#Requires -Version 5.1
<#
.SYNOPSIS
  一键起停全部服务（应用 + PostgreSQL/pgvector，可选 Ollama）。Windows 原生终端用这个。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up -Ai
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up -Edge
  powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 reset -Yes
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'reset', 'status', 'logs', 'psql')]
    [string]$Command = 'up',

    # 叠加 docker-compose.ai.yml：容器里跑 Ollama + BGE-M3（首次约 1.2 GB）
    [switch]$Ai,

    # 叠加 docker-compose.edge.yml：Caddy 反代 + TLS + 压缩 + 安全头（对外访问时用）
    [switch]$Edge,

    # reset 时跳过确认
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

function Write-Info($message) { Write-Host "· $message" }
function Stop-With($message) { Write-Host "✗ $message" -ForegroundColor Red; exit 1 }

# 原生命令失败时（docker 没起来、镜像不存在）会把 stderr 变成 ErrorRecord，
# 在 $ErrorActionPreference='Stop' 下直接抛异常——友好提示就走不到了。
# 这里临时放宽成 Continue，只取退出码。
function Invoke-Quiet {
    param([ScriptBlock]$Action)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Action 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-With '没找到 docker 命令：先装 Docker Desktop（https://www.docker.com/products/docker-desktop/）'
    }
    if ((Invoke-Quiet { docker compose version }) -ne 0) {
        Stop-With 'docker compose 不可用：Docker Desktop 需要开启 Compose v2'
    }
    if ((Invoke-Quiet { docker info }) -ne 0) {
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

# 给人抄的口令：去掉 0/O、1/l/I（等宽与无衬线字体里几乎分不清，抄错一位就是"用户名或口令不对"）。
# 其余密钥仍用 New-Secret——那是机器读的，长度比可读性值钱。
function New-Password([int]$length = 12) {
    $alphabet = 'abcdefghjkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXY23456789'
    $limit = 256 - (256 % $alphabet.Length)   # 拒绝采样，避免取模偏置
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $builder = New-Object System.Text.StringBuilder
    while ($builder.Length -lt $length) {
        $one = New-Object byte[] 1
        $rng.GetBytes($one)
        if ($one[0] -lt $limit) { [void]$builder.Append($alphabet[$one[0] % $alphabet.Length]) }
    }
    $rng.Dispose()
    return $builder.ToString()
}

function Initialize-EnvFile {
    if (Test-Path .env) { return }
    Write-Info '首次运行：从 .env.example 生成 .env，并写入随机密钥'
    Copy-Item .env.example .env
    $values = @{
        POSTGRES_PASSWORD      = New-Secret 24
        PSYCH_USER_SECRET      = New-Secret 32
        PSYCH_INTERNAL_TOKEN   = (New-Secret 32).Substring(0, 24)
        PSYCH_OPERATOR_PASSWORD = New-Password 12
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

# 容器档 dev-hint 是关的，登录页没有任何内置账号；没有这行口令就没人能登进去。
# 只在缺的时候补：已有口令的人重启多少次都不变，改过的口令也不会被覆盖。
function Ensure-OperatorCredential {
    $map = Get-EnvMap
    if ($map['PSYCH_OPERATOR_PASSWORD']) { return }
    $user = if ($map['PSYCH_OPERATOR_USER']) { $map['PSYCH_OPERATOR_USER'] } else { 'admin' }
    $pass = New-Password 12
    Write-Info '生成后台初始账号口令并写入 .env（首次启动时写进数据库）'
    $lines = @(Get-Content .env -Encoding UTF8 |
        Where-Object { $_ -notmatch '^PSYCH_OPERATOR_(USER|PASSWORD)=' })
    $lines += "PSYCH_OPERATOR_USER=$user"
    $lines += "PSYCH_OPERATOR_PASSWORD=$pass"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines((Join-Path (Get-Location) '.env'), $lines, $utf8NoBom)
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
    if ($Edge) { $base += @('-f', 'docker-compose.edge.yml') }
    # compose 失败时会把原因写 stderr；Stop 模式下那会变成异常，
    # 用户看不到"退出码 + 日志"两行。放宽成 Continue，让输出照常打出来。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker compose @base @args
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) { Stop-With "docker compose 执行失败（exit $LASTEXITCODE）" }
}

# Docker Hub 在部分网络下拉不动。配了 DOCKER_MIRROR 就先经镜像源拉取再打回标准标签，
# 仓库里的镜像名保持不变；没配就交给 compose 自己拉。
function Ensure-Images {
    $map = Get-EnvMap
    $mirror = $map['DOCKER_MIRROR']
    $images = @('pgvector/pgvector:pg16', 'maven:3.9-eclipse-temurin-21', 'eclipse-temurin:21-jre-alpine')
    if ($Ai) { $images += 'ollama/ollama:latest' }
    foreach ($img in $images) {
        if ((Invoke-Quiet { docker image inspect $img }) -eq 0) { continue }
        if (-not $mirror) { continue }
        $src = if ($img -match '^(pgvector|ollama)/') { "$mirror/$img" } else { "$mirror/library/$img" }
        Write-Info "从镜像源拉取 $img"
        if ((Invoke-Quiet { docker pull $src }) -ne 0) {
            Stop-With "拉取 $src 失败：检查 .env 里的 DOCKER_MIRROR 是否可用"
        }
        Invoke-Quiet { docker tag $src $img } | Out-Null
    }
}

function Wait-Healthy {
    $envMap = Get-EnvMap
    $port = if ($envMap.APP_PORT) { $envMap.APP_PORT } else { '8099' }
    $url = "http://127.0.0.1:$port/actuator/health"
    Write-Info '等应用健康（最长 240 秒，首次要构建镜像与灌示例语料）'
    for ($waited = 0; $waited -lt 240; $waited += 3) {
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
    Write-Host '✗ 240 秒内没等到健康检查，最近日志：' -ForegroundColor Red
    Invoke-Compose ps
    Invoke-Compose logs --tail 60 app
    exit 1
}

function Show-Summary {
    $envMap = Get-EnvMap
    $port = if ($envMap.APP_PORT) { $envMap.APP_PORT } else { '8099' }
    $dbPort = if ($envMap.POSTGRES_PORT) { $envMap.POSTGRES_PORT } else { '15432' }
    $opUser = if ($envMap.PSYCH_OPERATOR_USER) { $envMap.PSYCH_OPERATOR_USER } else { 'admin' }
    $opPass = $envMap.PSYCH_OPERATOR_PASSWORD
    $model = if ($envMap.OLLAMA_EMBED_MODEL) { $envMap.OLLAMA_EMBED_MODEL } else { 'bge-m3' }
    $chat = if ($envMap.OLLAMA_CHAT_MODEL) { $envMap.OLLAMA_CHAT_MODEL } else { 'qwen3-8b' }
    Write-Host ''
    Write-Host "  产品房间      http://localhost:$port/room/"
    Write-Host "  知识问答      http://localhost:$port/knowledge/"
    Write-Host "  内部后台      http://localhost:$port/internal"
    Write-Host "  内部令牌      $($envMap.PSYCH_INTERNAL_TOKEN)"
    Write-Host "  数据库        localhost:$dbPort  ($($envMap.POSTGRES_USER)/$($envMap.POSTGRES_DB))"
    if ($opPass) {
        Write-Host "  后台登录      $opUser / $opPass"
        Write-Host '                （/internal/login；口令只在首次启动写入，后台改过就不再是这一行）'
    }
    if ($Edge) {
        $site = if ($envMap.SITE_ADDRESS) { $envMap.SITE_ADDRESS } else { 'localhost' }
        Write-Host "  边缘层        https://$site/  （Caddy 自签证书时浏览器会提示一次不安全）"
    }
    if ($Ai) {
        Write-Host "  模型          对话 $chat + 向量 $model（容器内 Ollama，1024 维）"
    } else {
        Write-Host '  模型          桩（哈希 256 维，零成本）；要真模型加 -Ai'
    }
    Write-Host ''
    Write-Host '  跟日志：scripts\stack.ps1 logs      停止：scripts\stack.ps1 down'
}

switch ($Command) {
    'up' {
        Assert-Docker
        Initialize-EnvFile
        Ensure-OperatorCredential
        Ensure-Images
        Write-Info '构建并启动容器（首次较慢：要编译并下 PG 镜像）'
        if ($Ai) {
            Write-Info '带模型档：首次要拉 bge-m3 约 1.2 GB + qwen3-8b 约 5 GB，CPU 推理单轮可能几十秒'
        }
        try {
            Invoke-Compose up -d --build
        } catch {
            Write-Host '✗ 启动失败。最常见的两个原因：' -ForegroundColor Red
            Write-Host '  · 拉不到 Docker Hub 镜像：在 .env 里设 DOCKER_MIRROR=docker.m.daocloud.io 后重试；'
            Write-Host '  · Docker Desktop 没配代理：设置 → Resources → Proxies。'
            exit 1
        }
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
