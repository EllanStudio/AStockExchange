ALTER TABLE astock_security ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE astock_security MODIFY COLUMN symbol VARCHAR(32) NOT NULL;

CREATE TABLE astock_exchange_calendar (
    market_code VARCHAR(8) NOT NULL,
    trade_date DATE NOT NULL,
    is_trading_day BOOLEAN NOT NULL,
    note VARCHAR(128),
    PRIMARY KEY (market_code, trade_date)
);

INSERT INTO astock_exchange_calendar (market_code, trade_date, is_trading_day, note)
SELECT 'CN', trade_date, is_trading_day, note FROM astock_market_calendar;

ALTER TABLE astock_fill ADD COLUMN trade_date DATE;
UPDATE astock_fill SET trade_date = CAST(created_at AS DATE) WHERE trade_date IS NULL;

INSERT INTO astock_security (
    symbol, name, exchange, board, currency, enabled
) VALUES
    ('HK.00700', '腾讯控股', 'HK', 'MAIN', 'HKD', TRUE),
    ('HK.09988', '阿里巴巴-W', 'HK', 'MAIN', 'HKD', TRUE),
    ('HK.03690', '美团-W', 'HK', 'MAIN', 'HKD', TRUE),
    ('HK.01810', '小米集团-W', 'HK', 'MAIN', 'HKD', TRUE),
    ('HK.00388', '香港交易所', 'HK', 'MAIN', 'HKD', TRUE),
    ('US.AAPL', 'Apple', 'US', 'NASDAQ', 'USD', TRUE),
    ('US.MSFT', 'Microsoft', 'US', 'NASDAQ', 'USD', TRUE),
    ('US.NVDA', 'NVIDIA', 'US', 'NASDAQ', 'USD', TRUE),
    ('US.AMZN', 'Amazon', 'US', 'NASDAQ', 'USD', TRUE),
    ('US.TSLA', 'Tesla', 'US', 'NASDAQ', 'USD', TRUE);

INSERT INTO astock_security_rule (
    security_id, effective_from, lot_size, tick_size, price_limit_bps, t_plus_days, enabled
)
SELECT security_id, DATE '2020-01-01',
       CASE WHEN symbol = 'HK.01810' THEN 200
            WHEN exchange = 'HK' THEN 100
            ELSE 1 END,
       100, 0, 0, TRUE
FROM astock_security
WHERE symbol IN (
    'HK.00700', 'HK.09988', 'HK.03690', 'HK.01810', 'HK.00388',
    'US.AAPL', 'US.MSFT', 'US.NVDA', 'US.AMZN', 'US.TSLA'
);

INSERT INTO astock_system_inventory (security_id, quantity)
SELECT security_id, 10000000 FROM astock_security
WHERE symbol IN (
    'HK.00700', 'HK.09988', 'HK.03690', 'HK.01810', 'HK.00388',
    'US.AAPL', 'US.MSFT', 'US.NVDA', 'US.AMZN', 'US.TSLA'
);
