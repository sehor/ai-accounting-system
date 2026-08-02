from __future__ import annotations

import json
import sys
import uuid
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
JOURNAL = ROOT / "results" / "建设银行-2026.6-会计分录.json"
RESULT = ROOT / "results" / "AI-Agent银行流水测试-运行结果.json"
BASE_URL = "http://127.0.0.1:8080"


def http_json(method: str, path: str, user_id: str, body=None, extra_headers=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    headers = {"X-User-Id": user_id, "Accept": "application/json, text/event-stream"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    headers.update(extra_headers or {})
    request = Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            return response.read().decode(), dict(response.headers)
    except HTTPError as error:
        raise RuntimeError(f"{method} {path} failed: {error.code} {error.read().decode()}") from error


def rest(method: str, path: str, user_id: str, body=None):
    raw, _ = http_json(method, path, user_id, body)
    return json.loads(raw) if raw else None


def mcp_message(raw: str):
    data_lines = [line[5:] for line in raw.splitlines() if line.startswith("data:")]
    return json.loads(data_lines[-1] if data_lines else raw)


class McpClient:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.request_id = 0
        response, headers = http_json("POST", "/mcp", user_id, {
            "jsonrpc": "2.0",
            "id": self._id(),
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "codex-bank-e2e", "version": "1.0"},
            },
        })
        initialized = mcp_message(response)
        if "error" in initialized:
            raise RuntimeError(initialized["error"])
        self.session_id = headers["Mcp-Session-Id"]
        http_json("POST", "/mcp", user_id, {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {},
        }, {"Mcp-Session-Id": self.session_id})

    def _id(self):
        self.request_id += 1
        return self.request_id

    def request(self, method: str, params: dict):
        raw, _ = http_json("POST", "/mcp", self.user_id, {
            "jsonrpc": "2.0",
            "id": self._id(),
            "method": method,
            "params": params,
        }, {"Mcp-Session-Id": self.session_id})
        message = mcp_message(raw)
        if "error" in message:
            raise RuntimeError(message["error"])
        return message["result"]

    def tool(self, name: str, arguments: dict):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(result["content"][0]["text"])
        if result.get("structuredContent") is not None:
            return result["structuredContent"]
        text = result["content"][0]["text"]
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text


def account_specs(journal: list[dict]):
    names = sorted({line["account"] for item in journal for line in item["entries"]})
    groups = {
        "银行存款-": ("1002", "ASSET", "DEBIT"),
        "应收账款-": ("1122", "ASSET", "DEBIT"),
        "应付账款-": ("2202", "LIABILITY", "CREDIT"),
        "其他应付款-": ("2241", "LIABILITY", "CREDIT"),
    }
    fixed = {
        "应付职工薪酬-工资": ("2211.01", "LIABILITY", "CREDIT"),
        "财务费用-手续费": ("6603.01", "EXPENSE", "DEBIT"),
        "财务费用-利息收入": ("6603.02", "EXPENSE", "DEBIT"),
        "财务费用-利息费用": ("6603.03", "EXPENSE", "DEBIT"),
    }
    counters = {prefix: 0 for prefix in groups}
    result = {}
    for name in names:
        if name in fixed:
            result[name] = fixed[name]
            continue
        prefix = next((value for value in groups if name.startswith(value)), None)
        if prefix is None:
            raise ValueError(f"Unmapped account: {name}")
        counters[prefix] += 1
        base, category, balance = groups[prefix]
        result[name] = (f"{base}.{counters[prefix]:02d}", category, balance)
    return result


def main():
    journal = json.loads(JOURNAL.read_text(encoding="utf-8"))
    owner_id, agent_id = str(uuid.uuid4()), str(uuid.uuid4())
    rest("GET", "/v1/me", owner_id)
    ledger = rest("POST", "/v1/ledgers", owner_id, {
        "name": "AI Agent银行流水测试账套-" + datetime.now().strftime("%Y%m%d-%H%M%S"),
        "accountingStandardCode": "SME",
        "accountingStandardVersion": "v1",
        "baseCurrency": "CNY",
        "startDate": "2026-01-01",
        "approvalEnabled": False,
    })
    ledger_id = ledger["id"]
    rest("GET", "/v1/me", agent_id)
    rest("POST", f"/v1/ledgers/{ledger_id}/members", owner_id, {
        "userId": agent_id,
        "role": "AGENT",
    })

    mcp = McpClient(agent_id)
    tools = {tool["name"] for tool in mcp.request("tools/list", {})["tools"]}
    required = {"ensure_account", "list_periods", "create_voucher_draft",
                "validate_voucher", "post_voucher", "finance_query"}
    forbidden = {"approve_voucher", "close_period", "reopen_period", "unpost_voucher", "manage_members"}
    if not required <= tools or tools & forbidden:
        raise AssertionError(f"Unexpected tool boundary: {sorted(tools)}")
    if ledger_id not in {item["id"] for item in mcp.tool("list_ledgers", {})}:
        raise AssertionError("Agent cannot see the test ledger")
    periods = mcp.tool("list_periods", {"ledgerId": ledger_id})
    period_id = next(period["id"] for period in periods if period["periodCode"] == "2026-06")

    accounts = {}
    for name, (code, category, balance) in account_specs(journal).items():
        account = mcp.tool("ensure_account", {
            "ledgerId": ledger_id,
            "request": {
                "code": code,
                "name": name,
                "category": category,
                "normalBalance": balance,
            },
        })
        accounts[name] = account["id"]

    vouchers = []
    for item in journal:
        number = f"BANK-202606-{int(item['id']):03d}"
        draft = mcp.tool("create_voucher_draft", {
            "ledgerId": ledger_id,
            "idempotencyKey": number,
            "request": {
                "periodId": period_id,
                "voucherDate": item["date"],
                "voucherType": "BANK",
                "voucherNumber": number,
                "summary": item["brief"],
                "lines": [{
                    "accountId": accounts[line["account"]],
                    "side": line["direction"].upper(),
                    "currency": "CNY",
                    "originalAmount": line["amount"],
                    "exchangeRate": 1,
                    "summary": item["brief"],
                } for line in item["entries"]],
            },
        })
        validated = mcp.tool("validate_voucher", {
            "ledgerId": ledger_id, "voucherId": draft["id"],
        })
        posted = mcp.tool("post_voucher", {
            "ledgerId": ledger_id, "voucherId": validated["id"],
        })
        if posted["status"] != "POSTED":
            raise AssertionError(f"Voucher {number} was not posted")
        vouchers.append(posted["id"])

    reports = {
        report: mcp.tool("finance_query", {
            "ledgerId": ledger_id,
            "report": report,
            "periodCode": "2026-06",
        })
        for report in ("trial_balance", "general_ledger", "balance_sheet", "income_statement")
    }
    output = {
        "ledger": ledger,
        "ownerId": owner_id,
        "agentId": agent_id,
        "periodId": period_id,
        "tools": sorted(tools),
        "accountCount": len(accounts),
        "accounts": accounts,
        "voucherCount": len(vouchers),
        "voucherIds": vouchers,
        "reports": reports,
    }
    RESULT.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "ledgerId": ledger_id,
        "agentId": agent_id,
        "accountCount": len(accounts),
        "voucherCount": len(vouchers),
        "result": str(RESULT),
    }, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(str(exception), file=sys.stderr)
        raise
