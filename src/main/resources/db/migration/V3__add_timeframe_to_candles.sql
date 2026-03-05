-- Tabela Temporária para backup, como é hypertable evitamos ALTER TABLE pesado na PK
-- 1. Cria a coluna nova
ALTER TABLE market_candles ADD COLUMN IF NOT EXISTS timeframe VARCHAR(10) NOT NULL DEFAULT '1h';

-- 2. Descarta a primary key antiga
ALTER TABLE market_candles DROP CONSTRAINT pk_market_candles;

-- 3. Recria a primary key com as 3 colunas
ALTER TABLE market_candles ADD CONSTRAINT pk_market_candles PRIMARY KEY (symbol, timeframe, time);
