$ErrorActionPreference = "Stop"

$ideaPathValue = $env:INTELLIJ_LOCAL_PATH
if ([string]::IsNullOrWhiteSpace($ideaPathValue)) {
    throw "Set the INTELLIJ_LOCAL_PATH environment variable before running build-local.ps1"
}
$ideaPath = (Resolve-Path -LiteralPath $ideaPathValue).Path
$sonarPathValue = $env:SONARLINT_PLUGIN_PATH
if ([string]::IsNullOrWhiteSpace($sonarPathValue)) {
    throw "Set the SONARLINT_PLUGIN_PATH environment variable before running build-local.ps1"
}
$sonarPath = (Resolve-Path -LiteralPath $sonarPathValue).Path
$kotlinHome = Join-Path $ideaPath "plugins\Kotlin\kotlinc"
$outputPath = Join-Path $PSScriptRoot "build\classes\kotlin\main"
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$jarRoots = @(
    (Join-Path $ideaPath "lib"),
    (Join-Path $ideaPath "plugins\mcpserver\lib"),
    (Join-Path $sonarPath "lib")
)
# Pass the long classpath through the Java launcher's response file. Kotlin's own response
# file parser treats Windows backslashes as escapes, while the Java launcher accepts the
# forward-slash paths below without truncating the command line.
$classpath = [string]::Join(';', ($jarRoots | ForEach-Object {
    (Get-ChildItem -LiteralPath $_ -Filter "*.jar" | Select-Object -ExpandProperty FullName)
})).Replace('\', '/')
$sourcePath = Join-Path $PSScriptRoot "src\main\kotlin\com\cmi\codex\sonar\SonarIdeToolset.kt"
$compilerJar = Join-Path $kotlinHome "lib\kotlin-compiler.jar"
$javaPath = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    (Get-Command java -ErrorAction Stop).Source
}
$javaArgumentFile = Join-Path $env:TEMP "sonar-ide-companion-java.args"
@(
    "-Xmx2g",
    "-cp",
    ('"' + $compilerJar.Replace('\', '/') + '"'),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-jvm-target",
    "21",
    "-classpath",
    ('"' + $classpath + '"'),
    "-d",
    ('"' + $outputPath.Replace('\', '/') + '"'),
    ('"' + $sourcePath.Replace('\', '/') + '"')
) | Set-Content -LiteralPath $javaArgumentFile -Encoding ascii
& $javaPath ("@" + $javaArgumentFile)
if ($LASTEXITCODE -ne 0) {
    throw "Kotlin compilation failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Force -Path (Join-Path $outputPath "META-INF") | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "src\main\resources\META-INF\plugin.xml") `
    -Destination (Join-Path $outputPath "META-INF\plugin.xml") -Force
Write-Output "Compiled classes: $outputPath"
