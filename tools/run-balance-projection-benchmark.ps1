param(
    [int]$VoucherLines = 1000000,
    [int]$Warmups = 5,
    [int]$Iterations = 30,
    [int]$Accounts = 100,
    [switch]$CleanupStale
)

$env:BENCHMARK_VOUCHER_LINES = $VoucherLines
$env:BENCHMARK_WARMUPS = $Warmups
$env:BENCHMARK_ITERATIONS = $Iterations
$env:BENCHMARK_ACCOUNTS = $Accounts
$env:RUN_BALANCE_PROJECTION_BENCHMARK = 'true'
$env:BENCHMARK_CLEANUP_STALE = $CleanupStale.IsPresent.ToString()

Write-Host "Running isolated balance benchmark: $VoucherLines voucher_line rows, $Iterations iterations"
& "$PSScriptRoot\..\mvnw.cmd" '-Dtest=BalanceProjectionBenchmarkTest' '-DfailIfNoTests=false' test
if ($LASTEXITCODE -ne 0) {
    throw "Balance projection benchmark failed with exit code $LASTEXITCODE"
}
