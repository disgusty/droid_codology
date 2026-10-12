# Takes a screenshot of the connected device/emulator into the given PNG path.
param([string]$out = "shot.png")
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell screencap -p /data/local/tmp/shot.png | Out-Null
& $adb pull /data/local/tmp/shot.png $out | Out-Null
