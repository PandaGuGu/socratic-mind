#Requires -Version 5.1
<#
.SYNOPSIS
  本机起停应用（不依赖 Docker）：H2 文件库 + 桩模型，一条命令常驻后台。

.DESCRIPTION
  和 stack.ps1 的区别：那个起 PostgreSQL 容器，适合验证 pgvector 与持久化；
  这个只跑一个 jar，适合"随时打开体验一下"。进程用 Start-Process 拉起，
  不挂在当前终端上——关掉终端、关掉编辑器都不会把它带走。

  数据落在 backend/data/cbt.mv.db，重启保留。要回到干净状态就删掉它。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\local-app.ps1 up
  powershell -ExecutionPolicy Bypass -File scripts\local-app.ps1 status
  powershell -ExecutionPolicy Bypass -File scripts\local-app.ps1 down
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'status', 'logs')]
    [string]$Command = 'up',

    [int]$Port = 8080,

    # 想接本机 Ollama 就去掉 stub：-Profile h2
    [string]$Profile = 'h2,stub'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jarDir = Join-Path $root 'backend\psych-app\target'
$runDir = Join-Path $root 'backend\target'
$pidFile = Join-Path $runDir 'local-app.pid'
$outLog = Join-Path $runDir 'local-app.out.log'
$errLog = Join-Path $runDir 'local-app.err.log'
$base = "http://localhost:$Port"

function Get-RunningPid {
    if (-not (Test-Path $pidFile)) { return $null }
    $raw = (Get-Content $pidFile -Raw).Trim()
    if (-not $raw) { return $null }
    $proc = Get-Process -Id ([int]$raw) -ErrorAction SilentlyContinue
    if ($proc) { return [int]$raw }
    return $null
}

function Test-Healthy {
    try {
        $res = Invoke-WebRequest -Uri "$base/actuator/health" -UseBasicParsing -TimeoutSec 3
        return $res.StatusCode -eq 200
    } catch {
        return $false
    }
}

switch ($Command) {
    'up' {
        $running = Get-RunningPid
        if ($running) {
            Write-Host "已经在跑（PID $running）→ $base/room/"
            exit 0
        }
        # 版本号每次发版都会变，脚本按最新的 psych-app-*.jar 取，不用跟着改版本号。
        $jar = Get-ChildItem -Path $jarDir -Filter 'psych-app-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not $jar -or -not (Test-Path $jar)) {
            Write-Host "缺少 jar：$jar" -ForegroundColor Red
            Write-Host "先构建：cd backend; mvn -o -DskipTests package"
            exit 1
        }
        New-Item -ItemType Directory -Force -Path $runDir | Out-Null
        # 工作目录必须是 backend：H2 的连接串是相对路径（./data/cbt），
        # 从仓库根启动会把库建在根目录，从 backend 启动会建在 backend/data——
        # 同一个命令两种位置，重启后"数据不见了"就是这么来的。
        $appDir = Join-Path $root 'backend'
        Write-Host "· 启动中（$Profile，端口 $Port）…"
        # -WindowStyle Hidden + 独立进程：关掉终端不会带走它。
        $proc = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $appDir `
            -ArgumentList @('-jar', $jar, "--spring.profiles.active=$Profile", "--server.port=$Port") `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog
        Set-Content -Path $pidFile -Value $proc.Id -NoNewline

        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Milliseconds 1000
            if (Test-Healthy) {
                Write-Host "✓ 已就绪（PID $($proc.Id)）" -ForegroundColor Green
                Write-Host "  房间      $base/room/"
                Write-Host "  知识库    $base/knowledge/"
                Write-Host "  内部后台  $base/internal/"
                Write-Host "  评测      $base/internal/eval.html"
                exit 0
            }
            if ($proc.HasExited) {
                Write-Host "✗ 进程退出，看日志：$errLog" -ForegroundColor Red
                exit 1
            }
        }
        Write-Host "✗ 60 秒内没起来，看日志：$outLog" -ForegroundColor Red
        exit 1
    }
    'down' {
        $running = Get-RunningPid
        if (-not $running) {
            Write-Host "没有在跑（或 pid 文件已失效）"
            Remove-Item -Force -ErrorAction SilentlyContinue $pidFile
            exit 0
        }
        Stop-Process -Id $running -Force
        Remove-Item -Force -ErrorAction SilentlyContinue $pidFile
        Write-Host "✓ 已停止（PID $running）" -ForegroundColor Green
    }
    'status' {
        $running = Get-RunningPid
        if ($running -and (Test-Healthy)) {
            Write-Host "在跑（PID $running）→ $base/room/" -ForegroundColor Green
        } elseif ($running) {
            Write-Host "进程在（PID $running）但健康检查不通，可能还在启动"
        } else {
            Write-Host "没在跑"
        }
    }
    'logs' {
        if (Test-Path $outLog) { Get-Content $outLog -Tail 60 }
        else { Write-Host "还没有日志：$outLog" }
    }
}
