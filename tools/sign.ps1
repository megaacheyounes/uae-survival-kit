param([string]$OutputApk = 'delivery/UAE-Field-Guide.apk')
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$root=(Get-Location).Path
$env:JAVA_HOME=(Get-ChildItem .build/jdk-17* -Directory | Select-Object -First 1).FullName
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{'D:\android\sdk'}
$signer=Join-Path $sdk 'build-tools/35.0.0/apksigner.bat'
$private=Join-Path $root '.private'
$key=Join-Path $private 'personal.p12'
$secretFile=Join-Path $private 'password.dpapi'
New-Item -ItemType Directory -Force $private | Out-Null
if(!(Test-Path $secretFile)){
    if(Test-Path $key){throw 'Existing signing key requires its saved password.'}
    $bytes=New-Object byte[] 40
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $secretFile
}
$secure=Get-Content -LiteralPath $secretFile -Raw | ForEach-Object {ConvertTo-SecureString $_.Trim()}
$env:FIELDGUIDE_SIGN_PASS=[System.Net.NetworkCredential]::new('', $secure).Password
try{
    if(!(Test-Path $key)){
        & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -alias personal -keystore $key -storetype PKCS12 -storepass:env FIELDGUIDE_SIGN_PASS -keypass:env FIELDGUIDE_SIGN_PASS -keyalg RSA -keysize 3072 -validity 9125 -dname 'CN=UAE Field Guide Personal'
        if($LASTEXITCODE){throw 'Could not create private signing key.'}
    }
    $built='app/build/outputs/apk/release/app-release-unsigned.apk'
    $archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path $built).Path)
    try{
        if(!$archive.GetEntry('lib/arm64-v8a/libfieldguide.so')){throw 'Build the ARM64 runtime and assemble the APK again before signing.'}
    }finally{$archive.Dispose()}
    $apk=$OutputApk
    & $signer sign --ks $key --ks-key-alias personal --ks-pass env:FIELDGUIDE_SIGN_PASS --v4-signing-enabled false --out $apk $built
    if($LASTEXITCODE){throw 'Signing failed.'}
    & $signer verify --verbose --print-certs $apk
    if($LASTEXITCODE){throw 'APK verification failed.'}
}finally{Remove-Item Env:FIELDGUIDE_SIGN_PASS}
