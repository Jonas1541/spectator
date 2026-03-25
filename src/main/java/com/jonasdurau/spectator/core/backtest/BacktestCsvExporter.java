package com.jonasdurau.spectator.core.backtest;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.RegimeChangeEvent;
import com.jonasdurau.spectator.core.domain.TradeSide;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gera um CSV completo com todos os dados plotados no gráfico de backtest:
 * Candles (OHLCV), Trade Events (entradas/saídas), Regime Changes (HMM) e Métricas de Sumário.
 */
public class BacktestCsvExporter {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String HEADER = "timestamp,open,high,low,close,volume,trade_action,trade_side,trade_price,trade_pnl,regime";

    /**
     * Gera o conteúdo CSV completo a partir dos mesmos dados que alimentam o gráfico do TradingView.
     */
    public static String export(List<Candle> candles, BacktestReport report) {
        if (candles == null || candles.isEmpty()) {
            return HEADER + "\n";
        }

        List<BacktestTrade> trades = report.tradeLog();
        List<RegimeChangeEvent> regimeChanges = report.regimeChanges();

        // Indexa trades por epoch second para lookup O(1)
        Map<Long, List<BacktestTrade>> tradeIndex = (trades == null) ? Collections.emptyMap() :
                trades.stream().collect(Collectors.groupingBy(t -> t.time().getEpochSecond()));

        // Prepara a lista de regime changes ordenada por tempo
        List<RegimeChangeEvent> sortedRegimes = (regimeChanges == null) ? Collections.emptyList() :
                regimeChanges.stream()
                        .sorted(Comparator.comparing(RegimeChangeEvent::time))
                        .toList();

        // Ordena candles por tempo
        List<Candle> sortedCandles = candles.stream()
                .filter(c -> c.getTime() != null)
                .sorted(Comparator.comparing(Candle::getTime))
                .toList();

        StringBuilder csv = new StringBuilder();
        csv.append(HEADER).append('\n');

        String currentRegime = "";
        int regimeIdx = 0;

        for (Candle candle : sortedCandles) {
            Instant candleTime = candle.getTime();
            long epochSecond = candleTime.getEpochSecond();

            // Avança o regime até o candle atual
            while (regimeIdx < sortedRegimes.size() &&
                   !sortedRegimes.get(regimeIdx).time().isAfter(candleTime)) {
                currentRegime = sortedRegimes.get(regimeIdx).regime().name();
                regimeIdx++;
            }

            String timestamp = ISO_FORMATTER.format(candleTime);
            String candleBase = String.format(Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                    timestamp, candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());

            List<BacktestTrade> candleTrades = tradeIndex.get(epochSecond);

            if (candleTrades != null && !candleTrades.isEmpty()) {
                // Uma linha para cada evento de trade neste candle
                for (BacktestTrade trade : candleTrades) {
                    String action = resolveAction(trade);
                    String side = trade.side().name();
                    String price = String.format(Locale.US, "%.2f", trade.price());
                    String pnl = trade.isEntry() ? "0.00" : String.format(Locale.US, "%.2f", trade.pnl());

                    csv.append(candleBase).append(',')
                       .append(action).append(',')
                       .append(side).append(',')
                       .append(price).append(',')
                       .append(pnl).append(',')
                       .append(currentRegime).append('\n');
                }
            } else {
                // Linha de candle sem evento de trade
                csv.append(candleBase).append(",,,,,").append(currentRegime).append('\n');
            }
        }

        // Seção de sumário ao final (linhas comentadas para não quebrar parsers de CSV)
        csv.append('\n');
        csv.append("# BACKTEST SUMMARY\n");
        csv.append("# strategy,").append(report.strategyName()).append('\n');
        csv.append("# symbol,").append(report.symbol()).append('\n');
        csv.append("# period,").append(ISO_FORMATTER.format(report.startTime())).append(" to ").append(ISO_FORMATTER.format(report.endTime())).append('\n');
        csv.append("# initial_capital,").append(String.format(Locale.US, "%.2f", report.initialCapital())).append('\n');
        csv.append("# final_capital,").append(String.format(Locale.US, "%.2f", report.finalCapital())).append('\n');
        csv.append("# net_profit,").append(String.format(Locale.US, "%.2f", report.netProfit())).append('\n');
        csv.append("# net_profit_pct,").append(String.format(Locale.US, "%.2f%%", (report.netProfit() / report.initialCapital()) * 100)).append('\n');
        csv.append("# total_trades,").append(report.totalTrades()).append('\n');
        csv.append("# winning_trades,").append(report.winningTrades()).append('\n');
        csv.append("# losing_trades,").append(report.losingTrades()).append('\n');
        csv.append("# win_rate,").append(String.format(Locale.US, "%.2f%%", report.winRate())).append('\n');
        csv.append("# max_drawdown,").append(String.format(Locale.US, "%.2f%%", report.maxDrawdown())).append('\n');
        csv.append("# expectancy,").append(String.format(Locale.US, "%.2f", report.expectancy())).append('\n');
        csv.append("# sharpe_ratio,").append(String.format(Locale.US, "%.2f", report.sharpeRatio())).append('\n');

        if (report.monteCarlo() != null) {
            MonteCarloReport mc = report.monteCarlo();
            csv.append("# mc_risk_of_ruin,").append(String.format(Locale.US, "%.2f%%", mc.riskOfRuin())).append('\n');
            csv.append("# mc_median_max_drawdown,").append(String.format(Locale.US, "%.2f%%", mc.medianMaxDrawdown())).append('\n');
            csv.append("# mc_simulations,").append(mc.simulationsRun()).append('\n');
        }

        return csv.toString();
    }

    private static String resolveAction(BacktestTrade trade) {
        if (trade.isEntry()) {
            return trade.side() == TradeSide.LONG ? "BUY_ENTRY" : "SELL_ENTRY";
        } else {
            // Exit: determinar TP ou SL pelo PnL
            if (trade.pnl() >= 0) {
                return trade.side() == TradeSide.LONG ? "LONG_TP" : "SHORT_TP";
            } else {
                return trade.side() == TradeSide.LONG ? "LONG_SL" : "SHORT_SL";
            }
        }
    }
}
