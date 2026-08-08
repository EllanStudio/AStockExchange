# AStock Exchange

一个面向 Paper 26.2 多子服网络的“全球股票镜像模拟交易所”，首版统一支持沪深 A 股、港股和美股。现实行情只作为价格锚点；玩家交易的是服务器内部虚拟股份，所有资金、持仓、订单、盈亏和审计记录都在 AStockService 与 MariaDB 中结算，不连接真实证券账户。

本仓库交付的是可运行首版，不是概念脚手架：

- `astock-domain`：固定点金额、统一行情、质量闸门、有效期交易规则和交易时段模型；
- `astock-service`：Spring Boot 4.1 / Java 25 独立服务，包含 REST、WebSocket、Flyway、单逻辑写入队列、下一行情序列成交、部分成交、分市场交易时段/整手/可卖日、Broker Wallet、有限 NPC 做市、复式账本、风控与管理审计；
- `astock-paper`：Paper 26.2 薄插件，包含异步服务客户端、行情推送缓存、全局 GUI 刷新器、玩家/管理命令、反射式 Vault 桥接和本地持久化转账 Saga 日志；
- `compose.yaml`：MariaDB 11.8.8 与 AStockService 的本地部署；
- 自动化测试：覆盖固定点运算、三地代码、行情拒绝条件、沪深/香港/纽约交易时段、订单幂等、下一序列成交、A 股 T+1、美股当日可卖、买卖闭环、撤单释放、转账和账本零和校验。

## 市场范围

| 市场 | 内部代码 | 原生币种 | 连续竞价时区 | 首版规则 |
|---|---|---|---|---|
| 沪深 | `SH.600519` / `SZ.000001` | CNY | `Asia/Shanghai` | 100 股整手、主板 10%/创业板 20%、T+1 |
| 香港 | `HK.00700` | HKD | `Asia/Hong_Kong` | 按证券整手、无统一日涨跌停、游戏内 T+0 |
| 美国 | `US.AAPL` / `US.BRK.B` | USD | `America/New_York` | 1 股、无统一日涨跌停、游戏内 T+0，仅常规时段成交 |

港股真实价位表、特殊证券整手、公司行动，以及各市场节假日会变化；上线前必须用 `astock_security_rule` 与 `astock_exchange_calendar` 按运营白名单复核。数据库预置规则是可运行示例，不是交易所规则数据库。

## 关键安全语义

```mermaid
flowchart LR
    MD["可替换 MarketDataProvider"] --> N["QuoteNormalizer"]
    N --> Q["QuoteQualityGate"]
    Q --> E["Quote Epoch / 下一序列"]
    E --> O["单逻辑订单写入器"]
    O --> R["RiskEngine"]
    R --> M["有限 NPC 做市"]
    M --> S["Settlement / 分市场可卖日"]
    S --> L["不可修改复式账本"]
    L --> DB[("MariaDB")]
    Q --> WS["WebSocket 行情"]
    WS --> P["Paper 薄插件"]
    P --> V["Vault Saga"]
```

- 市价单记录接单行情序列，只能在更大的有效序列上成交；
- 缺买一/卖一、行情超过 5 秒、停牌、质量冲突或数据源失败时不成交；
- 限价买单冻结最大现金与费用，限价卖单冻结股份，撤单原子释放；
- 买入 lot 按证券规则在 T+0 或后续交易日进入可卖数量；
- 每笔资金和股份账本按 `transaction_id + currency` 汇总必须为零；
- Paper 主线程不进行 HTTP、WebSocket、数据库、JSON 批处理或撮合；
- AStockService 不可用时插件行情会标为断线，交易请求失败关闭；最后行情不会被伪装成可成交行情。

## 版本

- Java 25
- Gradle 9.6.1（Wrapper 含官方 SHA-256 校验）
- Spring Boot 4.1.0
- Paper API `26.2.build.111-stable`（固定 build，不动态拉取）
- MariaDB 11.8.8

## 快速验收

Windows：

```powershell
.\gradlew.bat clean test build
```

Linux/macOS：

```bash
./gradlew clean test build
```

产物：

- `astock-service/build/libs/astock-service.jar`
- `astock-paper/build/libs/AStockPaper-1.0.0.jar`

## 本地服务启动

1. 复制 `.env.example` 为 `.env`，为三个密码项生成不同的高强度随机值。
2. 启动服务：

```bash
docker compose up -d --build
docker compose ps
```

3. 健康检查：

```bash
curl http://127.0.0.1:8787/actuator/health
```

默认行情源是确定性的 `mock`，适合全天候功能测试。生产配置默认执行真实交易时段；如需在非交易时间验收，可临时在 `.env` 设置 `ASTOCK_ENFORCE_SESSIONS=false`，但不要把它带入正式服。

