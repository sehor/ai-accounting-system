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

关账返回 `BALANCE_PROJECTION_NOT_READY` 时不要等待接口；检查 worker 是否启用及失败重试。返回 `BALANCE_RECONCILIATION_FAILED` 时保留事实源，先重建并核对后再关账。

## 回滚

把读取模式改回 `legacy` 并重新发布即可。不要删除投影表、事件或水位；它们用于继续核对和安全重建。
