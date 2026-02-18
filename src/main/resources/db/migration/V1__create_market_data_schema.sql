-- 1. Tabela de Market Data (Candles)
-- Usamos DOUBLE PRECISION para performance em cálculos de indicadores (Math.pow, etc).
-- Para valores financeiros (saldo, ledger), usaremos NUMERIC posteriormente.
CREATE TABLE IF NOT EXISTS market_candles (
    symbol      VARCHAR(20) NOT NULL,
    time        TIMESTAMPTZ NOT NULL,
    open        DOUBLE PRECISION NOT NULL,
    high        DOUBLE PRECISION NOT NULL,
    low         DOUBLE PRECISION NOT NULL,
    close       DOUBLE PRECISION NOT NULL,
    volume      DOUBLE PRECISION NOT NULL,
    
    -- Chave primária composta (Símbolo + Tempo)
    CONSTRAINT pk_market_candles PRIMARY KEY (symbol, time)
);

-- 2. Converte para Hypertable (Particionamento automático por tempo)
-- O 'migrate_data => true' garante que funcione mesmo se você inserir dados antes.
SELECT create_hypertable('market_candles', 'time', if_not_exists => TRUE, migrate_data => TRUE);

-- 3. Habilita Compressão (CRUCIAL para performance e custo)
-- Agrupa por Símbolo e ordena por tempo dentro do bloco comprimido.
ALTER TABLE market_candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol',
    timescaledb.compress_orderby = 'time DESC'
);

-- 4. Política de Compressão Automática
-- Comprime dados automaticamente após 7 dias (dados quentes ficam descomprimidos para escrita rápida)
SELECT add_compression_policy('market_candles', INTERVAL '7 days');