不使用 Docker 时，可先启动 MariaDB，再设置 `ASTOCK_DB_URL`、`ASTOCK_DB_USER`、`ASTOCK_DB_PASSWORD`、`ASTOCK_API_KEY`，然后运行：

```powershell
java -jar astock-service\build\libs\astock-service.jar
```

## Paper 安装

1. 安装 Paper 26.2、Vault 及一个 Vault 兼容经济插件。
2. 将 `AStockPaper-1.0.0.jar` 放入每个 Paper 子服的 `plugins/`。
3. 首次启动后编辑 `plugins/AStockPaper/config.yml`：
   - `service.base-url` 指向共享的 AStockService；
   - `service.api-key` 必须与 `ASTOCK_API_KEY` 完全一致；
   - 每个子服设置唯一 `paper.server-id`。
4. 重启 Paper。Velocity 不安装交易核心，也不处理订单。

插件在 `plugins/AStockPaper/transfer-journal.json` 持久化 Vault 跨系统 Saga。不要手工删除仍有记录的文件；若服务器恰好在经济 API 调用边界崩溃，插件会提示管理员运行对账。

## 玩家命令

```text
/stock market
/stock quote <代码>
/stock balance
/stock positions
/stock orders
/stock buy <代码> <股数> [原生币种限价]
/stock sell <代码> <股数> [原生币种限价]
/stock cancel <订单ID>
/stock deposit <游戏币>
/stock withdraw <游戏币>
/stock disclaimer
```

管理命令：

```text
/stock admin status
/stock admin source
/stock admin freeze <代码> [原因]
/stock admin unfreeze <代码>
/stock admin reconcile <玩家名或 UUID>
/stock admin audit <订单ID>
/stock admin treasury
```

## 行情模式

`mock` 是覆盖三地的默认确定性开发源。正式适配器 `longbridge` 使用 [Longbridge OpenAPI](https://open.longbridge.com/docs/getting-started)，通过以下环境变量启用：

```dotenv
ASTOCK_DATA_PRIMARY=longbridge
LONGBRIDGE_APP_KEY=...
LONGBRIDGE_APP_SECRET=...
LONGBRIDGE_ACCESS_TOKEN=...
LONGBRIDGE_REGION=hk
```

Longbridge 批量概览接口每批最多请求 500 个白名单代码；订单可能成交时再拉目标证券深度。若账号没有对应市场的实时/盘口权限，行情只能展示或会被判为陈旧，绝不会合成买一卖一。不要假定港股一定实时：部分账号只有 BMP（约 15 分钟），部分套餐或活动包含 LV1；具体以运行账号在 [Developer Center 的 OpenAPI 行情权限](https://open.longbridge.com/docs/quote/overview) 为准。A 股若未返回可用盘口同样只展示、不成交。

`eastmoney` 适配器仍可通过 `ASTOCK_DATA_PRIMARY=eastmoney` 显式开启，但只覆盖沪深开发环境。相同股票两秒内的细节请求使用 single-flight 合并；概览行情只触发盘口确认，绝不直接伪造成交。

东方财富适配器仅定位为开发、内测、小规模非商业环境，没有 SLA。本项目不授予任何行情展示或再分发权。正式公开或商业运营必须实现 `MarketDataProvider` 接入有明确游戏内展示授权的数据商，并在切换前完成法务/合规评估。不要将 `mock` 配成真实源的故障备用，否则会把合成价格引入交易。

开发数据库预置 20 只沪深、5 只港股和 5 只美股用于确定性验收。正式白名单应通过 `astock_security` 与带生效日期的 `astock_security_rule` 扩展；ST、新股、退市整理、停牌、复杂证券和无法识别风险状态的品种应保持关闭。

## 金额单位

- 行情价格：`long`，1 单位 = `0.0001` 原生币种（CNY/HKD/USD）；
- Broker Wallet：`long`，1 单位 = `0.01 游戏币`；
- 股份：`long`，单位为股；
- `ASTOCK_RATE_CNY/HKD/USD` 是运营方定义的“每原生币种单位兑换多少游戏币”，不是实时外汇行情；
- 所有乘法通过受检固定点函数完成，统一使用四舍五入，不使用 `double` 结算。

REST 详情见 [docs/API.md](docs/API.md)，部署、备份、故障处置和对账见 [docs/OPERATIONS.md](docs/OPERATIONS.md)。

## 玩家必须知悉

> 本系统为 Minecraft 服务器内的虚拟模拟交易玩法。所有交易仅使用服务器游戏币，不代表真实证券所有权，不构成证券交易或投资建议。行情可能存在延迟、错误或中断，游戏币及虚拟持仓不得兑换为现金或其他现实财产。

如果游戏币可以使用人民币购买，不应让付费取得的游戏币直接进入本玩法；上线前还需要独立运营与合规评估。不可提现只能降低风险，不能解决行情数据授权问题。
