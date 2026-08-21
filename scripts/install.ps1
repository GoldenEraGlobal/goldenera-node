[CmdletBinding()]
param(
    [ValidateSet("Install", "Reconfigure")]
    [string]$Action = "Install",
    [string]$InstallDir,
    [string]$Image,
    [switch]$LocalImage,
    [switch]$NonInteractive,
    [switch]$SkipDockerCheck
)

$ErrorActionPreference = "Stop"
$DefaultImage = "ghcr.io/goldeneraglobal/goldenera-node:latest"
$LocalImageName = "goldenera-node:sandbox-local"
$ZeroAddress = "0x0000000000000000000000000000000000000000"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Info([string]$Message) { Write-Host "[goldenera] $Message" }
function Fail([string]$Message) { throw "[goldenera] ERROR: $Message" }
function Env-OrDefault([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}
function Ask([string]$Message, [string]$Default) {
    if ($NonInteractive) { return $Default }
    $answer = Read-Host "$Message [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}
function Ask-YesNo([string]$Message, [bool]$Default) {
    if ($NonInteractive) { return $Default }
    $label = if ($Default) { "yes" } else { "no" }
    while ($true) {
        $answer = (Read-Host "$Message (yes/no) [$label]").Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
        if ($answer -in @("y", "yes")) { return $true }
        if ($answer -in @("n", "no")) { return $false }
    }
}
function New-Secret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return -join ($buffer | ForEach-Object { $_.ToString("x2") })
}
function New-Base64Secret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return [Convert]::ToBase64String($buffer)
}
function Write-Utf8NoBom([string]$Path, [string]$Content) {
    [IO.File]::WriteAllText($Path, $Content, $Utf8NoBom)
}
function Get-ExistingValue([string]$Key, [string]$Default) {
    $envFile = Join-Path $InstallDir ".env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -like "$Key=*" } | Select-Object -Last 1
        if ($line) { return $line.Substring($Key.Length + 1) }
    }
    return $Default
}
function Test-DockerReady {
    try { docker info *> $null; return $LASTEXITCODE -eq 0 } catch { return $false }
}
function Ensure-Docker {
    if ($SkipDockerCheck) { return }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            Fail "Docker is not installed and winget is unavailable. Install Docker Desktop and run the script again."
        }
        Write-Info "Installing Docker Desktop through winget. Its use is subject to Docker's license terms."
        winget install --id Docker.DockerDesktop --exact --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { Fail "Docker Desktop installation failed." }
        $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $env:Path = "$machinePath;$userPath"
    }
    if (-not (Test-DockerReady)) {
        $desktop = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
        if (Test-Path $desktop) { Start-Process $desktop }
        Write-Info "Waiting for Docker Desktop..."
        foreach ($attempt in 1..60) {
            Start-Sleep -Seconds 2
            if (Test-DockerReady) { break }
        }
    }
    if (-not (Test-DockerReady)) {
        Fail "Docker Desktop is not ready. If installation requires WSL2 setup or a restart, complete it and run the script again."
    }
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Fail "The Docker Compose plugin is missing." }
}

Ensure-Docker

$localAppData = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { $HOME } else { $env:LOCALAPPDATA }
$defaultDir = Join-Path $localAppData "GoldenEra\Node"
if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    $InstallDir = Env-OrDefault "GOLDENERA_INSTALL_DIR" ""
}
if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    if ($NonInteractive) {
        $InstallDir = $defaultDir
    } else {
        $mode = Ask "Data location (automatic/manual)" "automatic"
        $InstallDir = if ($mode.ToLowerInvariant() -in @("m", "manual")) {
            Ask "Absolute path" $defaultDir
        } else { $defaultDir }
    }
}
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
$envFile = Join-Path $InstallDir ".env"
if ((Test-Path $envFile) -and $Action -ne "Reconfigure") {
    Fail "An installation already exists in $InstallDir. Use .\goldenera.ps1 update or -Action Reconfigure."
}

