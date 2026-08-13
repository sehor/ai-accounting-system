[CmdletBinding()]
param(
    [int]$Port = 18080,
    [string]$TestDbUrl = 'jdbc:postgresql://localhost:5432/ai-accounting-test',
    [string]$TestDbUsername = 'postgres',
    [string]$TestDbPassword = 'pzr123',
    [int]$StartupTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$logDirectory = Join-Path $projectRoot 'artifacts\dev-logs'

function Stop-ListenerOnPort([int]$ListenerPort) {
    $connections = @(Get-NetTCPConnection -LocalPort $ListenerPort -State Listen -ErrorAction SilentlyContinue)
    $processIds = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($processId in $processIds) {
        Write-Host "Stopping test backend process $processId on port $ListenerPort..."
        Stop-Process -Id $processId -Force
    }
}

function Wait-ForHttpOk([string]$Uri, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            if ((Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2).StatusCode -eq 200) {
                return
            }
        } catch {
            # The Spring Boot test process is still starting.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Test backend did not become healthy within $TimeoutSeconds seconds."
}

$variables = @('SERVER_PORT', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'LOCAL_USER_HEADER_ENABLED')
$previous = @{}
foreach ($name in $variables) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:SERVER_PORT = [string]$Port
    $env:DB_URL = $TestDbUrl
    $env:DB_USERNAME = $TestDbUsername
    $env:DB_PASSWORD = $TestDbPassword
    $env:LOCAL_USER_HEADER_ENABLED = 'true'

    Stop-ListenerOnPort $Port
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stdout = Join-Path $logDirectory "backend-test-$timestamp.stdout.log"
    $stderr = Join-Path $logDirectory "backend-test-$timestamp.stderr.log"

    Start-Process -FilePath (Join-Path $projectRoot 'mvnw.cmd') -ArgumentList 'spring-boot:run' `
        -WorkingDirectory $projectRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr | Out-Null

    Wait-ForHttpOk "http://127.0.0.1:$Port/actuator/health" $StartupTimeoutSeconds
    Write-Host "Test backend is ready at http://127.0.0.1:$Port (db: $TestDbUrl, logs: $logDirectory)"
} finally {
    foreach ($name in $variables) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
