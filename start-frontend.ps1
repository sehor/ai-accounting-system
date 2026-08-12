[CmdletBinding()]
param(
    [int]$StartupTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$Port = 5173
$projectRoot = $PSScriptRoot
$frontendRoot = Join-Path $projectRoot 'frontend'
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
            # Vite is still starting.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Frontend did not become ready within $TimeoutSeconds seconds."
}

Stop-ListenerOnPort $Port
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $logDirectory "frontend-$timestamp.stdout.log"
$stderr = Join-Path $logDirectory "frontend-$timestamp.stderr.log"
$pnpm = (Get-Command pnpm.cmd -ErrorAction Stop).Source

Start-Process -FilePath $pnpm -ArgumentList 'dev' -WorkingDirectory $frontendRoot -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr | Out-Null

Wait-ForHttpOk "http://127.0.0.1:$Port/" $StartupTimeoutSeconds
Write-Host "Frontend is ready at http://127.0.0.1:$Port (logs: $logDirectory)"
