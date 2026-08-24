# SonarQube for IDE MCP Companion

[English](README.md) | 简体中文

这是一个独立的 IntelliJ companion plugin。它通过 IntelliJ 2026.1 内置 MCP Server 暴露
`sonar_ide_current_findings` 和 `sonar_ide_analyze_precommit`。前者从 SonarQube for IDE 的
公开 Project Service (`AnalysisSubmitter` / `OnTheFlyFindingsHolder`) 读取当前内存 Findings；
后者对调用方明确提供的已保存文件执行一次受限的 pre-commit 分析。

## 数据边界

当前插件提供的是 `sourceKind=on_the_fly`：它只覆盖 SonarQube for IDE 完成 on-the-fly 分析后
仍保留在内存中的 Findings。它不是 Jenkins 扫描结果、SonarQube Quality Gate 结果，也不是
原生提交动作的精确回调，因此始终返回 `snapshotExact=false`、`stalePossible=true`。

`sonar_ide_analyze_precommit` 调用公开的 `AnalysisSubmitter.analyzeFilesPreCommit`，等待全部
`CheckInCallable` 结果，并仅在完整成功时返回 `sourceKind=precommit`、
`snapshotExact=true`、`isCommitSnapshot=true`。它不会注册 `CheckinHandler`、监听 Commit
按钮或自动重复 IDEA 原生提交检查。文件必须先保存；快照仅对应本次显式传入的文件列表。

主 Sonar MCP 支持以下来源：

- `source=auto`（未配置 `SONAR_IDE_FINDINGS_SNAPSHOT` 时）：先调用 IDEA bridge，失败后才返回 H2 tracking，并在结果中保留
  `fallback` 原因；H2 结果标记为 `sourceKind=known_findings`、`stale=true`，不能当作实时 IDE 结果。
- 配置 `SONAR_IDE_FINDINGS_SNAPSHOT` 后，普通 `auto` 请求会优先读取该显式 JSON snapshot；带
  `requireCommitSnapshot=true` 和 `precommitFiles` 的请求仍强制调用 IDEA pre-commit API。
- 显式 pre-commit 分析或 JSON snapshot：只有同时包含 `sourceKind=precommit`、
  `snapshotExact=true`、`isCommitSnapshot=true`、有效 `projectPath` 和未过期 `capturedAt` 才会被
  主 MCP 当作精确提交快照。调用插件分析时必须同时提供 `requireCommitSnapshot=true` 和
  非空 `precommitFiles`；不可用就返回不可用，绝不降级到 H2。

## 构建与安装

构建需要 JDK 21、Gradle 8.10.2、IntelliJ IDEA 2026.1 和已安装的 SonarQube for IDE。
通过 Gradle 属性或环境变量提供两者的本机路径，不要把个人绝对路径提交到仓库：

```powershell
$env:INTELLIJ_LOCAL_PATH = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.1"
$env:SONARLINT_PLUGIN_PATH = Join-Path $env:APPDATA "JetBrains\IntelliJIdea2026.1\plugins\sonarlint-intellij"
```

也可以使用 `-PintellijLocalPath=...` 和 `-PsonarLintPluginPath=...` 覆盖这两个值。

在本目录执行：

```powershell
gradle buildPlugin --no-daemon
```

然后在 IDEA 中通过 `Settings > Plugins > 齿轮 > Install Plugin from Disk...`，选择
`build/distributions/` 下生成的 ZIP 安装。

本次命名空间调整同时将插件 ID 改为 `io.github.aie1123.sonar-ide-companion`。若 IDEA 中已安装
调整前的版本，请先卸载旧安装项，完全重启 IDEA 后再安装当前构建，避免两个插件同时注册
相同的 MCP 工具。

如果日志出现 `Read access is allowed from inside read-action only` 并指向
`SonarIdeToolset.findingJson`，说明仍在运行 0.1.0 或更早的插件。0.1.1 已将实时 Findings
和 pre-commit 结果中所有 `LiveFinding`/`Document` 读取统一放入 IntelliJ Read Action；安装
新 zip 后必须完全重启 IDEA，旧进程不会热加载新 jar。

0.1.2 进一步收敛了 pre-commit 文件校验：缺失、不可访问或切换项目期间失效的路径会返回
结构化的 `INVALID_REQUEST`、`ANALYSIS_CANCELLED` 或 `PROJECT_DISPOSED`，不再把
`NoSuchFileException` 作为 IDEA SEVERE ERROR 泄漏。调用方必须从 Git、IDE 或文件系统读取
并确认真实项目相对路径，不能根据 Java package 名手工推测目录。

安装后重启 IDEA，并确认 SonarQube for IDE 已安装、项目已打开且已完成一次分析。全局
MCP Server 也必须由用户在 `Tools > MCP Server` 手动启用并确认 JetBrains 的权限提示；
插件不会静默修改 IDEA 配置。

## IDEA MCP bridge

主 MCP 默认尝试：

```text
http://127.0.0.1:64342/stream
```

IDEA 端口可能动态变化，实际端口不同则在启动 Codex/Qoder MCP 的环境中显式设置：

```powershell
$env:SONAR_IDE_BRIDGE_URL = "http://127.0.0.1:<实际端口>/stream"
```

代理只接受无凭证、无 query/hash 的 literal loopback HTTP `/stream` 地址；`/sse`、HTTPS、
远程地址和带 token 的 URL 会被拒绝。不要把 IDEA、SonarQube 或 Jenkins 凭证写入 URL、
代码、日志或 Skill。

## 工具参数与返回

主 MCP 的 `sq_get_ide_findings` 要求绝对 `projectPath`，并支持 `source` (`auto`、`bridge`、
`h2`、`snapshot`)、`scope`、`findingType`、`filePath`、`fileGlob`、`limit`、
`requireCommitSnapshot`、`precommitFiles` 和 `precommitTimeoutSeconds`。精确分析限制为 1-200
个项目相对常规文件，单路径最多 2048 字符，拒绝绝对路径、`..`、目录、缺失文件和真实路径
越界；等待时间限制为 5-120 秒，同一项目不能并发执行两次显式 pre-commit 分析。

`sonar_ide_current_findings` 的 `projectPath` 在只打开一个项目时可省略；多项目同时打开时必须
传绝对路径，`file` 必须是项目相对路径。`sonar_ide_analyze_precommit` 始终要求绝对
`projectPath` 和明确的项目相对 `files` 列表。

返回 JSON 至少包含 `available`、`sourceKind`、`projectPath`、`fetchedAt`、`issues`、
`hotspots`、`issueCount` 和 `hotspotCount`。没有匹配项目或 SonarQube for IDE 服务不可用时，
返回 `available=false` 和明确 `status`，不伪装成零 Findings。

## 验证

插件构建完成后，重启 IDEA 并在 IDEA MCP 工具目录确认 `sonar_ide_current_findings` 和
`sonar_ide_analyze_precommit`。若同时维护独立的主 Sonar MCP，请在其工程目录运行
`npm test` 和 `npm run smoke`。若 Codex/Qoder 已经运行，需要分别重启对应宿主以刷新
MCP 工具目录。
