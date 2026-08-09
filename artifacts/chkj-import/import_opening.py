from __future__ import annotations

import base64
import json
import runpy
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "pydeps"))

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
ACCOUNTS_SOURCE = Path(r"C:\Users\pzr\Downloads\chkjbackup\20260806094517科目列表.xls")
BALANCE_SOURCE = Path(r"C:\Users\pzr\Downloads\chkjbackup\20260806074335科目余额表_2026年第5期.xls")
CSV_OUTPUT = Path(__file__).parent / "opening-balances-2026-06.csv"
SUMMARY = Path(__file__).parent / "opening-import-summary.json"
LEDGER_ID = "11d7a8d8-f34b-4d03-9f7f-53980b09bc88"
USER_ID = "e164807e-e122-47a0-bf4b-77980458ef25"


def main() -> None:
    accounts = pd.read_excel(ACCOUNTS_SOURCE, dtype=str).fillna("")
    account_codes = {str(value).strip() for value in accounts["编码"]}
    parent_codes = {
        code[:4] if len(code) == 8 else code[:-3]
        for code in account_codes
        if len(code) in (8, 11, 14)
    }
    leaf_codes = account_codes - parent_codes

    balance = pd.read_excel(BALANCE_SOURCE, header=None, dtype=str).fillna("").iloc[4:]
    lines = []
    for _, row in balance.iterrows():
        code = str(row.iloc[0]).strip()
        if code not in leaf_codes:
            continue
        debit = Decimal(str(row.iloc[8]).replace(",", "") or "0")
        credit = Decimal(str(row.iloc[9]).replace(",", "") or "0")
        normalized_debit = max(debit, Decimal(0)) + max(-credit, Decimal(0))
        normalized_credit = max(credit, Decimal(0)) + max(-debit, Decimal(0))
        if normalized_debit == 0 and normalized_credit == 0:
            continue
        lines.append((code, normalized_debit, normalized_credit))

    rows = ["periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate"]
    rows.extend(f"2026-06,{code},CNY,,{debit},{credit},1" for code, debit, credit in lines)
    CSV_OUTPUT.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")

    helper = runpy.run_path(str(ROOT / "test-resources" / "agent_bank_statement_e2e.py"))
    mcp = helper["McpClient"](USER_ID)
    existing = mcp.tool("list_opening_balances", {"ledgerId": LEDGER_ID})
    if existing and all(item["confirmed"] for item in existing):
        imported = existing
        confirmation = {"confirmedCount": len(existing), "alreadyConfirmed": True}
    else:
        imported = mcp.tool("import_opening_balances", {
            "ledgerId": LEDGER_ID,
            "base64Content": base64.b64encode(CSV_OUTPUT.read_bytes()).decode(),
        })
        confirmation = mcp.tool("confirm_opening_balances", {"ledgerId": LEDGER_ID})
    verified = mcp.tool("list_opening_balances", {"ledgerId": LEDGER_ID})
    debit_total = sum(Decimal(str(item["debitBase"])) for item in verified)
    credit_total = sum(Decimal(str(item["creditBase"])) for item in verified)
    result = {
        "ledgerId": LEDGER_ID,
        "sourcePeriod": "2026-05",
        "openingPeriod": "2026-06",
        "importedLeafBalances": len(imported),
        "verifiedBalances": len(verified),
        "confirmedBalances": sum(1 for item in verified if item["confirmed"]),
        "debitTotal": str(debit_total),
        "creditTotal": str(credit_total),
        "balanced": debit_total == credit_total,
        "confirmation": confirmation,
        "csv": str(CSV_OUTPUT),
    }
    SUMMARY.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
