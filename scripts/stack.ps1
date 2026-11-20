#Requires -Version 5.1
<#
.SYNOPSIS
  一键起停全部服务（应用 + PostgreSQL/pgvector，可选 Ollama）。Windows 原生终端用这个。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up -Ai
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 reset -Yes
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'reset', 'status', 'logs', 'psql')]
    [string]$Command = 'up',

    # 叠加 docker-compose.ai.yml：容器里跑 Ollama + BGE-M3（首次约 1.2 GB）
    [switch]$Ai,

    # reset 时跳过确认
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

function Write-Info($message) { Write-Host "· $message" }
function Stop-With($message) { Write-Host "✗ $message" -ForegroundColor Red; exit 1 }

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-With '没找到 docker 命令：先装 Docker Desktop（https://www.docker.com/products/docker-desktop/）'
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Stop-With 'docker compose 不可用：Docker Desktop 需要开启 Compose v2' }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Stop-With 'Docker 守护进程没起来：先打开 Docker Desktop，等鲸鱼图标变成运行中，再重跑本脚本'
    }
}

function New-Secret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buffer)
    $rng.Dispose()
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Initialize-EnvFile {
    if (Test-Path .env) { return }
    Write-Info '首次运行：从 .env.example 生成 .env，并写入随机密钥'
    Copy-Item .env.example .env
    $values = @{
        POSTGRES_PASSWORD    = New-Secret 24
        PSYCH_USER_SECRET    = New-Secret 32
        PSYCH_INTERNAL_TOKEN = (New-Secret 32).Substring(0, 24)
    }
    $lines = Get-Content .env -Encoding UTF8
    $out = foreach ($line in $lines) {
        $written = $line
        foreach ($key in $values.Keys) {
            if ($line -match "^$key=\s*$") { $written = "$key=$($values[$key])" }
        }
        $written
    }
    # 必须无 BOM：Docker Compose 读带 BOM 的 .env 会把第一个变量名读成 "\ufeffAPP_PORT"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines((Join-Path (Get-Location) '.env'), $out, $utf8NoBom)
}

function Get-EnvMap {
    if (-not (Test-Path .env)) { Stop-With '没有 .env：先跑 scripts\stack.ps1 up' }
    $map = @{}
    foreach ($line in Get-Content .env -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $name, $value = $line.Split('=', 2)
        $map[$name.Trim()] = $value.Trim()
    }
    return $map
}

function Invoke-Compose {
    $base = @('-f', 'docker-compose.yml')
    if ($Ai) { $base += @('-f', 'docker-compose.ai.yml') }
    & docker compose @base @args
    if ($LASTEXITCODE -ne 0) { Stop-With "docker compose 执行失败（exit $LASTEXITCODE）" }
}

function Wait-Healthy {
    $envMap = Get-EnvMap
    $port = if ($envMap.APP_PORT) { $envMap.APP_PORT } else { '8099' }
    $url = "http://127.0.0.1:$port/actuator/health"
    Write-Info '等应用健康（最长 180 秒，首次要构建镜像与灌示例语料）'
    for ($waited = 0; $waited -lt 180; $waited += 3) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                Write-Host "✓ 应用已就绪：$url" -ForegroundColor Green
                return
            }
        } catch {
            Start-Sleep -Seconds 3
        }
        if ($waited -gt 0 -and $waited % 30 -eq 0) { Write-Host "  …已等 ${waited}s" }
    }
    Write-Host '✗ 180 秒内没等到健康检查，最近日志：' -ForegroundColor Red
    Invoke-Compose ps
    Invoke-Compose logs --tail 60 app
    exit 1
}

function Show-Summary {
    $envMap = Get-EnvMap
    $port = if ($envMap.APP_PORT) { $envMap.APP_PORT } else { '8099' }
    $dbPort = if ($envMap.POSTGRES_PORT) { $envMap.POSTGRES_PORT } else { '15432' }
    $model = if ($envMap.OLLAMA_EMBED_MODEL) { $envMap.OLLAMA_EMBED_MODEL } else { 'bge-m3' }
    Write-Host ''
    Write-Host "  产品房间      http://localhost:$port/room/"
    Write-Host "  知识问答      http://localhost:$port/knowledge/"
    Write-Host "  内部后台      http://localhost:$port/internal"
    Write-Host "  内部令牌      $($envMap.PSYCH_INTERNAL_TOKEN)"
    Write-Host "  数据库        localhost:$dbPort  ($($envMap.POSTGRES_USER)/$($envMap.POSTGRES_DB))"
    if ($Ai) {
        Write-Host "  向量模型      $model（容器内 Ollama，1024 维）"
    } else {
        Write-Host '  向量模型      桩（哈希 256 维，零成本）；要真向量加 -Ai'
    }
    Write-Host ''
    Write-Host '  跟日志：scripts\stack.ps1 logs      停止：scripts\stack.ps1 down'
}

switch ($Command) {
    'up' {
        Assert-Docker
        Initialize-EnvFile
        Write-Info '构建并启动容器（首次较慢：要下 JDK/Maven/PG 镜像并编译）'
        Invoke-Compose up -d --build
        Wait-Healthy
        Show-Summary
    }
    'down' {
        Assert-Docker
        Invoke-Compose down
        Write-Host '✓ 已停止（数据卷保留；要清空用 reset）' -ForegroundColor Green
    }
    'reset' {
        Assert-Docker
        if (-not $Yes) {
            Write-Host '这会删除数据库与后台配置的全部数据，不可恢复。' -ForegroundColor Yellow
            $answer = Read-Host '确认清空？输入 yes 继续'
            if ($answer -ne 'yes') { Stop-With '已取消' }
        }
        Invoke-Compose down -v
        Write-Host '✓ 已停止并删除数据卷' -ForegroundColor Green
    }
    'status' {
        Assert-Docker
        Invoke-Compose ps
    }
    'logs' {
        Assert-Docker
        Invoke-Compose logs -f --tail 100
    }
    'psql' {
        Assert-Docker
        $envMap = Get-EnvMap
        & docker compose -f docker-compose.yml exec postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB
    }
}
