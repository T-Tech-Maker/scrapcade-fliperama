$ErrorActionPreference = 'Continue'
# Android SDK: usa ANDROID_HOME ou a pasta padrao do Android Studio
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt  = "$sdk\build-tools\37.0.0"
$aj  = "$sdk\platforms\android-36.1\android.jar"
$dir = $PSScriptRoot
$ks  = "$dir\debug.keystore"
$keytool = 'keytool'   # precisa estar no PATH (JDK)
function Chk($name) { if ($LASTEXITCODE -ne 0) { Write-Host "FALHOU: $name (exit $LASTEXITCODE)"; exit 1 } }

Write-Host '== 1) keystore'
if (-not (Test-Path $ks)) {
  & $keytool -genkeypair -keystore $ks -alias key -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Debug,O=Local,C=BR'
  Chk 'keytool'
} else { Write-Host '   (keystore ja existe)' }

Write-Host '== 2) javac'
Remove-Item "$dir\classes" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$dir\classes" | Out-Null
$srcs = (Get-ChildItem "$dir\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $aj -cp $aj -d "$dir\classes" @srcs
Chk 'javac'

Write-Host '== 3) d8 -> classes.dex'
$classes = (Get-ChildItem "$dir\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --min-api 10 --lib $aj --output "$dir" @classes
Chk 'd8'

Write-Host '== 4) aapt package (manifest)'
& "$bt\aapt.exe" package -f -M "$dir\AndroidManifest.xml" -A "$dir\assets" -I $aj -F "$dir\app-unsigned.apk"
Chk 'aapt package'

Write-Host '== 5) add classes.dex'
Push-Location $dir
& "$bt\aapt.exe" add "app-unsigned.apk" "classes.dex" | Out-Null
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { Write-Host "FALHOU: aapt add"; exit 1 }

Write-Host '== 6) zipalign'
& "$bt\zipalign.exe" -f 4 "$dir\app-unsigned.apk" "$dir\launcher-aligned.apk"
Chk 'zipalign'

Write-Host '== 7) apksigner (v1 p/ Android 2.3)'
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false --min-sdk-version 10 --out "$dir\launcher.apk" "$dir\launcher-aligned.apk"
Chk 'apksigner'

Write-Host '== OK! APK gerado:'
Get-Item "$dir\launcher.apk" | Select-Object FullName, Length
