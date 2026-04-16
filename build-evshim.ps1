# build-evshim.ps1
# Recompiles evshim.c into libevshim.so using the Android NDK clang toolchain.
# Run from the project root after editing app/src/main/cpp/extras/evshim.c.
# After this script succeeds, run .\gradlew.bat assembleDebug to include the
# updated .so in the APK.

$ErrorActionPreference = "Stop"

$NDK_VERSION = "26.1.10909125"
$SDK_ROOT    = "$env:LOCALAPPDATA\Android\Sdk"
$NDK_ROOT    = "$SDK_ROOT\ndk\$NDK_VERSION"
$CLANG       = "$NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd"

$SRC         = "app\src\main\cpp\extras\evshim.c"
$INCLUDES    = "app\src\main\cpp\extras\sdl2_stub"
$OUT         = "app\src\main\jniLibs\arm64-v8a\libevshim.so"

if (-not (Test-Path $CLANG)) {
    Write-Error "Clang not found at: $CLANG`nCheck that NDK $NDK_VERSION is installed."
}

# Ensure the output directory exists (needed on a fresh clone).
$outDir = Split-Path -Parent $OUT
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Write-Host "Created output directory: $outDir"
}

Write-Host "Compiling evshim.c with NDK $NDK_VERSION ..."

# Use $clangArgs (not $args) — $args is a PowerShell automatic variable and
# assigning to it can interfere with parameter passing in some contexts.
$clangArgs = @(
    "-shared", "-fPIC", "-O2",
    "-I", $INCLUDES,
    "-Wl,--as-needed",
    "-ldl",
    "-o", $OUT,
    $SRC
)

& $CLANG @clangArgs

if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed (exit $LASTEXITCODE)"
}

Write-Host "OK  ->  $OUT"
Write-Host ""
Write-Host "Next: .\gradlew.bat assembleDebug"
