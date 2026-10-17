param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.,#]+$')][string]$Class,
    [Parameter(Mandatory = $true)][ValidateRange(1, 10000)][int]$ExpectedTests,
    [ValidatePattern('^[A-Za-z0-9_.]+$')][string]$TestPackage = 'com.prismai.llmhost.dev.debug.test',
    [string]$Serial,
    [string]$LogPath,
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
if (-not $Serial) {
    $devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect exactly one device or supply -Serial' }
    $Serial = $devices[0]
}
$result = @(& $adb -s $Serial shell am instrument -w -r -e class $Class "$TestPackage/androidx.test.runner.AndroidJUnitRunner")
$adbExit = $LASTEXITCODE
if ($LogPath) {
    $parent = Split-Path -Parent $LogPath
    if ($parent) { New-Item -ItemType Directory -Force $parent | Out-Null }
    $result | Set-Content -Encoding utf8 $LogPath
}
$result | Write-Output
$transcript = $result -join "`n"
# am instrument can exit 0 even when JUnit fails. Require the complete expected
# success summary and reject skipped tests, crashes and instrumentation errors.
if ($adbExit -ne 0 -or $transcript -notmatch "OK \($ExpectedTests tests?\)" -or
    $transcript -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]') {
    throw "Instrumentation did not pass all $ExpectedTests expected tests"
}
