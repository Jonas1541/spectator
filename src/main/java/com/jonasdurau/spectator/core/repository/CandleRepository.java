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
     * Busca os candles mais recentes para um símbolo, ordenados do mais novo para o mais velho.
     * Útil para pegar o estado atual do mercado (ex: últimos 200 para calcular a EMA 200).
     */
    @Query("SELECT c FROM Candle c WHERE c.symbol = :symbol ORDER BY c.time DESC LIMIT :limit")
    List<Candle> findLastCandles(String symbol, int limit);

    /**
     * Busca um range específico (para Backtesting ou gráficos históricos).
     */
    List<Candle> findBySymbolAndTimeBetweenOrderByTimeAsc(String symbol, Instant start, Instant end);

    /**
     * Verifica qual foi o último candle gravado no banco para saber de onde retomar a sincronização.
     * Retorna o Top 1 ordenado por tempo decrescente.
     */
    Candle findTopBySymbolOrderByTimeDesc(String symbol);

    /**
     * UPSERT nativo otimizado para PostgreSQL/TimescaleDB.
     * Insere o candle. Se a chave composta (symbol, time) já existir, 
     * ele atualiza os valores imediatamente num único comando atômico.
     * Elimina completamente o overhead de SELECT do JPA.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO market_candles (symbol, time, open, high, low, close, volume)
        VALUES (:#{#c.symbol}, :#{#c.time}, :#{#c.open}, :#{#c.high}, :#{#c.low}, :#{#c.close}, :#{#c.volume})
        ON CONFLICT (symbol, time)
        DO UPDATE SET
            open = EXCLUDED.open,
            high = EXCLUDED.high,
            low = EXCLUDED.low,
            close = EXCLUDED.close,
            volume = EXCLUDED.volume
        """, nativeQuery = true)
    void upsert(@Param("c") Candle c);
}