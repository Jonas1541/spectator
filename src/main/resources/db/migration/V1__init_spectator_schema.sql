-- 1. Tabela de Market Data (Candles)
-- Usamos DOUBLE PRECISION para performance em cálculos de indicadores.
CREATE TABLE IF NOT EXISTS market_candles (
    symbol      VARCHAR(20) NOT NULL,
    time        TIMESTAMPTZ NOT NULL,
    timeframe   VARCHAR(10) NOT NULL DEFAULT '1h',
    open        DOUBLE PRECISION NOT NULL,
    high        DOUBLE PRECISION NOT NULL,
    low         DOUBLE PRECISION NOT NULL,
    close       DOUBLE PRECISION NOT NULL,
    volume      DOUBLE PRECISION NOT NULL,
    quote_asset_volume DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    taker_buy_base_asset_volume DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    
    -- Chave primária composta
    CONSTRAINT pk_market_candles PRIMARY KEY (symbol, timeframe, time)
);

-- 2. Converte para Hypertable (Particionamento automático por tempo)
SELECT create_hypertable('market_candles', 'time', if_not_exists => TRUE, migrate_data => TRUE);

-- 3. Habilita Compressão
ALTER TABLE market_candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol',
    timescaledb.compress_orderby = 'time DESC'
);

-- 4. Política de Compressão Automática (após 7 dias)
SELECT add_compression_policy('market_candles', INTERVAL '7 days');

-- 5. Tabelas de Execução
CREATE TABLE positions (
    id UUID PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    strategy_name VARCHAR(100),
    side VARCHAR(10) NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    stop_loss DOUBLE PRECISION,
    take_profit DOUBLE PRECISION,
    initial_stop_loss DOUBLE PRECISION,
    trailing_multiplier DOUBLE PRECISION,
    breakeven_multiplier DOUBLE PRECISION,
    status VARCHAR(20) NOT NULL,
    realized_pnl DOUBLE PRECISION,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE trades (
    id UUID PRIMARY KEY,
    position_id UUID NOT NULL REFERENCES positions(id),
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_positions_symbol_status ON positions(symbol, status);
CREATE INDEX idx_trades_position_id ON trades(position_id);
