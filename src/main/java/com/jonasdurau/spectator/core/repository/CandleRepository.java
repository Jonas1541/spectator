package com.jonasdurau.spectator.core.repository;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.CandleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface CandleRepository extends JpaRepository<Candle, CandleId> {

    /**
     * Busca os candles mais recentes para um símbolo e tempo grafico, ordenados do mais novo para o mais velho.
     * Útil para pegar o estado atual do mercado (ex: últimos 200 para calcular a EMA 200).
     */
    @Query("SELECT c FROM Candle c WHERE c.symbol = :symbol AND c.timeframe = :timeframe ORDER BY c.time DESC LIMIT :limit")
    List<Candle> findLastCandles(String symbol, String timeframe, int limit);

    /**
     * Busca um range específico (para Backtesting ou gráficos históricos).
     */
    List<Candle> findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(String symbol, String timeframe, Instant start, Instant end);

    /**
     * Verifica qual foi o último candle gravado no banco pra saber de onde retomar a sincronização.
     * Retorna o Top 1 ordenado por tempo decrescente.
     */
    Candle findTopBySymbolAndTimeframeOrderByTimeDesc(String symbol, String timeframe);

    /**
     * UPSERT nativo otimizado para PostgreSQL/TimescaleDB.
     * Insere o candle. Se a chave composta (symbol, time) já existir, 
     * ele atualiza os valores imediatamente num único comando atômico.
     * Elimina completamente o overhead de SELECT do JPA.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO market_candles (symbol, timeframe, time, open, high, low, close, volume)
        VALUES (:#{#c.symbol}, :#{#c.timeframe}, :#{#c.time}, :#{#c.open}, :#{#c.high}, :#{#c.low}, :#{#c.close}, :#{#c.volume})
        ON CONFLICT (symbol, timeframe, time)
        DO UPDATE SET
            open = EXCLUDED.open,
            high = EXCLUDED.high,
            low = EXCLUDED.low,
            close = EXCLUDED.close,
            volume = EXCLUDED.volume
        """, nativeQuery = true)
    void upsert(@Param("c") Candle c);
}