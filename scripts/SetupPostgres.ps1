. "$PSScriptRoot/common.ps1"
$taskEnvPath = Join-Path $LocalDir 'postgres.env'
if (!(Test-Path $taskEnvPath)) {
    $taskPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    [IO.File]::WriteAllText($taskEnvPath, "POSTGRES_PASSWORD=$taskPassword`n", [Text.UTF8Encoding]::new($false))
}
$taskPassword = ((Get-Content $taskEnvPath | Where-Object { $_ -match '^POSTGRES_PASSWORD=' }) -split '=',2)[1]
if ([string]::IsNullOrWhiteSpace($taskPassword)) { throw 'postgres.env 中缺少 POSTGRES_PASSWORD。' }
$taskConfigPath = Join-Path $LocalDir 'application-local.properties'
if (Test-Path $taskConfigPath) { $taskLines = @(Get-Content $taskConfigPath) }
else {
    $taskSecret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
    $taskLines = @('spring.redis.host=127.0.0.1','spring.redis.port=6379','spring.redis.database=3','spring.redis.password=',"studyroom.jwt-secret=$taskSecret")
}
$taskLines = @($taskLines | Where-Object { $_ -notmatch '^spring.datasource\.' })
$taskLines += @('spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/studyroom_java','spring.datasource.username=studyroom',"spring.datasource.password=$taskPassword")
[IO.File]::WriteAllLines($taskConfigPath,$taskLines,[Text.UTF8Encoding]::new($false))
& docker compose --project-directory $ProjectRoot up -d --wait db
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 部署失败。请检查 Docker Desktop 或5433端口占用。' }
Write-Host 'PostgreSQL 已就绪：127.0.0.1:5433；开发库 studyroom_java，测试库 studyroom_java_test。'
Write-Host '口令保存于 .local/postgres.env 和应用本地配置；重复运行不会轮换口令或清空数据。'
