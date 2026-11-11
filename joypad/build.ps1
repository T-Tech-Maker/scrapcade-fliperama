$ErrorActionPreference = 'Continue'
# Zig 0.17 no PATH (ou ajuste o caminho abaixo)
$zig = if (Get-Command zig -ErrorAction SilentlyContinue) { 'zig' } else { 'C:\zig\zig.exe' }
$dir = $PSScriptRoot
# Binario ARM estatico, sem libc, nao-PIE (Android 2.3 nao roda PIE).
$a = @('cc', '-target', 'arm-linux-musleabi', '-mcpu=cortex_a8', '-marm', '-O2',
       '-ffreestanding', '-fno-builtin', '-fno-stack-protector', '-fno-pie', '-no-pie',
       '-nostdlib', '-static', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables',
       '-Wl,-e,_start', '-Wl,--build-id=none', '-s', '-o', "$dir\joyd", "$dir\joyd.c")
& $zig @a
if ($LASTEXITCODE -ne 0) { Write-Host "FALHOU (exit $LASTEXITCODE)"; exit 1 }
Get-Item "$dir\joyd" | Select-Object FullName, Length
