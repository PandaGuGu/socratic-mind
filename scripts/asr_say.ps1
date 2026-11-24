# 开发辅助：用 Windows 语音合成生成一段 WAV，供语音转写自测使用。
# 不参与产品运行；只在验证 scripts/asr_transcribe.ps1 时用。
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Text = "认知检验一般分为五步"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SetOutputToWaveFile($Out)
$synth.Speak($Text)
$synth.Dispose()
Write-Output $Out
