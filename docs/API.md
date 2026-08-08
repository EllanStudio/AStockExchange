# AStockService API

服务默认监听 `127.0.0.1:8787`。除 `/actuator/health` 外，业务 API 与其他 Actuator 请求必须携带：

```http
X-AStock-Key: <shared-secret>
Content-Type: application/json
```

价格参数和响应均为 `0.0001 CNY` 固定点单位，资金为 `0.01 游戏币`。路径里的股票可用 `SH.600519`、`SH_600519` 或纯六位代码。

## 行情

```text
GET /api/v1/market/quotes
GET /api/v1/market/quotes/{symbol}?refresh=true
GET /api/v1/market/status
WS  /ws/quotes
```

WebSocket 同样使用 `X-AStock-Key`。连接后先收到 `SNAPSHOT`，随后收到 `QUOTE`；断线缓存只可展示，不代表仍可成交。

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
