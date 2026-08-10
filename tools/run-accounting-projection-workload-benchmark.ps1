param(
    [int]$Periods = 20,
    [int]$VouchersPerPeriod = 500,
    [int]$Warmups = 5,
    [int]$Iterations = 30,
    [string]$TestDbUrl = 'jdbc:postgresql://localhost:5432/ai-accounting-test'
)

$env:TEST_DB_URL = $TestDbUrl
$env:RUN_ACCOUNTING_PROJECTION_WORKLOAD_BENCHMARK = 'true'
$env:BENCHMARK_PERIODS = $Periods
$env:BENCHMARK_VOUCHERS_PER_PERIOD = $VouchersPerPeriod
$env:BENCHMARK_WARMUPS = $Warmups
$env:BENCHMARK_ITERATIONS = $Iterations

& "$PSScriptRoot\..\mvnw.cmd" '-Dtest=AccountingProjectionWorkloadBenchmarkTest' test
if ($LASTEXITCODE -ne 0) {
    throw "Accounting projection workload benchmark failed with exit code $LASTEXITCODE"
}
