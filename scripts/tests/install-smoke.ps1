$ErrorActionPreference = "Stop"
$RootDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$TestRoot = Join-Path ([IO.Path]::GetTempPath()) ("goldenera-installer-test-" + [Guid]::NewGuid().ToString("N"))
$InstallDir = Join-Path $TestRoot "node"

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
}
function Assert-Contains([string]$Path, [string]$Text) {
    Assert-True ((Get-Content -Raw $Path).Contains($Text)) "$Path does not contain: $Text"
}
function Assert-NotContains([string]$Path, [string]$Text) {
    Assert-True (-not (Get-Content -Raw $Path).Contains($Text)) "$Path unexpectedly contains: $Text"
}

try {
    $env:GOLDENERA_P2P_HOST = "203.0.113.20"
    $env:GOLDENERA_MINING_ENABLE = "true"
    $env:GOLDENERA_BENEFICIARY_ADDRESS = "0x2222222222222222222222222222222222222222"
    $env:GOLDENERA_EXPLORER_ENABLE = "true"
    $env:GOLDENERA_ADMIN_PASSWORD = "admin-secret"
    $env:GOLDENERA_POSTGRES_PASSWORD = "postgres-secret"
    $env:GOLDENERA_HMAC_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    $env:GOLDENERA_AES_SECRET = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

    & (Join-Path $RootDir "scripts\install.ps1") -InstallDir $InstallDir -LocalImage -NonInteractive -SkipDockerCheck
    Assert-True (Test-Path (Join-Path $InstallDir ".env")) ".env is missing"
    Assert-True (Test-Path (Join-Path $InstallDir "compose.yaml")) "compose.yaml is missing"
    Assert-True (Test-Path (Join-Path $InstallDir "goldenera.ps1")) "controller is missing"
    Assert-Contains (Join-Path $InstallDir ".env") "GOLDENERA_IMAGE=goldenera-node:sandbox-local"
    Assert-Contains (Join-Path $InstallDir ".env") "POSTGRESQL_PASSWORD=postgres-secret"
    Assert-Contains (Join-Path $InstallDir "compose.yaml") "image: postgres:18.1-alpine"
    Assert-NotContains (Join-Path $InstallDir "compose.yaml") "container_name:"

    $env:GOLDENERA_P2P_HOST = "203.0.113.21"
    $env:GOLDENERA_MINING_ENABLE = "false"
    $env:GOLDENERA_EXPLORER_ENABLE = "false"
    Remove-Item Env:GOLDENERA_POSTGRES_PASSWORD
    Remove-Item Env:GOLDENERA_HMAC_SECRET
    Remove-Item Env:GOLDENERA_AES_SECRET
    & (Join-Path $RootDir "scripts\install.ps1") -Action Reconfigure -InstallDir $InstallDir -LocalImage -NonInteractive -SkipDockerCheck
    Assert-Contains (Join-Path $InstallDir ".env") "POSTGRESQL_PASSWORD=postgres-secret"
    Assert-Contains (Join-Path $InstallDir ".env") "P2P_HOST=203.0.113.21"
    Assert-True (-not (Get-Content -Raw (Join-Path $InstallDir "compose.yaml")).Contains("image: postgres:18.1-alpine")) "disabled database is still in compose.yaml"

    Write-Host "GoldenEra PowerShell installer smoke tests passed."
} finally {
    if (Test-Path $TestRoot) { Remove-Item -Recurse -Force $TestRoot }
}
