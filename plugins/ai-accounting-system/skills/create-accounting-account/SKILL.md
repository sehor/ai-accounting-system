---
name: create-accounting-account
description: 创建或核对会计科目。用户要求新增会计科目、按编码补建子科目、或确认科目的类别与余额方向时使用。
---

# 创建会计科目

使用 `accounting` MCP 的 `create_account`（新科目）或 `ensure_account`（幂等确认）创建科目，不直接写数据库。

## 先核对账套与编码规则

1. 用 `get_ledger_context` 读取账套、现有科目和期间配置。
2. 先 `search_accounts` 或 `list_accounts` 确认编码不存在；存在且属性一致时用 `ensure_account`。
3. 编码必须符合账套规则：一级科目 4 位数字，子科目在其父编码后追加当前级定长数字段。

## 类别

类别使用英文枚举码，中文仅用于展示：

- `CURRENT_ASSET` 流动资产
- `NON_CURRENT_ASSET` 非流动资产
- `CURRENT_LIABILITY` 流动负债
- `NON_CURRENT_LIABILITY` 非流动负债
- `EQUITY` 所有者权益
- `COST` 成本
- `OPERATING_REVENUE` 营业收入
- `OTHER_INCOME` 其他收益
- `OPERATING_COST_AND_TAX` 营业成本及税金
- `OTHER_EXPENSE` 其他损失
- `PERIOD_EXPENSE` 期间费用
- `INCOME_TAX` 所得税
- `PRIOR_YEAR_ADJUSTMENT` 以前年度损益调整

## 类别继承与余额方向

- 子科目必须继承父科目的类别；创建子科目时不要传入与父科目不同的类别。
- 余额方向 `normalBalance` 是 `DEBIT` 或 `CREDIT`，创建后不可修改。
- 子科目可以指定与父科目不同的余额方向，例如应交税费下的进项税额可为 `DEBIT`；不要自动强制继承父科目方向。

## 创建后核对

创建后用 `get_account` 或 `list_accounts` 复核返回的 `category`、`normalBalance`、`parentId`、`level` 与预期一致。若方向不对，不要尝试修改；删除该未使用科目后按正确方向重建。
