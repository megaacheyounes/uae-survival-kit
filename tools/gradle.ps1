param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks)
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$env:JAVA_HOME=(Get-ChildItem .build/jdk-17* -Directory | Select-Object -First 1).FullName
$env:GRADLE_USER_HOME=Join-Path (Get-Location) '.build/gradle-home'
if (!$env:ANDROID_HOME){$env:ANDROID_HOME='D:\android\sdk'}
& .build/gradle-8.11.1/bin/gradle.bat --console=plain @Tasks
exit $LASTEXITCODE
