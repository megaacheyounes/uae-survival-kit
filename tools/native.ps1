$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$root=(Get-Location).Path
$localConfig="$root/.build/native-paths.json"
if(Test-Path $localConfig){
    $paths=Get-Content $localConfig -Raw | ConvertFrom-Json
    foreach($name in @('FIELDGUIDE_TOOLS','FIELDGUIDE_NDK')){
        if(!(Test-Path "Env:$name") -and $paths.$name -and (Test-Path -LiteralPath $paths.$name)){
            Set-Item -Path "Env:$name" -Value $paths.$name
        }
    }
}
$cmake="$root/.build/py/Lib/site-packages/cmake/data/bin/cmake.exe"
$ninja="$root/.build/py/Scripts/ninja.exe"
if($env:FIELDGUIDE_TOOLS){
    $cmake="$env:FIELDGUIDE_TOOLS/cmake/data/bin/cmake.exe"
    $ninja="$env:FIELDGUIDE_TOOLS/bin/ninja.exe"
}
$ndk=if($env:FIELDGUIDE_NDK){$env:FIELDGUIDE_NDK}else{"$root/.build/android-ndk-r27c"}
$deps="$root/.build/llama-b12590-deps"
$source="$root/.build/llama-b12590-src.tar.gz"
$runtime="$root/.build/llama-b12590-bin-android-arm64.tar.gz"
function VerifyArchive($archive, $hash) {
    if ((Get-FileHash -LiteralPath $archive).Hash -ne $hash) { throw "Incorrect archive: $archive" }
}
VerifyArchive $source '2617c1a848f2ad1ee45afdfc0cf47be5e732b7218c2ecad4e924f4ff5143e8bf'
VerifyArchive $runtime 'f74aef351d7074e652e83a38ae6be35c789b441193482a992acb112727538b64'
New-Item -Force -ItemType Directory $deps | Out-Null
& tar -xzf $source -C $deps llama.cpp-b12590/include llama.cpp-b12590/ggml/include
if($LASTEXITCODE){exit $LASTEXITCODE}
& tar -xzf $runtime -C $deps llama-b12590/libllama.so llama-b12590/libggml.so llama-b12590/libggml-base.so 'llama-b12590/libggml-cpu-*.so'
if($LASTEXITCODE){exit $LASTEXITCODE}
$bin="$deps/llama-b12590"
$build="$root/.build/native-b12590"
& $cmake -S app/src/main/cpp -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release "-DLLAMA_DIR=$deps/llama.cpp-b12590" "-DLLAMA_BIN=$bin"
if($LASTEXITCODE){exit $LASTEXITCODE}
& $cmake --build $build --target fieldguide --parallel 2
if($LASTEXITCODE){exit $LASTEXITCODE}
$dest="app/src/main/jniLibs/arm64-v8a"
New-Item -Force -ItemType Directory $dest | Out-Null
& "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" -o "$dest/libfieldguide.so" "$build/libfieldguide.so"
if($LASTEXITCODE){exit $LASTEXITCODE}
Copy-Item "$bin/libllama.so","$bin/libggml.so","$bin/libggml-base.so","$bin/libggml-cpu-*.so" -Destination $dest
