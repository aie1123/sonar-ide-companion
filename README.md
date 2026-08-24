# SonarQube for IDE MCP Companion

English | [简体中文](README.zh-CN.md)

This standalone IntelliJ companion plugin exposes `sonar_ide_current_findings` and
`sonar_ide_analyze_precommit` through the MCP Server bundled with IntelliJ IDEA 2026.1.
The first tool reads the in-memory findings held by SonarQube for IDE through its public
project services (`AnalysisSubmitter` and `OnTheFlyFindingsHolder`). The second tool runs a
bounded pre-commit analysis for an explicit list of saved files.

## Data boundaries

`sonar_ide_current_findings` returns `sourceKind=on_the_fly`. It only covers findings that
SonarQube for IDE produced through on-the-fly analysis and still retains in memory. It is not a
Jenkins scan, a SonarQube Quality Gate result, or an exact native commit callback, so it always
returns `snapshotExact=false` and `stalePossible=true`.

`sonar_ide_analyze_precommit` calls the public `AnalysisSubmitter.analyzeFilesPreCommit` API,
waits for every `CheckInCallable` result, and returns `sourceKind=precommit`,
`snapshotExact=true`, and `isCommitSnapshot=true` only after a complete successful analysis.
It does not register a `CheckinHandler`, observe the Commit button, or repeat IntelliJ's native
commit checks. Files must be saved first, and the snapshot only covers the explicit file list.

An independent primary Sonar MCP can consume several sources:

- `source=auto` without `SONAR_IDE_FINDINGS_SNAPSHOT` tries the IDEA bridge first. If the bridge
  is unavailable, it may return H2 tracking data together with the fallback reason. H2 results
  are marked `sourceKind=known_findings` and `stale=true`; they are not live IDE results.
- When `SONAR_IDE_FINDINGS_SNAPSHOT` is configured, ordinary `auto` requests prefer that explicit
  JSON snapshot. Requests with `requireCommitSnapshot=true` and `precommitFiles` still force the
  IDEA pre-commit API.
- A pre-commit analysis or JSON snapshot is exact only when it includes
  `sourceKind=precommit`, `snapshotExact=true`, `isCommitSnapshot=true`, a valid `projectPath`,
  and a non-expired `capturedAt`. A required commit snapshot never falls back to H2 data.

## Build and install

The build requires JDK 21, Gradle 8.10.2, IntelliJ IDEA 2026.1, and an installed copy of
SonarQube for IDE. Supply the two local plugin paths through Gradle properties or environment
variables. Do not commit personal absolute paths:

```powershell
$env:INTELLIJ_LOCAL_PATH = "D:\Program Files\JetBrains\IntelliJ IDEA 2026.1"
$env:SONARLINT_PLUGIN_PATH = Join-Path $env:APPDATA "JetBrains\IntelliJIdea2026.1\plugins\sonarlint-intellij"
```

You can also override them with `-PintellijLocalPath=...` and
`-PsonarLintPluginPath=...`.

Build the plugin from this directory:

```powershell
gradle buildPlugin --no-daemon
```

In IDEA, open `Settings > Plugins > gear icon > Install Plugin from Disk...` and select the
generated ZIP under `build/distributions/`.

The namespace cleanup changes the plugin ID to `io.github.aie1123.sonar-ide-companion`. If an
earlier build from before that cleanup is installed, uninstall the old entry and restart IDEA
completely before installing the current build. This prevents two plugins from registering the
same MCP tools.

If the IDEA log reports `Read access is allowed from inside read-action only` from
`SonarIdeToolset.findingJson`, the IDE is still running version 0.1.0 or older. Version 0.1.1
moved every `LiveFinding` and `Document` read used by live and pre-commit serialization into an
IntelliJ Read Action. IDEA must be restarted because it does not hot-reload the replacement JAR.

Version 0.1.2 also tightened pre-commit file validation. Missing, inaccessible, or invalidated
paths now return structured `INVALID_REQUEST`, `ANALYSIS_CANCELLED`, or `PROJECT_DISPOSED`
responses instead of leaking `NoSuchFileException` as an IDEA severe error. Callers must obtain
exact project-relative paths from Git, the IDE, or the file system rather than infer directories
from Java package names.

After restarting IDEA, confirm that SonarQube for IDE is installed, a project is open, and at
least one analysis has completed. The global MCP Server must also be enabled manually under
`Tools > MCP Server`, including any JetBrains permission prompt. This plugin never changes those
settings silently.

## IDEA MCP bridge

The primary MCP tries this endpoint by default:

```text
http://127.0.0.1:64342/stream
```

The IDEA port may change. If it does, set the actual endpoint in the environment that starts the
Codex or Qoder MCP process:

```powershell
$env:SONAR_IDE_BRIDGE_URL = "http://127.0.0.1:<actual-port>/stream"
```

The proxy accepts only credential-free literal loopback HTTP URLs ending in `/stream`, without
a query or fragment. It rejects `/sse`, HTTPS, remote hosts, and token-bearing URLs. Never place
IDEA, SonarQube, or Jenkins credentials in a URL, source file, log, or Skill.

## Tool parameters and responses

The primary MCP's `sq_get_ide_findings` tool requires an absolute `projectPath` and supports
`source` (`auto`, `bridge`, `h2`, or `snapshot`), `scope`, `findingType`, `filePath`, `fileGlob`,
`limit`, `requireCommitSnapshot`, `precommitFiles`, and `precommitTimeoutSeconds`.

An exact analysis accepts 1 to 200 regular project-relative files. Each path is limited to 2,048
characters. Absolute paths, `..`, directories, missing files, and real-path escapes are rejected.
The wait is limited to 5 to 120 seconds, and a project cannot run two explicit pre-commit analyses
at the same time.

For `sonar_ide_current_findings`, `projectPath` may be omitted only when exactly one project is
open; otherwise it must be absolute. The optional `file` filter is project-relative.
`sonar_ide_analyze_precommit` always requires an absolute `projectPath` and an explicit list of
project-relative `files`.

Response JSON includes at least `available`, `sourceKind`, `projectPath`, `fetchedAt`, `issues`,
`hotspots`, `issueCount`, and `hotspotCount`. An unavailable project or SonarQube for IDE service
returns `available=false` with an explicit `status`; it is never represented as zero findings.

## Verification

After building and restarting IDEA, confirm that the IDEA MCP catalog contains
`sonar_ide_current_findings` and `sonar_ide_analyze_precommit`. If you also maintain the
independent primary Sonar MCP, run `npm test` and `npm run smoke` from that separate repository.
Restart Codex or Qoder when necessary so each host refreshes its MCP tool catalog.
