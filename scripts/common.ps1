$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalDir = Join-Path $ProjectRoot '.local'
New-Item -ItemType Directory -Path $LocalDir -Force | Out-Null
$Tooling = @{}
if (Test-Path (Join-Path $LocalDir 'tooling.json')) {
    $Tooling = Get-Content (Join-Path $LocalDir 'tooling.json') -Raw | ConvertFrom-Json
}
function Invoke-Maven {
    param([string[]]$Arguments)
    $taskMaven = if ($env:STUDYROOM_MAVEN) { $env:STUDYROOM_MAVEN } elseif ($Tooling.maven) { $Tooling.maven } else { 'mvn.cmd' }
    $taskArgs = @('-B', '-ntp')
    if ($Tooling.repository) { $taskArgs += "-Dmaven.repo.local=$($Tooling.repository)" }
    & $taskMaven @taskArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Maven 执行失败: $LASTEXITCODE" }
}
