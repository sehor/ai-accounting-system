[CmdletBinding()]
param(
    [string]$TestDbUrl = 'jdbc:postgresql://localhost:5432/ai-accounting-test',
    [string]$TestDbUsername = 'postgres',
    [string]$TestDbPassword = 'pzr123'
)

$variables = @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'LOCAL_USER_HEADER_ENABLED')
$previous = @{}
foreach ($name in $variables) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:DB_URL = $TestDbUrl
    $env:DB_USERNAME = $TestDbUsername
    $env:DB_PASSWORD = $TestDbPassword
    $env:LOCAL_USER_HEADER_ENABLED = 'true'
    & (Join-Path $PSScriptRoot 'start-backend.ps1')
} finally {
    foreach ($name in $variables) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
