# 代码库三维审查修复：分阶段实施索引

状态：Ready for staged implementation  
制定日期：2026-08-15

本计划已拆成 6 个独立阶段。**一个新对话只实施一个阶段**；完成、验证并提交交接结果后，再开启下一阶段，避免上下文拥挤。

## 实施顺序

1. [阶段 0：规格、基线与低风险清理](code-review-remediation/stage-0-spec-baseline-cleanup.md)
2. [阶段 1：审计与凭证所有权](code-review-remediation/stage-1-audit-voucher.md)
3. [阶段 2：固定资产一致性](code-review-remediation/stage-2-fixed-assets.md)
4. [阶段 3：期初、结账状态与法定报表](code-review-remediation/stage-3-ledger-closing-statutory.md)
5. [阶段 4：后端性能](code-review-remediation/stage-4-backend-performance.md)
6. [阶段 5：OpenAPI 与前端性能](code-review-remediation/stage-5-openapi-frontend.md)

阶段 1 是阶段 2 的前置；阶段 3 可在阶段 1 后与阶段 2 独立实施；阶段 4 必须先有阶段 0 的基线；阶段 5 等所有后端公开 API 稳定后实施。

## 全局已确认决策

- 忽略“Agent 可绕过人工审批”问题；不修改 Agent 权限、MCP 工具或审批/关账代码。
- 只将 PRD 第 55 行改为：“Agent 仅能通过固定 MCP 工具记账；启用审批时，可按账套配置和既定自动流程完成审批与记账，不强制要求人类审批。”
- 普通凭证继续允许在开放期间物理删除，但审计永久保留。
- `source_type/source_id` 非空的生成凭证只能由来源模块修改或撤销。
- 固定资产参数只允许当前开放期间生效，不建设期间版本表。
- 本轮允许破坏性升级 v1 API；后端、前端、OpenAPI 同版本发布。
- 金额和汇率等十进制 JSON 字段统一使用字符串。
- 本轮不做 repository port 补齐，也不拆分 `DefaultLedgerService`/`FinanceMcpTools`；另行立项。

## 每个阶段都必须遵守

- 使用混合编排；会计和数据完整性改动结束后安排只读 `reviewer`。
- 所有命令使用 `rtk`；前端使用 `pnpm/pnpx`。
- 不启动新服务；HTTP 只使用现有 `http://127.0.0.1:18080`。
- 后端先运行 `rtk .\mvnw.cmd -q -DskipTests compile`，HTTP 不足时才运行定向 JUnit。
- 已批准阶段 5 完成后运行 `rtk pnpm typecheck` 和 `rtk pnpm build`。
- 不覆盖用户已有改动；不修改旧 Flyway migration，只新增 migration。
- 完成代码修改后运行 `rtk graphify update .` 和 `git diff --check`。

## 阶段交接模板

每个阶段结束时必须记录：

1. 完成与未完成的验收项；
2. 修改文件和新增 migration；
3. 执行过的测试、HTTP 请求和性能数据；
4. 未解决风险、回滚方式及下一阶段所需信息；
5. 是否经过会计/数据完整性 reviewer 接受。

## 后续独立任务

- 为账户管理和导入补齐 application → port → JDBC 边界及架构测试。
- 拆分 `DefaultLedgerService`。
- 拆分 `FinanceMcpTools`，保持 Agent 工具、权限和行为不变。
- 为手工 balance rebuild job 设计可在批间回退到最早脏期的状态机。

