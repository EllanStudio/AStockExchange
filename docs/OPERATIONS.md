# 运维与故障处置

## 上线前清单

- 修改数据库 root 密码、业务密码和服务 API key，三者不得复用；
- 仅在内网暴露 8787，Paper 到服务的链路应位于可信网络或 TLS 反向代理后；
- 保持 `ASTOCK_ENFORCE_SESSIONS=true`；
- 将 `mock` 更换为获得展示/分发授权的数据源；
- 审核并扩展股票白名单和带生效日期的规则，确认节假日日历；
- 配置 Vault 及经济插件，验证存入、提取、崩溃恢复和人工对账；
- 用真实生产 Paper build 重新锁定并测试 `paper-api` 版本；
- 禁止现实付费游戏币直接进入系统，完成运营与合规评估；
- 明确做市资金池规模、单股/全局敞口与停买覆盖率。

## 备份

MariaDB 是权威状态；至少每日全量备份并配合 binlog 做时间点恢复。需要共同保护：

- `astock_fill` 与 `astock_ledger_entry`（审计真相）；
- 所有账户、持仓、lot、订单和转账表；
- `astock_security_rule`、`astock_market_calendar`；
- 每个 Paper 子服的 `plugins/AStockPaper/transfer-journal.json`。

恢复数据库后先保持 Paper 关闭，执行账户对账和未完成 Saga 检查，再开放交易。不要只恢复余额/持仓投影而丢弃成交与账本。

## 监控

- `/actuator/health`：服务与数据库健康；
- `/api/v1/admin/status`：行情更新时间、开放订单、待处理转账和市场阶段；
- `/api/v1/admin/source`：当前源、连续错误和熔断时间；
- `/api/v1/admin/treasury`：做市资金、负债和覆盖率；
- MariaDB 连接池、事务延时、磁盘、binlog 与死锁日志；
- Paper WebSocket 连接状态和转账恢复告警。

## 故障矩阵

| 故障 | 系统行为 | 操作 |
|---|---|---|
| 行情超时/过期 | 不发布可执行行情，不成交 | 检查 source；不要手工改旧行情时间戳 |
| 行情主源连续错误 | 熔断 30 秒；有配置时切备用 | 确认备用源也是获授权真实源 |
| MariaDB 不可用 | API 返回 503，订单不接收 | 恢复数据库并检查事务/账本 |
| AStockService 不可用 | Paper 行情断线、REST 失败，只读 | 恢复共享服务；不要在子服启动第二交易核心 |
| WebSocket 断开 | Paper 保留最后快照并显示断线 | REST/服务恢复后自动指数退避重连 |
| Paper 崩溃 | 核心订单和持仓不受影响 | 重启后读取转账 journal |
| Vault Saga 边界不明 | journal 保留 `SERVICE_BEGUN` 并告警 | 查经济插件日志后 reconcile，禁止猜测重放 |
| 覆盖率不足 | 停止玩家从 NPC 新买入 | 注资、降低敞口或进入只读，不承诺无限回购 |

## 对账

```text
/stock admin reconcile <玩家>
/stock admin audit <订单ID>
/stock admin treasury
```

`reconcile` 比较账户/持仓投影与不可修改账本的净额。若不平衡：

1. 冻结相关账户或全服交易；
2. 备份数据库和 Paper journal；
3. 用 `audit` 定位订单、成交与所有账本行；
4. 核实待处理 `astock_economy_transfer` 与经济插件日志；
5. 通过新的、可审计的修正交易处理，禁止直接覆盖历史 ledger/fill。

## 行情源切换

所有源实现 `MarketDataProvider` 并输出 `ProviderQuote`，之后统一归一化为 `CanonicalQuote`。接入正式数据商时不应修改订单和结算代码。新适配器必须验证：

- 时间戳、时区、价格/成交量单位；
- 买卖盘字段和停牌/闭市语义；
- 涨跌停与风险状态；
- 批量请求、限速、single-flight 和错误码；
- 主备源差异阈值与陈旧阈值；
- 游戏内展示、缓存、再分发和审计留存授权。

先在影子环境运行，只显示不成交；完成源间差异报告后再切换执行源。
