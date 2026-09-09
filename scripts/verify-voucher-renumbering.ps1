param([string]$BaseUrl = 'http://127.0.0.1:8080', [switch]$LargePeriod)
$ErrorActionPreference = 'Stop'
$headers = @{ 'X-User-Name' = 'admin' }

function Api([string]$Method, [string]$Path, $Body = $null, [string]$Key = '') {
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers.Clone(); TimeoutSec = 30 }
    if ($Key) { $request.Headers['Idempotency-Key'] = $Key }
    if ($null -ne $Body) { $request.Body = ConvertTo-Json -InputObject $Body -Depth 30; $request.ContentType = 'application/json; charset=utf-8' }
    Invoke-RestMethod @request
}
function Check($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Output "PASS: $Message"
}
function ExpectConflict([scriptblock]$Action, [string]$Code) {
    try { & $Action | Out-Null; throw "Expected $Code" }
    catch {
        if ($_.ErrorDetails.Message -notmatch $Code) { throw }
        Write-Output "PASS: $Code"
    }
}
function ExecutePlan($Parameters, $Preview, [string]$Key) {
    Api POST "$root/vouchers/renumber:execute" ($Parameters + @{ previewFingerprint = $Preview.previewFingerprint }) $Key
}

$ledger = Api POST '/v1/ledgers' @{
    name = "voucher-renumbering-smoke-$([guid]::NewGuid())"; accountingStandardCode = 'SME'
    accountingStandardVersion = 'v1'; baseCurrency = 'CNY'; startDate = '2026-01-01'; approvalEnabled = $false
}
try {
    $root = "/v1/ledgers/$($ledger.id)"
    $periods = Api GET "$root/periods"
    $period = $periods | Where-Object periodCode -eq '2026-01'
    $accounts = Api GET "$root/accounts"
    $expense = $accounts | Where-Object { $_.category -eq 'PERIOD_EXPENSE' -and $_.isLeaf } | Select-Object -First 1
    $equity = $accounts | Where-Object code -eq '3001'
    $parameters = @{ periodId = $period.id; mode = 'DATE_ORDER'; startNumber = '1' }
    $empty = Api POST "$root/vouchers/renumber:preview" $parameters
    Check ($empty.totalCount -eq 0 -and $null -eq $empty.firstNumber) 'empty period is a normal no-change preview'
    $body = @{
        periodId = $period.id; voucherDate = '2026-01-20'; voucherType = '记'; voucherNumber = '1'
        summary = 'renumber regression'; lines = @(
            @{ accountId = $expense.id; side = 'DEBIT'; currency = 'CNY'; originalAmount = '10'; exchangeRate = '1' }
            @{ accountId = $equity.id; side = 'CREDIT'; currency = 'CNY'; originalAmount = '10'; exchangeRate = '1' }
        )
    }
    $first = Api POST "$root/vouchers" $body
    $body.voucherDate = '2026-01-01'; $body.voucherNumber = '3'
    $second = Api POST "$root/vouchers" $body
    $body.voucherNumber = '10'
    $third = Api POST "$root/vouchers" $body
    $preview = Api POST "$root/vouchers/renumber:preview" $parameters
    Check (($preview.changes | ForEach-Object oldNumber) -join ',' -eq '3,10,1') 'date sorting uses numeric tie-break and fills all gaps'
    $body.voucherNumber = $null
    $deleted = Api POST "$root/vouchers" $body
    ExpectConflict { ExecutePlan $parameters $preview 'stale-after-create' } 'VOUCHER_RENUMBER_PREVIEW_STALE'
    $afterCreate = Api POST "$root/vouchers/renumber:preview" $parameters
    Api DELETE "$root/vouchers/$($deleted.id)"
    ExpectConflict { ExecutePlan $parameters $afterCreate 'stale-after-delete' } 'VOUCHER_RENUMBER_PREVIEW_STALE'
    $preview = Api POST "$root/vouchers/renumber:preview" $parameters
    Check ($preview.totalCount -eq 3) 'deleted vouchers are excluded'
    $result = ExecutePlan $parameters $preview 'date-order'
    Check ($result.changedCount -eq 3 -and $result.lastNumber -eq '3') 'number swap and gap removal commit atomically'
    $retry = ExecutePlan $parameters $preview 'date-order'
    Check ($retry.operationId -eq $result.operationId) 'retry returns original operation'
    $otherParameters = $parameters.Clone(); $otherParameters.startNumber = '5'
    ExpectConflict { ExecutePlan $otherParameters $preview 'date-order' } 'IDEMPOTENCY_KEY_REUSED'
    $after = Api GET "$root/vouchers/$($first.id)"
    Check ($after.voucherNumber -eq '3' -and $after.version -eq ($first.version + 1)) 'one revision per changed voucher'
    Check (($after.lines | ConvertTo-Json -Depth 20 -Compress) -eq ($first.lines | ConvertTo-Json -Depth 20 -Compress) -and $after.status -eq $first.status) 'renumbering preserves lines, amounts and posted status'
    $revisions = Api GET "$root/vouchers/$($first.id)/revisions"
    $audit = $revisions | Where-Object action -eq 'RENUMBER'
    Check (@($audit).Count -eq 1 -and ($audit.beforeData | ConvertFrom-Json).voucherNumber -eq '1' -and ($audit.afterData | ConvertFrom-Json).voucherNumber -eq '3') 'audit stores only old and final numbers'
    ExpectConflict { Api POST "$root/vouchers/$($first.id)/revisions/1`:restore" } 'VOUCHER_NUMBER_CONFLICT'
    $afterFailedRestore = Api GET "$root/vouchers/$($first.id)"
    Check ($afterFailedRestore.voucherNumber -eq '3' -and $afterFailedRestore.version -eq $after.version) 'conflicting history restoration rolls back without changing the voucher'
    $same = Api POST "$root/vouchers/renumber:preview" $parameters
    Check ($same.changedCount -eq 0) 'repeating the same sorting produces no changes'
    $noOp = ExecutePlan $parameters $same 'no-op'
    $unchanged = Api GET "$root/vouchers/$($first.id)"
    Check ($noOp.changedCount -eq 0 -and $unchanged.version -eq $after.version) 'no-change execution does not revise vouchers'
    $stepPath = "$root/period-closings/$($period.id)/steps/EXPENSE_TRANSFER`:generate"
    $step = Api POST $stepPath
    $closing = Api GET "$root/vouchers/$($step.voucherId)"
    Check ($closing.voucherNumber -eq '4') 'next automatic voucher follows the compacted sequence'
    $parameters.mode = 'NUMBER_ORDER'; $parameters.startNumber = '20'
    $preview = Api POST "$root/vouchers/renumber:preview" $parameters
    $custom = ExecutePlan $parameters $preview 'custom-number-order'
    $renumberedClosing = Api GET "$root/vouchers/$($closing.id)"
    Check ($custom.firstNumber -eq '20' -and $custom.lastNumber -eq '23' -and $renumberedClosing.voucherNumber -eq '23' -and $renumberedClosing.sourceId -eq $closing.sourceId) 'custom start includes automatic vouchers and preserves ownership'
    $new = Api POST "$root/vouchers" $body
    Check ($new.voucherNumber -eq '24') 'next manual voucher follows custom numbering'
    $regenerated = Api POST $stepPath
    $regeneratedVoucher = Api GET "$root/vouchers/$($regenerated.voucherId)"
    Check ($regeneratedVoucher.id -eq $closing.id -and $regeneratedVoucher.voucherNumber -eq '23') 'automatic regeneration keeps its assigned new number'
    $beforeDateEdit = Api POST "$root/vouchers/renumber:preview" $parameters
    $editBody = $body.Clone(); $editBody.expectedVersion = $new.version; $editBody.voucherNumber = $new.voucherNumber; $editBody.voucherDate = '2026-01-10'
    Api PUT "$root/vouchers/$($new.id)" $editBody | Out-Null
    ExpectConflict { ExecutePlan $parameters $beforeDateEdit 'stale-date' } 'VOUCHER_RENUMBER_PREVIEW_STALE'
    $foreignParameters = $parameters.Clone(); $foreignParameters.periodId = [guid]::NewGuid().ToString()
    ExpectConflict { Api POST "$root/vouchers/renumber:preview" $foreignParameters } 'ACCOUNTING_PERIOD_NOT_FOUND'
    $invalidStart = $parameters.Clone(); $invalidStart.startNumber = '9223372036854775807'
    ExpectConflict { Api POST "$root/vouchers/renumber:preview" $invalidStart } 'VOUCHER_RENUMBER_NUMBER_RANGE'
    $parameters.startNumber = '40'
    $concurrentPreview = Api POST "$root/vouchers/renumber:preview" $parameters
    $concurrentBody = ($parameters + @{previewFingerprint = $concurrentPreview.previewFingerprint}) | ConvertTo-Json -Compress
    $concurrentUrl = "$BaseUrl$root/vouchers/renumber:execute"
    $outcomes = 1..2 | ForEach-Object -Parallel {
        $reply = Invoke-WebRequest $using:concurrentUrl -Method POST -ContentType 'application/json' -Body $using:concurrentBody `
            -Headers @{'X-User-Name'='admin'; 'Idempotency-Key'="concurrent-$_"} -SkipHttpErrorCheck -TimeoutSec 30
        [int]$reply.StatusCode
    } -ThrottleLimit 2
    Check (($outcomes | Sort-Object) -join ',' -eq '200,409') 'concurrent executions serialize; the second stale preview is rejected'
    if ($LargePeriod) {
        for ($index = 0; $index -lt 501; $index++) {
            # Existing voucher creation can deadlock with asynchronous balance rebuilds.
            # Retry only an explicitly rolled-back fixture transaction, using the same key.
            for ($attempt = 0; ; $attempt++) {
                try { Api POST "$root/vouchers" $body "large-fixture-$index" | Out-Null; break }
                catch {
                    $failure = $_.ErrorDetails.Message | ConvertFrom-Json
                    if ($attempt -ge 3 -or $failure.trace -notmatch 'PessimisticLockingFailureException') { throw }
                    Write-Output "Retrying rolled-back fixture $index after existing create-path deadlock."
                }
            }
            if ($index % 100 -eq 0) { Write-Output "Creating large-period fixtures: $index / 501" }
        }
        $parameters.mode = 'DATE_ORDER'; $parameters.startNumber = '1'
        $large = Api POST "$root/vouchers/renumber:preview" $parameters
        Check ($large.totalCount -eq 506) 'preview includes every voucher beyond the 500-item list limit'
        $largeResult = ExecutePlan $parameters $large 'large-period'
        Check ($largeResult.lastNumber -eq '506') 'all 506 vouchers are numbered continuously'
    }
} finally {
    Api DELETE "/v1/admin/ledgers/$($ledger.id)"
    Write-Output 'Temporary regression ledger deleted.'
}