if ([string]::IsNullOrWhiteSpace($Image)) {
    $Image = Env-OrDefault "GOLDENERA_IMAGE" (Get-ExistingValue "GOLDENERA_IMAGE" $DefaultImage)
}
if ($LocalImage -or ((Env-OrDefault "GOLDENERA_LOCAL_IMAGE" "false") -eq "true")) { $Image = $LocalImageName }
$Image = Ask "Docker image" $Image
$pullPolicy = if ($Image -eq $LocalImageName) { "never" } else { "always" }

$network = (Ask "Network (MAINNET/TESTNET)" (Env-OrDefault "GOLDENERA_NETWORK" (Get-ExistingValue "NETWORK" "MAINNET"))).ToUpperInvariant()
if ($network -notin @("MAINNET", "TESTNET")) { Fail "Network must be MAINNET or TESTNET." }
$p2pHost = Env-OrDefault "GOLDENERA_P2P_HOST" (Get-ExistingValue "P2P_HOST" "")
if ([string]::IsNullOrWhiteSpace($p2pHost) -and -not $NonInteractive) {
    try { $p2pHost = (Invoke-RestMethod -Uri "https://api.ipify.org" -TimeoutSec 5).Trim() } catch { }
}
$p2pHost = Ask "Public IPv4 address for P2P" $p2pHost
if ([string]::IsNullOrWhiteSpace($p2pHost)) { Fail "P2P host is required." }
$parsedIp = $null
if (-not [Net.IPAddress]::TryParse($p2pHost, [ref]$parsedIp) -or $parsedIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
    Fail "P2P host must be a valid IPv4 address."
}
$p2pPort = [int](Ask "P2P port" (Env-OrDefault "GOLDENERA_P2P_PORT" (Get-ExistingValue "P2P_PORT" "9000")))
$apiPort = [int](Ask "API/Explorer port" (Env-OrDefault "GOLDENERA_API_PORT" (Get-ExistingValue "LISTEN_PORT" "8080")))
if ($p2pPort -lt 1 -or $p2pPort -gt 65535 -or $apiPort -lt 1 -or $apiPort -gt 65535 -or $p2pPort -eq $apiPort) {
    Fail "Ports must be different numbers from 1 to 65535."
}

$miningDefault = (Env-OrDefault "GOLDENERA_MINING_ENABLE" (Get-ExistingValue "MINING_ENABLE" "false")) -eq "true"
$miningEnabled = Ask-YesNo "Enable mining" $miningDefault
$beneficiary = Env-OrDefault "GOLDENERA_BENEFICIARY_ADDRESS" (Get-ExistingValue "BENEFICIARY_ADDRESS" $ZeroAddress)
$miningThreads = Env-OrDefault "GOLDENERA_MINING_THREADS" (Get-ExistingValue "MINING_HASHING_THREADS" "-1")
if ($miningEnabled) {
    $beneficiary = Ask "Reward address (0x...)" $beneficiary
    if ($beneficiary -notmatch '^0x[0-9a-fA-F]{40}$' -or $beneficiary -eq $ZeroAddress) { Fail "The reward address is invalid or is the zero address." }
    $miningThreads = Ask "Mining threads (-1 = automatic)" $miningThreads
    if ($miningThreads -notmatch '^-1$|^[1-9][0-9]*$') { Fail "Mining threads must be -1 or a positive integer." }
}

