param([switch]$Build, [switch]$SkipContainers)
. "$PSScriptRoot/common.ps1"
if (!(Test-Path (Join-Path $LocalDir 'application-local.properties'))) {
    throw '请先把 application-local.example.properties 复制到 .local/application-local.properties 并填写连接配置。'
}
if (!$SkipContainers) {
    & docker compose --project-directory $ProjectRoot up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 未启动，请检查 Docker Desktop 和 .local/postgres.env。' }
    & docker start my-redis
    if ($LASTEXITCODE -ne 0) { throw 'Redis 未启动，请检查容器或使用 -SkipContainers 连接自行管理的服务。' }
}
$taskJar = Join-Path $ProjectRoot 'backend/target/studyroom-server-1.0.0.jar'
if ($Build -or !(Test-Path $taskJar)) { & "$PSScriptRoot/Build.ps1" }
$taskPort = if ($env:STUDYROOM_HOST_PORT) { $env:STUDYROOM_HOST_PORT } else { '8080' }
try {
    $taskHealth = Invoke-RestMethod "http://127.0.0.1:$taskPort/api/health" -TimeoutSec 2
    if ($taskHealth.data.backend -eq 'java') { Write-Host "系统已运行：http://127.0.0.1:$taskPort"; return }
    throw '端口被其他服务占用'
} catch {
    if (Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue) { throw "端口 $taskPort 已占用，请检查服务。" }
}
# Oracle javapath 可能是启动器，使用 java.home 中的真正 JVM，确保 PID 可管理。
$taskJavaSettings = & java.exe -XshowSettings:properties -version 2>&1 | Out-String
$taskJavaMatch = [regex]::Match($taskJavaSettings, '(?m)^\s*java.home\s*=\s*(.+)\r?$')
if (!$taskJavaMatch.Success) { throw '无法定位 JDK，请检查 java -version。' }
$taskJava = Join-Path $taskJavaMatch.Groups[1].Value.Trim() 'bin/java.exe'
$taskRunJar = Join-Path $LocalDir 'server.jar'
Copy-Item -LiteralPath $taskJar -Destination $taskRunJar -Force
$taskProcess = Start-Process -FilePath $taskJava -ArgumentList @('-Dfile.encoding=UTF-8','-jar', ('"' + $taskRunJar + '"')) -WorkingDirectory (Join-Path $ProjectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $LocalDir 'server.log') -RedirectStandardError (Join-Path $LocalDir 'server-error.log') -PassThru
$taskProcess.Id | Set-Content (Join-Path $LocalDir 'server.pid')
for ($taskAttempt = 0; $taskAttempt -lt 45; $taskAttempt++) {
    Start-Sleep -Seconds 1
    if ($taskProcess.HasExited) { throw '后端启动失败，请查看 .local/server.log 和 server-error.log' }
    try {
        $taskHealth = Invoke-RestMethod "http://127.0.0.1:$taskPort/api/health" -TimeoutSec 2
        if ($taskHealth.data.status -eq 'healthy') { Write-Host "系统已启动：http://127.0.0.1:$taskPort"; return }
    } catch { }
}
throw '启动超时，请查看 .local/server.log；进程可能仍在运行。'
