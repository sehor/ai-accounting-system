# 科目余额投影运行手册

## 配置

- `accounting.balance.read-mode=legacy|auto|projection`，默认 `legacy`。
- `accounting.balance.worker-enabled=false`，确认迁移完成后才启用。
- 仅当投影状态为 `READY` 且已处理到最后一个入队事件时读取投影，否则使用 live fallback；
  已完全追平的投影不会因为长时间没有新事件而失效。

## 首次上线

1. 先部署 Flyway 与代码，保持 legacy 和 worker 关闭。
2. 在维护窗口暂停凭证、期初余额和期间状态写入。
3. 以 OWNER 身份对每个账套提交全账套重建请求，轮询 job，逐期间检查 `differenceCount=0`。
4. 开启 worker，确认失败状态和积压为零后切换 `read-mode=auto`。
5. 冒烟检查试算平衡、资产负债表、利润表和 Finance Query，并观察一个完整关账周期。

## 日常排障

报表响应头 `X-Balance-Source=live-fallback` 表示投影未初始化、超过最大延迟、失败或重建中。先查看 job 状态和应用指标 `accounting.balance.projection.failures`；修复后可提交覆盖期间的重建。

## 逐期快照排障

- `accounting.balance.propagation.duration` 持续升高：检查最早 pending 期间以及该账套后续期间数量；历史变更会有意传播到最后期间。
- `accounting.balance.projection.failures` 增长：读取 `balance_projection_state.last_error_code`、`last_error_message` 和 `attempts`，确认失败账套；同一账套始终顺序处理，不应手工跳过水位。
- `accounting.balance.rebuild.duration` 持续升高：确认是否存在重复重建请求。指定起期的重建会自动延伸到最后期间。
- 回退期间先验证事实报表正确，再恢复 worker；不要把 FAILED 状态直接改成 READY。修复根因后提交重建或等待幂等重试。
- 对账至少检查三项：叶级发生额等于已过账事实、每行“期末=期初+借方发生-贷方发生”、父科目等于直接子科目汇总。

关账返回 `BALANCE_PROJECTION_NOT_READY` 时不要等待接口；检查 worker 是否启用及失败重试。返回 `BALANCE_RECONCILIATION_FAILED` 时保留事实源，先重建并核对后再关账。

## 回滚

把读取模式改回 `legacy` 并重新发布即可。不要删除投影表、事件或水位；它们用于继续核对和安全重建。
