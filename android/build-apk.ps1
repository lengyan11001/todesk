param(
    [string]$AndroidSdk = "D:\",
    [string]$JavaHome = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot",
    [int]$VersionCode = 12,
    [string]$VersionName = "0.1.11",
    [string]$WebRtcVersion = "144.7559.09",
    [ValidateSet("debug", "release")]
    [string]$Channel = "release",
    [string]$Keystore = "",
    [string]$KeyAlias = "",
    [string]$StorePass = "",
    [string]$KeyPass = ""
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$App = Join-Path $Root "app"
$Build = Join-Path $Root "manual-build"
$Deps = Join-Path $Root ".deps"
$AarWork = Join-Path $Build "webrtc-aar"
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
    "$Build\libs", `
    "$Build\native-libs", `
    "$Build\out" | Out-Null

$WebRtcAar = Join-Path $Deps "webrtc-android-$WebRtcVersion.aar"
$WebRtcZip = Join-Path $Deps "webrtc-android-$WebRtcVersion.zip"
New-Item -ItemType Directory -Force -Path $Deps | Out-Null
if (!(Test-Path $WebRtcAar)) {
    Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/$WebRtcVersion/android-$WebRtcVersion.aar" `
        -OutFile $WebRtcAar `
        -TimeoutSec 180
}
Copy-Item -LiteralPath $WebRtcAar -Destination $WebRtcZip -Force
Remove-Item -LiteralPath $AarWork -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive -LiteralPath $WebRtcZip -DestinationPath $AarWork -Force
$WebRtcJar = Join-Path $AarWork "classes.jar"
if (!(Test-Path $WebRtcJar)) { throw "WebRTC classes.jar not found in $WebRtcAar" }
Copy-Item -LiteralPath $WebRtcJar -Destination "$Build\libs\webrtc-android.jar" -Force
if (Test-Path "$AarWork\jni") {
    Copy-Item -Path "$AarWork\jni\*" -Destination "$Build\native-libs" -Recurse -Force
}

Invoke-Native $Aapt2 @("compile", "--dir", "$App\src\main\res", "-o", "$Build\compiled-res")

$flatFiles = Get-ChildItem -LiteralPath "$Build\compiled-res" -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
$aaptLinkArgs = @(
    "link",
    "-I", $PlatformJar,
    "--manifest", "$App\src\main\AndroidManifest.xml",
    "--java", "$Build\gen",
    "--min-sdk-version", "26",
    "--target-sdk-version", "34",
    "--version-code", "$VersionCode",
    "--version-name", "$VersionName",
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
    "-classpath", "$PlatformJar;$Build\libs\webrtc-android.jar",
    "-d", "$Build\classes"
) + $sources
Invoke-Native $Javac $javacArgs

$classFiles = Get-ChildItem -LiteralPath "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }

$d8Args = @(
    "--min-api", "26",
    "--lib", $PlatformJar,
    "--output", "$Build\dex"
) + $classFiles + @("$Build\libs\webrtc-android.jar")
Invoke-Native $D8 $d8Args

Copy-Item -LiteralPath "$Build\unsigned.apk" -Destination "$Build\with-dex.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = [System.IO.Compression.ZipFile]::Open("$Build\with-dex.apk", "Update")
try {
    $entry = $apk.GetEntry("classes.dex")
    if ($entry) { $entry.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk, "$Build\dex\classes.dex", "classes.dex") | Out-Null
    if (Test-Path "$Build\native-libs") {
        Get-ChildItem -LiteralPath "$Build\native-libs" -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring((Resolve-Path "$Build\native-libs").Path.Length).TrimStart('\','/')
            $apkPath = ("lib/" + ($relative -replace "\\", "/"))
            $old = $apk.GetEntry($apkPath)
            if ($old) { $old.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk, $_.FullName, $apkPath) | Out-Null
        }
    }
} finally {
    $apk.Dispose()
}

Invoke-Native $ZipAlign @("-f", "-p", "4", "$Build\with-dex.apk", "$Build\out\bhzn-todesk-$Channel-unsigned-aligned.apk")

if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $KeystoreDir "bhzn-todesk-$Channel.keystore"
}
if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
    $KeyAlias = if ($Channel -eq "release") { "bhzn-todesk-release" } else { "androiddebugkey" }
}
if ([string]::IsNullOrWhiteSpace($StorePass)) {
    $StorePass = if ($Channel -eq "release") { $env:BHZN_ANDROID_STORE_PASS } else { "android" }
}
if ([string]::IsNullOrWhiteSpace($KeyPass)) {
    $KeyPass = if ($Channel -eq "release") { $env:BHZN_ANDROID_KEY_PASS } else { "android" }
}
if ([string]::IsNullOrWhiteSpace($StorePass) -or [string]::IsNullOrWhiteSpace($KeyPass)) {
    throw "Set BHZN_ANDROID_STORE_PASS and BHZN_ANDROID_KEY_PASS for release signing."
}

if (!(Test-Path $Keystore)) {
    Invoke-Native $Keytool @(
        "-genkeypair",
        "-keystore", $Keystore,
        "-storepass", $StorePass,
        "-keypass", $KeyPass,
        "-alias", $KeyAlias,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-validity", "36500",
        "-dname", "CN=BHZN ToDesk,O=BHZN,C=CN"
    )
}

$SignedApk = "$Build\out\bhzn-todesk-$Channel-v$VersionName-$VersionCode.apk"
Invoke-Native $ApkSigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-key-alias", $KeyAlias,
    "--ks-pass", "pass:$StorePass",
    "--key-pass", "pass:$KeyPass",
    "--out", $SignedApk,
    "$Build\out\bhzn-todesk-$Channel-unsigned-aligned.apk"
)

Invoke-Native $ApkSigner @("verify", "--verbose", $SignedApk)
Write-Output $SignedApk
