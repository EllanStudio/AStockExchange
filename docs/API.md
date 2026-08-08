# AStockService API

服务默认监听 `127.0.0.1:8787`。除 `/actuator/health` 外，业务 API 与其他 Actuator 请求必须携带：

```http
X-AStock-Key: <shared-secret>
Content-Type: application/json
```

价格参数和响应均为 `0.0001` 原生币种固定点单位：沪深为 CNY、香港为 HKD、美国为 USD；资金为 `0.01 游戏币`。规范证券代码为 `SH.600519`、`SZ.000001`、`HK.00700`、`US.AAPL` 或 `US.BRK.B`。路径和 Paper 命令还接受下划线、纯六位沪深代码、1～5 位港股代码以及无前缀美股 ticker。

同一个 `limitPrice` 数值会按目标证券的原生币种解释，例如 `HK.00700` 的 `1500000` 表示 `HK$150.0000`，`US.AAPL` 的 `2500000` 表示 `$250.0000`。服务随后使用运营配置的游戏经济汇率换算 Broker Wallet 冻结额；它不调用实时外汇市场。

## 行情

```text
GET /api/v1/market/quotes
GET /api/v1/market/quotes/{symbol}?refresh=true
GET /api/v1/market/status
WS  /ws/quotes
```

WebSocket 同样使用 `X-AStock-Key`。连接后先收到 `SNAPSHOT`，随后收到 `QUOTE`；断线缓存只可展示，不代表仍可成交。

`GET /api/v1/admin/status` 的 `markets` 字段分别返回 `CN/HK/US` 的本地市场阶段和是否允许成交。只有 `CONTINUOUS` 阶段可撮合；美股盘前行情不参与首版成交。

## 账户

```text
GET /api/v1/accounts/{playerUuid}
GET /api/v1/accounts/{playerUuid}/positions
GET /api/v1/accounts/{playerUuid}/orders?limit=50
```

## 订单

```http
POST /api/v1/orders

{
  "clientRequestId": "survival-1:order:...",
  "playerUuid": "00000000-0000-0000-0000-000000000000",
  "symbol": "SH.600519",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 100,
  "limitPrice": 15000000
}
```

`clientRequestId` 全局唯一；重复提交返回原订单。市价单的 `limitPrice` 必须为 `null`，服务会生成防极端滑点的内部 `priceCap`。

数量、tick、涨跌停和可卖日来自该证券当前生效的 `astock_security_rule`。`price_limit_bps = 0` 表示该证券没有统一的日涨跌停限制，并不表示风控关闭。行情必须同时新鲜、处于可交易状态且具有真实买一卖一，订单才可能在下一行情序列成交。

```text
DELETE /api/v1/orders/{orderId}?playerUuid={uuid}
GET    /api/v1/orders/{orderId}
GET    /api/v1/orders/{orderId}/fills
```

## Broker Wallet Saga

开始转账：

```http
POST /api/v1/transfers

{
  "clientRequestId": "survival-1:...",
  "playerUuid": "00000000-0000-0000-0000-000000000000",
  "direction": "DEPOSIT",
  "amount": 100000
}
```

Paper 完成并持久化经济侧变更后：

```text
POST /api/v1/transfers/{transferId}/confirm-economy
```

经济侧失败时：

```http
POST /api/v1/transfers/{transferId}/compensate
{"reason":"Vault rejected operation"}
```

查询：

```text
GET /api/v1/transfers/{transferId}
GET /api/v1/transfers/pending?playerUuid={uuid}
```

## 管理

```text
GET  /api/v1/admin/status
GET  /api/v1/admin/source
POST /api/v1/admin/securities/{symbol}/freeze
POST /api/v1/admin/securities/{symbol}/unfreeze
GET  /api/v1/admin/reconcile/{playerUuid}
GET  /api/v1/admin/audit/{orderId}
GET  /api/v1/admin/treasury
```

冻结请求可带 `{"reason":"..."}`，冻结时会撤销该股票所有开放订单并释放资产。

## 错误

```json
{"code":"QUOTE_STALE","message":"quote is stale"}
```

- `400`：请求格式或交易单位错误；
- `401`：API key 错误；
- `409`：资金、T+1、风控、行情等业务拒绝；
- `503`：数据库或行情源不可用；
- `500`：未预期内部错误，服务端记录完整堆栈。
