param([string]$Serial, [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    Get-Command $Program -ErrorAction Stop | Out-Null
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Program @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $nativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($nativeExit -ne 0) { throw "Command failed (exit $nativeExit): $Program" }
}

$repoDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
if (-not $Serial) {
    $devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect exactly one device or supply -Serial' }
    $Serial = $devices[0]
}
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($abi -notin @('arm64-v8a', 'x86_64')) { throw "Unsupported ABI: $abi" }
$cmakeDir = Join-Path $Sdk 'cmake/3.22.1/bin'
$cmake = Join-Path $cmakeDir 'cmake.exe'
$toolchain = Join-Path $Sdk 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'
$buildDir = Join-Path $repoDir "build/native-runtime-tests/$abi"
Invoke-Checked $cmake @('-S', (Join-Path $repoDir 'app/src/test/cpp'), '-B', $buildDir,
    '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$(Join-Path $cmakeDir 'ninja.exe')",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain", "-DANDROID_ABI=$abi",
    '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Debug')
Invoke-Checked $cmake @('--build', $buildDir, '--parallel', '2')
$tests = @('harness_self_test', 'conversation_state_test', 'stream_protocol_test',
    'drain_completion_test', 'context_replay_test', 'native_handle_registry_test')
$remoteDir = '/data/local/tmp/prism-runtime-' + [guid]::NewGuid().ToString('N')
try {
    Invoke-Checked $adb @('-s', $Serial, 'shell', 'mkdir', '-p', $remoteDir)
    foreach ($test in $tests) {
        Invoke-Checked $adb @('-s', $Serial, 'push', (Join-Path $buildDir $test), "$remoteDir/$test")
        Invoke-Checked $adb @('-s', $Serial, 'shell', 'chmod', '755', "$remoteDir/$test")
        Invoke-Checked $adb @('-s', $Serial, 'shell', "$remoteDir/$test")
    }
} finally {
    if ($remoteDir -match '^/data/local/tmp/prism-runtime-[0-9a-f]{32}$') {
        & $adb -s $Serial shell rm -rf $remoteDir
    }
}
