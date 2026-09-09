param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$headers = @{ 'X-User-Name' = 'admin' }

function Api([string]$Method, [string]$Path, $Body = $null, [string]$Key = '') {
    $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers.Clone(); TimeoutSec = 30 }
    if ($Key) { $args.Headers['Idempotency-Key'] = $Key }
    if ($null -ne $Body) { $args.Body = ConvertTo-Json -InputObject $Body -Depth 20; $args.ContentType = 'application/json; charset=utf-8' }
    Invoke-RestMethod @args
}
function Check($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Output "PASS: $Message"
}

$ledger = Api POST '/v1/ledgers' @{
    name = "voucher-numbering-smoke-$([guid]::NewGuid())"; accountingStandardCode = 'SME'
    accountingStandardVersion = 'v1'; baseCurrency = 'CNY'; startDate = '2026-01-01'; approvalEnabled = $false
}
try {
    $root = "/v1/ledgers/$($ledger.id)"
    $periods = Api GET "$root/periods"
    $period = $periods | Where-Object periodCode -eq '2026-01'
    Check ($null -ne $period) 'test period available'
    $accounts = Api GET "$root/accounts"
    $expense = $accounts | Where-Object { $_.category -eq 'PERIOD_EXPENSE' -and $_.isLeaf } | Select-Object -First 1
    $equity = $accounts | Where-Object code -eq '3001'
    Check ($null -ne $expense -and $null -ne $equity) 'test accounts available'
    $body = @{
        periodId = $period.id; voucherDate = '2026-01-15'; voucherType = 'GENERAL'; voucherNumber = 'DOC-legacy'
        summary = 'numbering regression'; lines = @(
            @{ accountId = $expense.id; side = 'DEBIT'; currency = 'CNY'; originalAmount = '10'; exchangeRate = '1' }
            @{ accountId = $equity.id; side = 'CREDIT'; currency = 'CNY'; originalAmount = '10'; exchangeRate = '1' }
        )
    }
    $first = Api POST "$root/vouchers" $body 'numbering-first'
    Check ($first.voucherType -eq '记' -and $first.voucherNumber -eq '1') 'legacy supplied identifiers become 记-1'
    $retry = Api POST "$root/vouchers" $body 'numbering-first'
    Check ($retry.id -eq $first.id -and $retry.voucherNumber -eq '1') 'idempotent retry preserves voucher and number'
    $body.voucherType = '记'; $body.voucherNumber = $null
    $second = Api POST "$root/vouchers" $body
    Check ($second.voucherNumber -eq '2') 'manual and automatic requests share sequence'
    $stepPath = "$root/period-closings/$($period.id)/steps/EXPENSE_TRANSFER`:generate"
    $step = Api POST $stepPath
    Check ($null -ne $step.voucherId) 'expense transfer creates a voucher'
    $closing = Api GET "$root/vouchers/$($step.voucherId)"
    Check ($closing.voucherType -eq '记' -and $closing.voucherNumber -eq '3' -and $closing.sourceType -eq 'PERIOD_CLOSING') 'generated expense transfer uses 记-3 and retains business ownership'
    $again = Api POST $stepPath
    Check ($again.voucherId -eq $closing.id) 'repeating generation does not duplicate voucher'
    $third = Api POST "$root/vouchers" $body
    $replaced = Api POST $stepPath
    $updated = Api GET "$root/vouchers/$($replaced.voucherId)"
    Check ($updated.id -eq $closing.id -and $updated.voucherNumber -eq '3' -and $updated.voucherType -eq '记') 'regeneration after balance changes keeps the assigned number'
    $body.expectedVersion = $second.version; $body.voucherType = 'GENERAL'; $body.voucherNumber = 'legacy-update'
    $edited = Api PUT "$root/vouchers/$($second.id)" $body
    Check ($edited.voucherType -eq '记' -and $edited.voucherNumber -match '^[0-9]+$') 'updates cannot restore a business identifier as the voucher number'
    $restored = Api POST "$root/vouchers/$($second.id)/revisions/1`:restore"
    Check ($restored.voucherType -eq '记' -and $restored.voucherNumber -eq '2') 'revision restoration retains the standard voucher format'
} finally {
    Api DELETE "/v1/admin/ledgers/$($ledger.id)"
    Write-Output 'Temporary regression ledger deleted.'
}
