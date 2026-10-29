. "$PSScriptRoot/common.ps1"
$taskExpectedJar = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'backend/target/studyroom-server-1.0.0.jar'))
$taskRunJar = [IO.Path]::GetFullPath((Join-Path $LocalDir 'server.jar'))
# 同时识别旧 javapath 启动器产生的子进程，严格按当前项目绝对路径匹配。
$taskProcesses = Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object {
    $_.CommandLine -and ($_.CommandLine.Contains($taskExpectedJar) -or $_.CommandLine.Contains($taskRunJar))
}
foreach ($taskProcess in $taskProcesses) {
    Stop-Process -Id $taskProcess.ProcessId -Force
    Wait-Process -Id $taskProcess.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    Write-Host "已停止本项目 Java 进程 $($taskProcess.ProcessId)。"
}
$taskPidPath = Join-Path $LocalDir 'server.pid'
if (Test-Path $taskPidPath) { Remove-Item -LiteralPath $taskPidPath }
