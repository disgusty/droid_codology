# Builds the debug APK, installs it on the connected device and starts it (extra args go to am start).
param([string[]]$extra = @())
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = (Resolve-Path "C:\Program Files\Microsoft\jdk-21*").Path
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
Push-Location $root
& .\gradlew.bat :app:assembleDebug --console=plain -q 2>&1 | Where-Object { $_ -notmatch 'warning: \[options\]|^3 warnings|deprecated|Recompile with|unchecked' }
$ok = $LASTEXITCODE
Pop-Location
if ($ok -ne 0) { Write-Output "BUILD FAILED"; exit 1 }
& $adb install -r "$root\app\build\outputs\apk\debug\app-debug.apk" | Select-Object -Last 1
& $adb shell am force-stop com.disgusty.droid_codology
& $adb logcat -c
& $adb shell am start -n com.disgusty.droid_codology/.MainActivity @extra | Out-Null
