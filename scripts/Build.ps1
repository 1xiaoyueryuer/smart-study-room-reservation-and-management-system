param([switch]$Integration, [switch]$Offline)
. "$PSScriptRoot/common.ps1"
Push-Location (Join-Path $ProjectRoot 'frontend')
try {
    $taskNpmArgs = @('ci', '--prefer-offline')
    if ($Tooling.npmCache) { $taskNpmArgs += @('--cache', $Tooling.npmCache) }
    if ($Offline) { $taskNpmArgs += '--offline' }
    & npm.cmd @taskNpmArgs
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
} finally { Pop-Location }
Push-Location (Join-Path $ProjectRoot 'backend')
$taskPreviousIntegration = $env:STUDYROOM_INTEGRATION
try {
    if ($Integration) { $env:STUDYROOM_INTEGRATION = 'true' }
    Invoke-Maven -Arguments @('test', 'package')
} finally {
    $env:STUDYROOM_INTEGRATION = $taskPreviousIntegration
    Pop-Location
}
