# 本地语音转写：调用 Windows 自带的 System.Speech 识别器，把 WAV 转成文字。
#
# 由 AsrService 以子进程方式调用，不联网、不落库；临时 WAV 由调用方删除。
# 需要系统装有对应语言的语音识别包（本机验证：zh-CN / MS-2052-80-DESK）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/asr_transcribe.ps1 -Path audio.wav

param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Result
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Speech

$culture = [System.Globalization.CultureInfo]::GetCultureInfo("zh-CN")
$recognizer = $null
try {
    $recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine($culture)
} catch {
    $recognizer = New-Object System.Speech.Recognition.SpeechRecognitionEngine
}

$recognizer.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
$recognizer.SetInputToWaveFile($Path)

$recognized = $recognizer.Recognize()
$text = ""
if ($null -ne $recognized) {
    $text = $recognized.Text
}

# 结果写文件而不是 stdout：管道编码会随系统代码页变化，中文经 stdout 回来会变成乱码。
# 文件明确 UTF-8，调用方按 UTF-8 读。
[System.IO.File]::WriteAllText($Result, $text, (New-Object System.Text.UTF8Encoding($false)))

$recognizer.Dispose()
