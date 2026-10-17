param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Serial,
    [string]$Sdk = $env:ANDROID_HOME,
    [ValidateSet('MinifiedJniSmoke', 'NativeLifecycleSmoke')][string]$Harness = 'MinifiedJniSmoke',
    [string]$LlmModel,
    [string]$ModelsPackage,
    [string]$DiffusionModelRelativePath
)
$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    Get-Command $Program -ErrorAction Stop | Out-Null
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell redirects native stderr as ErrorRecords, even for
        # successful adb progress output. The process exit code is authoritative.
        $ErrorActionPreference = 'Continue'
        & $Program @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $nativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($nativeExit -ne 0) { throw "Command failed (exit $nativeExit): $Program" }
}

$repoDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$apkFile = (Resolve-Path -LiteralPath $Apk).Path
if ($Harness -eq 'NativeLifecycleSmoke') {
    if (-not $LlmModel -or $ModelsPackage -notmatch '^[a-zA-Z0-9._]+$' -or
        $DiffusionModelRelativePath -notmatch '^files/models/[a-zA-Z0-9._/-]+\.gguf$' -or
        ($DiffusionModelRelativePath -split '/') -contains '..') {
        throw 'Lifecycle smoke requires -LlmModel, -ModelsPackage and a files/models/*.gguf path'
    }
    $LlmModel = (Resolve-Path -LiteralPath $LlmModel).Path
}
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$buildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'd8.bat') } |
    Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
    Sort-Object Name -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName 'android.jar' }
if (-not $buildTools -or -not $androidJar) { throw 'Android build tools/platform not found' }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
if (-not $Serial) {
    $devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect exactly one device or supply -Serial' }
    $Serial = $devices[0]
}
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($abi -notin @('arm64-v8a', 'x86_64')) { throw "Unsupported ABI: $abi" }
$runId = [guid]::NewGuid().ToString('N')
$workDir = Join-Path $repoDir "build/jni-smoke/$runId"
$classesDir = Join-Path $workDir 'classes'
$dexDir = Join-Path $workDir 'dex'
$libsDir = Join-Path $workDir 'lib'
New-Item -ItemType Directory -Force $classesDir, $dexDir, $libsDir | Out-Null
Invoke-Checked $javac @('--release', '8', '-classpath', $androidJar,
    '-d', $classesDir, (Join-Path $PSScriptRoot "$Harness.java"))
$javaClasses = @(Get-ChildItem $classesDir -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-Checked (Join-Path $buildTools.FullName 'd8.bat') (@('--min-api', '26', '--lib',
    $androidJar, '--output', $dexDir) + $javaClasses)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apkFile)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -match "^lib/$abi/[^/]+\.so$") {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry, (Join-Path $libsDir $entry.Name))
        }
    }
} finally { $archive.Dispose() }
if (-not (Test-Path (Join-Path $libsDir 'libllmhost.so'))) { throw 'APK lacks libllmhost.so' }

# A fresh shell-owned directory; no install/uninstall, app storage access or data reset.
$remoteDir = "/data/local/tmp/prism-jni-$runId"
try {
    Invoke-Checked $adb @('-s', $Serial, 'shell', 'mkdir', '-p', "$remoteDir/lib", "$remoteDir/cache")
    Invoke-Checked $adb @('-s', $Serial, 'push', $apkFile, "$remoteDir/target.apk")
    Invoke-Checked $adb @('-s', $Serial, 'push', (Join-Path $dexDir 'classes.dex'), "$remoteDir/harness.dex")
    foreach ($lib in Get-ChildItem $libsDir -Filter '*.so') {
        Invoke-Checked $adb @('-s', $Serial, 'push', $lib.FullName, "$remoteDir/lib/$($lib.Name)")
    }
    # ART requires dynamically loaded code to be read-only on recent Android versions.
    Invoke-Checked $adb @('-s', $Serial, 'shell', 'chmod', '444', "$remoteDir/target.apk", "$remoteDir/harness.dex")
    $modelArgs = @()
    if ($Harness -eq 'NativeLifecycleSmoke') {
        Invoke-Checked $adb @('-s', $Serial, 'push', $LlmModel, "$remoteDir/tiny.gguf")
        Invoke-Checked $adb @('-s', $Serial, 'shell',
            "run-as $ModelsPackage cat $DiffusionModelRelativePath > $remoteDir/diffusion.gguf")
        $modelArgs = @("$remoteDir/tiny.gguf", "$remoteDir/diffusion.gguf")
    }
    Invoke-Checked $adb (@('-s', $Serial, 'shell', "CLASSPATH=$remoteDir/harness.dex",
        'app_process', '/system/bin', "com.prismai.validation.$Harness",
        "$remoteDir/target.apk", "$remoteDir/lib", "$remoteDir/cache") + $modelArgs)
} finally {
    # Validate the exact generated scope before recursive device cleanup.
    if ($remoteDir -match '^/data/local/tmp/prism-jni-[0-9a-f]{32}$') {
        & $adb -s $Serial shell rm -rf $remoteDir
    }
}
