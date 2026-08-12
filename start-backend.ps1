[CmdletBinding()]
param(
    [int]$StartupTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$Port = 8080
$projectRoot = $PSScriptRoot
$logDirectory = Join-Path $projectRoot 'artifacts\dev-logs'

function Stop-ListenerOnPort([int]$ListenerPort) {
    $connections = @(Get-NetTCPConnection -LocalPort $ListenerPort -State Listen -ErrorAction SilentlyContinue)
    $processIds = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($processId in $processIds) {
        Write-Host "Stopping process $processId on port $ListenerPort..."
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
            # The Spring Boot process is still starting.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Backend did not become healthy within $TimeoutSeconds seconds."
}

Stop-ListenerOnPort $Port
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $logDirectory "backend-$timestamp.stdout.log"
$stderr = Join-Path $logDirectory "backend-$timestamp.stderr.log"

Start-Process -FilePath (Join-Path $projectRoot 'mvnw.cmd') -ArgumentList 'spring-boot:run' `
    -WorkingDirectory $projectRoot -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr | Out-Null

Wait-ForHttpOk "http://127.0.0.1:$Port/actuator/health" $StartupTimeoutSeconds
Write-Host "Backend is ready at http://127.0.0.1:$Port (logs: $logDirectory)"
