CREATE TABLE astock_security (
    security_id INT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    board VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    frozen_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (security_id),
    CONSTRAINT uk_astock_security_symbol UNIQUE (symbol)
);

CREATE TABLE astock_security_rule (
    rule_id BIGINT NOT NULL AUTO_INCREMENT,
    security_id INT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    lot_size BIGINT NOT NULL,
    tick_size BIGINT NOT NULL,
    price_limit_bps INT NOT NULL,
    t_plus_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (rule_id),
    CONSTRAINT fk_rule_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id),
    CONSTRAINT uk_rule_effective UNIQUE (security_id, effective_from)
);

CREATE TABLE astock_market_calendar (
    trade_date DATE NOT NULL,
    is_trading_day BOOLEAN NOT NULL,
    note VARCHAR(128),
    PRIMARY KEY (trade_date)
);

CREATE TABLE astock_account (
    account_id BIGINT NOT NULL AUTO_INCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    cash_available BIGINT NOT NULL DEFAULT 0,
    cash_frozen BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    CONSTRAINT uk_astock_account_player UNIQUE (player_uuid),
    CONSTRAINT ck_account_nonnegative CHECK (cash_available >= 0 AND cash_frozen >= 0)
);

CREATE TABLE astock_position (
    account_id BIGINT NOT NULL,
    security_id INT NOT NULL,
    quantity_total BIGINT NOT NULL DEFAULT 0,
    quantity_available BIGINT NOT NULL DEFAULT 0,
    quantity_frozen BIGINT NOT NULL DEFAULT 0,
    average_cost BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, security_id),
    CONSTRAINT fk_position_account FOREIGN KEY (account_id) REFERENCES astock_account (account_id),
    CONSTRAINT fk_position_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id),
    CONSTRAINT ck_position_nonnegative CHECK (
        quantity_total >= 0 AND quantity_available >= 0 AND quantity_frozen >= 0
        AND quantity_available + quantity_frozen <= quantity_total
    )
);

CREATE TABLE astock_position_lot (
    lot_id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    security_id INT NOT NULL,
    quantity BIGINT NOT NULL,
    remaining_quantity BIGINT NOT NULL,
    buy_trade_date DATE NOT NULL,
    unlock_trade_date DATE NOT NULL,
    cost_price BIGINT NOT NULL,
    settled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (lot_id),
    CONSTRAINT fk_lot_account FOREIGN KEY (account_id) REFERENCES astock_account (account_id),
    CONSTRAINT fk_lot_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id),
    CONSTRAINT ck_lot_quantity CHECK (quantity > 0 AND remaining_quantity >= 0 AND remaining_quantity <= quantity)
);
CREATE INDEX idx_lot_available ON astock_position_lot (account_id, security_id, unlock_trade_date, lot_id);

CREATE TABLE astock_order (
    order_id VARCHAR(36) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    account_id BIGINT NOT NULL,
    security_id INT NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    quantity BIGINT NOT NULL,
    remaining_quantity BIGINT NOT NULL,
    limit_price BIGINT,
    price_cap BIGINT,
    reserved_cash BIGINT NOT NULL DEFAULT 0,
    reserved_quantity BIGINT NOT NULL DEFAULT 0,
    accepted_quote_sequence BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    reject_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    CONSTRAINT uk_order_client_request UNIQUE (client_request_id),
    CONSTRAINT fk_order_account FOREIGN KEY (account_id) REFERENCES astock_account (account_id),
    CONSTRAINT fk_order_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id),
    CONSTRAINT ck_order_quantity CHECK (quantity > 0 AND remaining_quantity >= 0 AND remaining_quantity <= quantity)
);
CREATE INDEX idx_order_match ON astock_order (security_id, status, accepted_quote_sequence, created_at);
CREATE INDEX idx_order_account ON astock_order (account_id, created_at);

CREATE TABLE astock_fill (
    fill_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    security_id INT NOT NULL,
    quantity BIGINT NOT NULL,
    price BIGINT NOT NULL,
    notional BIGINT NOT NULL,
    fee BIGINT NOT NULL,
    quote_sequence BIGINT NOT NULL,
    quote_source VARCHAR(64) NOT NULL,
    quote_timestamp BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (fill_id),
    CONSTRAINT fk_fill_order FOREIGN KEY (order_id) REFERENCES astock_order (order_id),
    CONSTRAINT fk_fill_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id)
);
CREATE INDEX idx_fill_order ON astock_fill (order_id, created_at);

CREATE TABLE astock_ledger_entry (
    entry_id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(36) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    currency VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (entry_id)
);
CREATE INDEX idx_ledger_transaction ON astock_ledger_entry (transaction_id);
CREATE INDEX idx_ledger_reference ON astock_ledger_entry (reference_type, reference_id);

CREATE TABLE astock_system_account (
    account_code VARCHAR(32) NOT NULL,
    cash_balance BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_code)
);

CREATE TABLE astock_system_inventory (
    security_id INT NOT NULL,
    quantity BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (security_id),
    CONSTRAINT fk_inventory_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id)
);

CREATE TABLE astock_economy_transfer (
    transfer_id VARCHAR(36) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transfer_id),
    CONSTRAINT uk_transfer_request UNIQUE (client_request_id)
);
CREATE INDEX idx_transfer_status ON astock_economy_transfer (status, updated_at);

CREATE TABLE astock_treasury_exposure (
    snapshot_id BIGINT NOT NULL AUTO_INCREMENT,
    liability BIGINT NOT NULL,
    treasury_balance BIGINT NOT NULL,
    coverage_ratio_bps INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id)
);

CREATE TABLE astock_outbox (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (outbox_id)
);
CREATE INDEX idx_outbox_unpublished ON astock_outbox (published_at, outbox_id);

CREATE TABLE astock_corporate_action (
    action_id BIGINT NOT NULL AUTO_INCREMENT,
    security_id INT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    effective_date DATE NOT NULL,
    payload_json TEXT NOT NULL,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_action_security FOREIGN KEY (security_id) REFERENCES astock_security (security_id)
);
