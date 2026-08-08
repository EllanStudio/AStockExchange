# Security policy

- 不要提交 `.env`、Paper `config.yml` 的真实 API key、数据库密码或经济插件凭据。
- AStockService 业务 API 仅使用共享服务密钥，必须限制在可信内网；公网部署应增加 TLS、密钥轮换和网络访问控制。
- 不要手工删除或修改 `astock_fill`、`astock_ledger_entry` 和未完成的 Paper 转账 journal。
- 报告资金复制、越权、账本不平、旧行情套利或凭据泄露时，应立即停止新成交、保留日志和数据库快照，再进行调查。
