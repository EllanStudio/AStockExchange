INSERT INTO astock_system_account (account_code, cash_balance) VALUES
    ('MARKET_MAKER', 1000000000000),
    ('FEES', 0);

INSERT INTO astock_security (security_id, symbol, name, exchange, board, enabled) VALUES
    (1, 'SH.600000', '浦发银行', 'SH', 'MAIN', TRUE),
    (2, 'SH.600030', '中信证券', 'SH', 'MAIN', TRUE),
    (3, 'SH.600036', '招商银行', 'SH', 'MAIN', TRUE),
    (4, 'SH.600276', '恒瑞医药', 'SH', 'MAIN', TRUE),
    (5, 'SH.600519', '贵州茅台', 'SH', 'MAIN', TRUE),
    (6, 'SH.600887', '伊利股份', 'SH', 'MAIN', TRUE),
    (7, 'SH.601318', '中国平安', 'SH', 'MAIN', TRUE),
    (8, 'SH.601398', '工商银行', 'SH', 'MAIN', TRUE),
    (9, 'SH.601857', '中国石油', 'SH', 'MAIN', TRUE),
    (10, 'SH.601899', '紫金矿业', 'SH', 'MAIN', TRUE),
    (11, 'SH.601988', '中国银行', 'SH', 'MAIN', TRUE),
    (12, 'SH.603259', '药明康德', 'SH', 'MAIN', TRUE),
    (13, 'SZ.000001', '平安银行', 'SZ', 'MAIN', TRUE),
    (14, 'SZ.000333', '美的集团', 'SZ', 'MAIN', TRUE),
    (15, 'SZ.000651', '格力电器', 'SZ', 'MAIN', TRUE),
    (16, 'SZ.000858', '五粮液', 'SZ', 'MAIN', TRUE),
    (17, 'SZ.002415', '海康威视', 'SZ', 'MAIN', TRUE),
    (18, 'SZ.002594', '比亚迪', 'SZ', 'MAIN', TRUE),
    (19, 'SZ.300059', '东方财富', 'SZ', 'CHINEXT', TRUE),
    (20, 'SZ.300750', '宁德时代', 'SZ', 'CHINEXT', TRUE);

INSERT INTO astock_security_rule (
    security_id, effective_from, lot_size, tick_size, price_limit_bps, t_plus_days, enabled
)
SELECT security_id, DATE '2020-01-01', 100, 100,
       CASE WHEN board = 'CHINEXT' THEN 2000 ELSE 1000 END,
       1, TRUE
FROM astock_security;

INSERT INTO astock_system_inventory (security_id, quantity)
SELECT security_id, 10000000 FROM astock_security;
