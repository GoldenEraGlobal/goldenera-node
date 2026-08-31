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
    Remove-Item Env:GOLDENERA_IMAGE -ErrorAction SilentlyContinue
    Remove-Item Env:GOLDENERA_MINING_ENABLE -ErrorAction SilentlyContinue
    Remove-Item Env:GOLDENERA_NODE_MEMORY_LIMIT_MB -ErrorAction SilentlyContinue
    $DefaultInstallDir = Join-Path $TestRoot "default-node"
    $env:GOLDENERA_P2P_HOST = "203.0.113.19"
    Remove-Item Env:GOLDENERA_EXPLORER_ENABLE -ErrorAction SilentlyContinue
    & (Join-Path $RootDir "scripts\install.ps1") -InstallDir $DefaultInstallDir -LocalImage -NonInteractive -SkipDockerCheck
    Assert-Contains (Join-Path $DefaultInstallDir ".env") "EXPLORER_ENABLE=false"
    Assert-Contains (Join-Path $DefaultInstallDir ".env") "POSTGRESQL_ENABLE=false"
    Assert-Contains (Join-Path $DefaultInstallDir ".env") "LISTEN_PORT=8080"
    Assert-Contains (Join-Path $DefaultInstallDir ".env") "NODE_MEMORY_LIMIT_MB=8192"
    Assert-NotContains (Join-Path $DefaultInstallDir "compose.yaml") "image: postgres:18.1-alpine"

    $AutomaticInstallDir = Join-Path $TestRoot "automatic-node"
    $env:GOLDENERA_P2P_HOST = "203.0.113.22"
    $env:GOLDENERA_BENEFICIARY_ADDRESS = "0x3333333333333333333333333333333333333333"
    & (Join-Path $RootDir "scripts\install.ps1") -InstallDir $AutomaticInstallDir -InstallMode Automatic -SkipDockerCheck
    Assert-Contains (Join-Path $AutomaticInstallDir ".env") "GOLDENERA_IMAGE=ghcr.io/goldeneraglobal/goldenera-node:latest"
    Assert-Contains (Join-Path $AutomaticInstallDir ".env") "MINING_ENABLE=true"
    Assert-Contains (Join-Path $AutomaticInstallDir ".env") "EXPLORER_ENABLE=false"
    Assert-Contains (Join-Path $AutomaticInstallDir ".env") "NODE_MEMORY_LIMIT_MB=12288"
    Assert-NotContains (Join-Path $AutomaticInstallDir "compose.yaml") "image: postgres:18.1-alpine"

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
    Assert-Contains (Join-Path $InstallDir ".env") "NODE_MEMORY_LIMIT_MB=12288"
    Assert-Contains (Join-Path $InstallDir ".env") "ROCKSDB_WRITE_BUFFER_MB=32"
    Assert-Contains (Join-Path $InstallDir ".env") "SYNC_RANDOMX_VERIFICATION_MODE=LIGHT"
    Assert-Contains (Join-Path $InstallDir "compose.yaml") "image: postgres:18.1-alpine"
    Assert-Contains (Join-Path $InstallDir "compose.yaml") 'mem_limit: ${NODE_MEMORY_LIMIT_MB}m'
    Assert-Contains (Join-Path $InstallDir "compose.yaml") "- IPC_LOCK"
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
