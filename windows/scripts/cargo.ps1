$ErrorActionPreference = 'Stop'
# Avoid advanced-function common parameters consuming Clippy's -D flag.
$CargoArguments = $args
$clientRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $clientRoot
$localCargoHome = Join-Path $repoRoot '.tools/cargo'
$localRustupHome = Join-Path $repoRoot '.tools/rustup'
$localCargo = Join-Path $localCargoHome 'bin/cargo.exe'
$previousCargoHome = $env:CARGO_HOME
$previousRustupHome = $env:RUSTUP_HOME

try {
    if (Test-Path -LiteralPath $localCargo) {
        $env:CARGO_HOME = $localCargoHome
        $env:RUSTUP_HOME = $localRustupHome
        $cargoExecutable = $localCargo
    } else {
        $cargoExecutable = (Get-Command cargo -ErrorAction Stop).Source
    }

    Push-Location $clientRoot
    try {
        & $cargoExecutable @CargoArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Cargo exited with code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:CARGO_HOME = $previousCargoHome
    $env:RUSTUP_HOME = $previousRustupHome
}
