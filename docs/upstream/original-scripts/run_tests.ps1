# 智能共享自习室预约系统 - 全套测试自动化运行脚本 (PowerShell)
# 依次执行:
# 1. 后端单元测试 (Go test)
# 2. API 接口全链路端到端集成测试 (Python)
# 3. 高并发抢座与互斥防超卖测试 (Python)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  智能共享自习室预约系统 - 自动化测试套件" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. 后端单元测试
Write-Host "`n[第 1 阶段] 运行 Go 后端单元测试..." -ForegroundColor Yellow
Push-Location "$PSScriptRoot\..\backend"
try {
    go test ./... -v
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Go 单元测试执行失败!" -ForegroundColor Red
        exit 1
    }
} finally {
    Pop-Location
}
Write-Host "[PASS] 后端单元测试全部通过!`n" -ForegroundColor Green

# 2. 端到端 API 集成测试
Write-Host "[第 2 阶段] 运行 API 自动化端到端集成测试..." -ForegroundColor Yellow
python "$PSScriptRoot\test_api_e2e.py"
if ($LASTEXITCODE -ne 0) {
    Write-Host "API 端到端集成测试执行失败!" -ForegroundColor Red
    exit 1
}

# 3. 高并发抢座压力测试
Write-Host "`n[第 3 阶段] 运行 20 并发抢座互斥与性能测试..." -ForegroundColor Yellow
python "$PSScriptRoot\test_concurrency.py"
if ($LASTEXITCODE -ne 0) {
    Write-Host "高并发抢座测试执行失败!" -ForegroundColor Red
    exit 1
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "  恭喜! 全套测试 (单测 + 接口集成 + 并发互斥) 全部通过!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
