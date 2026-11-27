# Start a workspace-owned test device using the existing Android SDK image.
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$root=(Get-Location).Path
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{'D:\android\sdk'}
$env:ANDROID_HOME=$sdk
$env:ANDROID_AVD_HOME="$root\.build\avd"
$avd=Join-Path $env:ANDROID_AVD_HOME 'FieldGuideTest.avd'
New-Item -ItemType Directory -Force $avd | Out-Null
@("avd.ini.encoding=UTF-8","path=$avd","target=android-36") | Set-Content "$env:ANDROID_AVD_HOME\FieldGuideTest.ini" -Encoding ascii
@('AvdId=FieldGuideTest','avd.ini.displayname=Field Guide Test','abi.type=x86_64','hw.cpu.arch=x86_64','hw.cpu.ncore=2','hw.ramSize=5120','hw.lcd.width=900','hw.lcd.height=1600','hw.lcd.density=320','hw.keyboard=yes','hw.gps=yes','hw.gpu.enabled=yes','hw.gpu.mode=swiftshader_indirect','disk.dataPartition.size=7G','vm.heapSize=320','image.sysdir.1=system-images/android-36/google_apis_playstore/x86_64/','tag.id=google_apis_playstore','tag.display=Google Play','PlayStore.enabled=true') | Set-Content "$avd\config.ini" -Encoding ascii
$binary=Join-Path $sdk 'emulator\emulator.exe'
$proc=Start-Process $binary -ArgumentList @('-avd','FieldGuideTest','-port','5580','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader_indirect','-cores','2') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\.build\emulator-out.log" -RedirectStandardError "$root\.build\emulator-err.log"
Write-Output "FieldGuideTest emulator PID $($proc.Id), serial emulator-5580."