$explorerDefault = (Env-OrDefault "GOLDENERA_EXPLORER_ENABLE" (Get-ExistingValue "EXPLORER_ENABLE" "true")) -eq "true"
$explorerEnabled = Ask-YesNo "Enable the built-in Explorer/indexer, PostgreSQL, and webhooks" $explorerDefault
$identityMnemonic = Env-OrDefault "GOLDENERA_IDENTITY_MNEMONIC" ""
if ([string]::IsNullOrWhiteSpace($identityMnemonic) -and -not $NonInteractive) {
    $identityMode = Ask "Node identity (automatic/import mnemonic)" "automatic"
    if ($identityMode.ToLowerInvariant() -in @("i", "import", "mnemonic")) {
        $secure = Read-Host "Node identity mnemonic (input is hidden)" -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $identityMnemonic = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}
if ($identityMnemonic -and (($identityMnemonic -split '\s+').Count -lt 12)) { Fail "The node identity mnemonic has fewer than 12 words." }

$adminUser = Env-OrDefault "GOLDENERA_ADMIN_USERNAME" (Get-ExistingValue "ADMIN_USERNAME" "admin")
$adminPassword = Env-OrDefault "GOLDENERA_ADMIN_PASSWORD" (Get-ExistingValue "ADMIN_PASSWORD" (New-Secret 16))
$postgresPassword = Env-OrDefault "GOLDENERA_POSTGRES_PASSWORD" (Get-ExistingValue "POSTGRESQL_PASSWORD" (New-Secret 16))
$hmacSecret = Env-OrDefault "GOLDENERA_HMAC_SECRET" (Get-ExistingValue "SECURITY_HMAC_SECRET" (New-Base64Secret 32))
$aesSecret = Env-OrDefault "GOLDENERA_AES_SECRET" (Get-ExistingValue "SECURITY_AES_GCM_SECRET" (New-Base64Secret 32))
$boolMining = $miningEnabled.ToString().ToLowerInvariant()
$boolExplorer = $explorerEnabled.ToString().ToLowerInvariant()

New-Item -ItemType Directory -Force -Path $InstallDir, (Join-Path $InstallDir "node_data"), (Join-Path $InstallDir "node_logs"), (Join-Path $InstallDir "postgres_data") *> $null

$compose = @'
name: goldenera
services:
  node:
    image: ${GOLDENERA_IMAGE}
    pull_policy: ${GOLDENERA_PULL_POLICY}
    restart: unless-stopped
    env_file: [.env]
    environment:
      POSTGRESQL_HOST: db
      LOGGING_FILE: ${LOGGING_FILE:-goldenera.log}
    ports:
      - "${LISTEN_PORT:-8080}:${LISTEN_PORT:-8080}"
      - "${P2P_PORT:-9000}:${P2P_PORT:-9000}"
    volumes:
      - ./node_data:/app/node_data
      - ./node_logs:/app/node_logs
    cap_add:
      - IPC_LOCK
    ulimits:
      memlock:
        soft: -1
        hard: -1
'@
if ($explorerEnabled) {
    $compose += @'
    depends_on:
      db:
        condition: service_healthy

  db:
    image: postgres:18.1-alpine
    restart: unless-stopped
    env_file: [.env]
    command: postgres -c shared_buffers=512MB -c max_connections=100
    environment:
      POSTGRES_DB: ${POSTGRESQL_DB_NAME:-node_db}
      POSTGRES_USER: ${POSTGRESQL_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${POSTGRESQL_PASSWORD}
    volumes:
      - ./postgres_data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRESQL_USERNAME:-postgres}"]
      interval: 5s
      timeout: 5s
      retries: 12
'@
}
Write-Utf8NoBom (Join-Path $InstallDir "compose.yaml") $compose

$configuration = @"
GOLDENERA_IMAGE=$Image
GOLDENERA_PULL_POLICY=$pullPolicy
SPRING_PROFILES_ACTIVE=prod
LISTEN_PORT=$apiPort
NETWORK=$network
BENEFICIARY_ADDRESS=$beneficiary
P2P_HOST=$p2pHost
P2P_PORT=$p2pPort
NODE_IDENTITY_FILE=./node_data/.node_identity
BLOCKCHAIN_DB_PATH=./node_data/blockchain
PEER_REPUTATION_DB_PATH=./node_data/peer-reputation
MINING_ENABLE=$boolMining
MINING_HASHING_THREADS=$miningThreads
MINING_MEMORY_MODE=FULL
POSTGRESQL_ENABLE=$boolExplorer
EXPLORER_ENABLE=$boolExplorer
WEBHOOK_ENABLE=$boolExplorer
POSTGRESQL_PORT=5432
POSTGRESQL_DB_NAME=node_db
POSTGRESQL_USERNAME=postgres
POSTGRESQL_PASSWORD=$postgresPassword
SECURITY_HMAC_SECRET=$hmacSecret
SECURITY_AES_GCM_SECRET=$aesSecret
SECURITY_CORE_API_ENABLED=false
SECURITY_EXPLORER_API_ENABLED=$boolExplorer
ADMIN_USERNAME=$adminUser
ADMIN_PASSWORD=$adminPassword
JAVA_HEAP_MB=
ROCKSDB_BLOCK_CACHE_MB=512
ROCKSDB_WRITE_BUFFER_MB=64
ROCKSDB_MAX_WRITE_BUFFERS=4
ROCKSDB_MAX_BACKGROUND_JOBS=6
ROCKSDB_BLOCK_SIZE_KB=16
ROCKSDB_BLOOM_FILTER_BITS=10
ROCKSDB_DIRECT_READS=true
ROCKSDB_DIRECT_WRITES=true
ROCKSDB_RATE_LIMIT_MB_PER_SEC=0
ROCKSDB_BLOB_ENABLED=true
ROCKSDB_BLOB_MIN_BYTES=65536
ROCKSDB_BLOB_FILE_SIZE_MB=256
ROCKSDB_BLOB_GC_ENABLED=true
ROCKSDB_BLOB_GC_AGE_CUTOFF=0.25
CACHE_BLOCK_MB=256
CACHE_TRIE_NODE_MB=256
CACHE_TX_MB=128
CACHE_HEADER_MAX_ENTRIES=50000
CACHE_HEIGHT_MAX_ENTRIES=100000
CACHE_EXPIRE_MINUTES=60
DIRECTORY_PING_INTERVAL_IN_MS=30000
MEMPOOL_MAX_SIZE=100000
MEMPOOL_EXPIRE_TX_IN_MINUTES=60
MEMPOOL_MIN_ACCEPTABLE_FEE_IN_WEI=10
MEMPOOL_MAX_NONCE_GAP_PER_SENDER=64
LOGGING_DIR=./node_logs
LOGGING_FILE=goldenera.log
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_GLOBAL_GOLDENERA=INFO
THROTTLING_GLOBAL_CAPACITY=500
THROTTLING_GLOBAL_REFILL_TOKENS=500
THROTTLING_PUBLIC_CORE_CAPACITY=100
THROTTLING_PUBLIC_CORE_REFILL_TOKENS=20
THROTTLING_API_KEY_DEFAULT_CAPACITY=5000
THROTTLING_API_KEY_DEFAULT_REFILL_TOKENS=2000
THROTTLING_API_KEY_EXPLORER_CAPACITY=500
THROTTLING_API_KEY_EXPLORER_REFILL_TOKENS=100
THROTTLING_P2P_CAPACITY=20000
THROTTLING_P2P_REFILL_TOKENS=10000
"@
Write-Utf8NoBom $envFile $configuration

$controller = @'
param([ValidateSet("start", "stop", "restart", "update", "status", "logs", "config")][string]$Command = "status", [string]$Service = "node")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
switch ($Command) {
    "start"   { docker compose up -d --remove-orphans }
    "stop"    { docker compose stop }
    "restart" { docker compose restart node }
    "update"  {
        $policy = (Get-Content .env | Where-Object { $_ -like "GOLDENERA_PULL_POLICY=*" } | Select-Object -Last 1).Split("=", 2)[1]
        if ($policy -ne "never") { docker compose pull }
        docker compose up -d --remove-orphans
        docker compose ps
    }
    "status"  { docker compose ps }
    "logs"    { docker compose logs -f $Service }
    "config"  { docker compose config }
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
'@
Write-Utf8NoBom (Join-Path $InstallDir "goldenera.ps1") $controller

if ($identityMnemonic) {
    Write-Utf8NoBom (Join-Path $InstallDir "node_data\.node_identity") $identityMnemonic.Trim()
}

if (-not $SkipDockerCheck) {
    Push-Location $InstallDir
    try {
        docker compose config --quiet
        docker compose up -d --remove-orphans
        if ($LASTEXITCODE -ne 0) { Fail "The node failed to start." }
    } finally { Pop-Location }
}

Write-Info "GoldenEra Node is configured in $InstallDir"
Write-Info "Manage it with: & '$InstallDir\goldenera.ps1' status|logs|update|restart|stop|start"
Write-Info "API/Explorer: http://localhost:$apiPort"
Write-Info "Admin username: $adminUser"
Write-Info "Admin password: $adminPassword"
Write-Warning ".env contains sensitive values; save the password in a password manager."
