param(
    [string]$AndroidSdk = "D:\",
    [string]$JavaHome = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$App = Join-Path $Root "app"
$Build = Join-Path $Root "manual-build"
$KeystoreDir = Join-Path $Root "keystore"
$BuildTools = Join-Path $AndroidSdk "build-tools\34.0.0"
$PlatformJar = Join-Path $AndroidSdk "platforms\android-34\android.jar"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$Keytool = Join-Path $JavaHome "bin\keytool.exe"

if (!(Test-Path $Aapt2)) { throw "aapt2 not found: $Aapt2" }
if (!(Test-Path $PlatformJar)) { throw "android.jar not found: $PlatformJar" }
if (!(Test-Path $Javac)) { throw "javac not found: $Javac" }

function Invoke-Native {
    param(
        [Parameter(Mandatory=$true)][string]$File,
        [Parameter(Mandatory=$true)][string[]]$NativeArgs
    )
    & $File @NativeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "$File failed with exit code $LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Force -Path $KeystoreDir | Out-Null
Remove-Item -LiteralPath $Build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path `
    "$Build\compiled-res", `
    "$Build\gen", `
    "$Build\classes", `
    "$Build\dex", `
    "$Build\out" | Out-Null

Invoke-Native $Aapt2 @("compile", "--dir", "$App\src\main\res", "-o", "$Build\compiled-res")

$flatFiles = Get-ChildItem -LiteralPath "$Build\compiled-res" -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
$aaptLinkArgs = @(
    "link",
    "-I", $PlatformJar,
    "--manifest", "$App\src\main\AndroidManifest.xml",
    "--java", "$Build\gen",
    "--min-sdk-version", "26",
    "--target-sdk-version", "34",
    "--version-code", "2",
    "--version-name", "0.1.1",
    "-o", "$Build\unsigned.apk"
) + $flatFiles
Invoke-Native $Aapt2 $aaptLinkArgs

$sources = @()
$sources += Get-ChildItem -LiteralPath "$App\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -LiteralPath "$Build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }

$javacArgs = @(
    "-encoding", "UTF-8",
    "-source", "8",
    "-target", "8",
    "-bootclasspath", $PlatformJar,
    "-classpath", $PlatformJar,
    "-d", "$Build\classes"
) + $sources
Invoke-Native $Javac $javacArgs

$classFiles = Get-ChildItem -LiteralPath "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }

$d8Args = @(
    "--min-api", "26",
    "--lib", $PlatformJar,
    "--output", "$Build\dex"
) + $classFiles
Invoke-Native $D8 $d8Args

Copy-Item -LiteralPath "$Build\unsigned.apk" -Destination "$Build\with-dex.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = [System.IO.Compression.ZipFile]::Open("$Build\with-dex.apk", "Update")
try {
    $entry = $apk.GetEntry("classes.dex")
    if ($entry) { $entry.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk, "$Build\dex\classes.dex", "classes.dex") | Out-Null
} finally {
    $apk.Dispose()
}

Invoke-Native $ZipAlign @("-f", "-p", "4", "$Build\with-dex.apk", "$Build\out\bhzn-todesk-unsigned-aligned.apk")

$Keystore = Join-Path $KeystoreDir "bhzn-todesk-debug.keystore"
if (!(Test-Path $Keystore)) {
    Invoke-Native $Keytool @(
        "-genkeypair",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=BHZN ToDesk Debug,O=BHZN,C=CN"
    )
}

Invoke-Native $ApkSigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", "$Build\out\bhzn-todesk-debug.apk",
    "$Build\out\bhzn-todesk-unsigned-aligned.apk"
)

Invoke-Native $ApkSigner @("verify", "$Build\out\bhzn-todesk-debug.apk")
Write-Output "$Build\out\bhzn-todesk-debug.apk"
