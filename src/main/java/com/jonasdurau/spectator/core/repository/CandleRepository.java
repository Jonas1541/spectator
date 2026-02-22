package com.jonasdurau.spectator.core.repository;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.CandleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}