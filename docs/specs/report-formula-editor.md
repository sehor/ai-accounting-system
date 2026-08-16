# 报表公式重整：实施索引

本索引只保存全局决策。实施时只读取本文件和当前阶段文件，完成验收并写下交接结果后，再读取下一阶段。

## 目标

统一 SME/CAS 资产负债表和利润表的公式来源，提供结构化编辑、草稿试算、发布、版本和回滚。

## 固定决策

- SME：固定法定行；可改行名称和公式，行号、分组、顺序、列口径、勾稽规则锁定。
- CAS：保留动态逐科目明细；可改类别、标准项、具体科目和借贷方向规则。
- 科目引用支持 `STANDARD_ACCOUNT_KEY` 和账套 `ACCOUNT_ID`；父科目包含全部末级后代。
- 正式报表只读账套当前发布快照；标准包只负责初始化、迁移和恢复标准草稿。
- OWNER、EDITOR 可编辑发布；其他角色只读。
- 每张报表最多一个草稿；保存后必须试算，勾稽告警确认后可发布。
- 发布后所有历史期间按当前发布版本重算。
- 回滚生成新版本，不覆盖历史。
- 科目余额表、自定义报表、审批流、期间绑定不在范围内。
- 公式是受限 AST；不增加文本表达式或动态代码执行能力。

## 阶段

1. [领域契约与标准包](report-formula-editor/phase-1-domain-schema.md)
2. [存储、迁移与备份](report-formula-editor/phase-2-storage-migration.md)
3. [校验器与计算器](report-formula-editor/phase-3-validator-evaluator.md)
4. [正式报表切换](report-formula-editor/phase-4-report-cutover.md)
5. [草稿、发布与版本 API](report-formula-editor/phase-5-lifecycle-api.md)
6. [前端公式编辑器](report-formula-editor/phase-6-frontend.md)
7. [回归、OpenAPI 与交付](report-formula-editor/phase-7-regression.md)

## 执行协议

每阶段必须：

1. 先确认上一阶段验收已通过。
2. 只修改当前阶段列出的职责范围。
3. 使用定向测试；前端全量 test/lint/typecheck/build 需用户另行批准。
4. 结束时记录：修改文件、验证命令、结果、遗留问题。
5. 验收失败时留在当前阶段修复，不提前实施后续阶段。

全部阶段完成后运行 `graphify update .`。
