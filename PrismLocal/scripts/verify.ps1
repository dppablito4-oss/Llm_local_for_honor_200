# Local gate. CI also runs portable C++ tests with sanitizers on Linux.
# The placeholder switch builds Play offline; it does not validate authentication.
param([switch]$UseCiAuthPlaceholder, [switch]$NativeTestsOnDevice)
$ErrorActionPreference = "Stop"
Push-Location -LiteralPath (Join-Path $PSScriptRoot "..")
try {
    & .\gradlew.bat --no-daemon --max-workers=2 :app:testDevDebugUnitTest :app:testPlayDebugUnitTest :app:lintDevDebug :app:lintPlayDebug :app:assembleDevDebug
    if ($LASTEXITCODE -ne 0) { throw "Debug/lint/JVM gate failed ($LASTEXITCODE)" }
    # One minified variant at a time keeps R8 within host memory limits.
    & .\gradlew.bat --no-daemon --max-workers=2 :app:assembleDevBenchmark
    if ($LASTEXITCODE -ne 0) { throw "Benchmark gate failed ($LASTEXITCODE)" }
    $playArgs = @('--no-daemon', '--max-workers=2', ':app:assemblePlayRelease')
    if ($UseCiAuthPlaceholder) { $playArgs += '-PLLMHOST_SUPABASE_ANON_KEY=ci-public-build-placeholder' }
    & .\gradlew.bat @playArgs
    if ($LASTEXITCODE -ne 0) { throw "Play gate failed ($LASTEXITCODE)" }
    # A stale unsigned APK may coexist with a newly signed output.
    $playDir = 'app/build/outputs/apk/play/release'
    $playMetadata = Get-Content -Raw (Join-Path $playDir 'output-metadata.json') | ConvertFrom-Json
    $apks = @('app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk',
        (Join-Path $playDir $playMetadata.elements[0].outputFile))
    & python (Join-Path $PSScriptRoot 'check-apk-jni.py') @apks
    if ($LASTEXITCODE -ne 0) { throw 'Minified JNI contract check failed' }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $zipalign = Join-Path $sdk 'build-tools/36.0.0/zipalign.exe'
    foreach ($apk in @('app/build/outputs/apk/dev/debug/app-dev-debug.apk') + $apks) {
        & $zipalign -c -P 16 4 $apk
        if ($LASTEXITCODE -ne 0) { throw "APK alignment failed: $apk" }
    }
    if ($NativeTestsOnDevice) { & (Join-Path $PSScriptRoot 'device/test-native-runtime.ps1') }
} finally {
    Pop-Location
}
