package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.service.LiveMetricsService;
import com.jonasdurau.spectator.core.service.LiveSymbolMetrics;
import com.jonasdurau.spectator.core.service.StrategyEngineService;
import com.jonasdurau.spectator.ui.broadcaster.MarketDataBroadcaster;
import com.jonasdurau.spectator.ui.broadcaster.MarketTick;
import com.jonasdurau.spectator.ui.components.TradingViewChart;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import org.springframework.beans.factory.annotation.Value;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Route("")
@PageTitle("Spectator | Trading Terminal")
public class DashboardView extends VerticalLayout {

    // Cores TradingView padrão
    private static final String COLOR_GREEN = "#26a69a";
    private static final String COLOR_RED = "#ef5350";
    private static final String COLOR_YELLOW = "#f5cb5c";

    private final MarketDataBroadcaster broadcaster;
    private final CandleRepository candleRepository;
    private final LiveMetricsService liveMetricsService;
    private final Optional<StrategyEngineService> strategyEngineService;
    private Consumer<MarketTick> broadcasterListener;

    private final List<String> symbols;
    private final Map<String, SymbolPanel> symbolPanels = new LinkedHashMap<>();
    private final Button tradingToggle = new Button();
    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

    // Global metrics board labels
    private final Span globalWinRateLabel = new Span("-");
    private final Span globalNetProfitLabel = new Span("-");
    private final Span globalTradesLabel = new Span("-");
    private final Span globalDrawdownLabel = new Span("-");
    private final Span globalExpectancyLabel = new Span("-");
    private final Span globalSharpeLabel = new Span("-");

    public DashboardView(MarketDataBroadcaster broadcaster,
                         CandleRepository candleRepository,
                         LiveMetricsService liveMetricsService,
                         Optional<StrategyEngineService> strategyEngineService,
                         @Value("${spectator.symbols}") String symbolsConfig) {
        this.broadcaster = broadcaster;
        this.candleRepository = candleRepository;
        this.liveMetricsService = liveMetricsService;
        this.strategyEngineService = strategyEngineService;

        this.symbols = Arrays.stream(symbolsConfig.split(","))
                .map(s -> s.trim().toUpperCase())
                .toList();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        createHeader();
        createGlobalMetricsBoard();
        createSymbolPanels();
        loadInitialData();
    }

    // ──────────────────────────── HEADER ────────────────────────────

    private void createHeader() {
        H1 title = new H1("Spectator | Live Dashboard");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);

        Button backtestButton = new Button("\uD83E\uDDEA Backtest Studio");
        backtestButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        backtestButton.addClickListener(e -> UI.getCurrent().navigate(BacktestView.class));

        configureTradingToggle();

        HorizontalLayout rightControls = new HorizontalLayout(tradingToggle, backtestButton);
        rightControls.setSpacing(true);
        rightControls.setAlignItems(Alignment.CENTER);

        HorizontalLayout headerLayout = new HorizontalLayout(title, rightControls);
        headerLayout.setWidthFull();
        headerLayout.setAlignItems(Alignment.CENTER);
        headerLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);

        add(headerLayout);
    }

    // ──────────────────────── GLOBAL METRICS ────────────────────────

    private void createGlobalMetricsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        board.addClassNames(LumoUtility.Background.BASE, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        board.add(createMetricCell("Win Rate", globalWinRateLabel));
        board.add(createMetricCell("Net Profit", globalNetProfitLabel));
        board.add(createMetricCell("Total Trades", globalTradesLabel));
        board.add(createMetricCell("Max Drawdown", globalDrawdownLabel));
        board.add(createMetricCell("Expectancy", globalExpectancyLabel));
        board.add(createMetricCell("Sharpe Ratio", globalSharpeLabel));

        add(board);
    }

    private void populateGlobalMetrics() {
        LiveSymbolMetrics global = liveMetricsService.computeGlobalMetrics();
        applyMetricsToLabels(global, globalWinRateLabel, globalNetProfitLabel,
                globalTradesLabel, globalDrawdownLabel, globalExpectancyLabel, globalSharpeLabel);
    }

    // ─────────────────────── PER-SYMBOL PANELS ───────────────────────

    private void createSymbolPanels() {
        for (String symbol : symbols) {
            SymbolPanel panel = new SymbolPanel(symbol);
            symbolPanels.put(symbol, panel);
            add(panel.container);
        }
    }

    private void loadInitialData() {
        populateGlobalMetrics();

        for (Map.Entry<String, SymbolPanel> entry : symbolPanels.entrySet()) {
            String symbol = entry.getKey();
            SymbolPanel panel = entry.getValue();

            List<Candle> initialCandles = candleRepository.findLastCandles(symbol, "1h", 500);
            if (!initialCandles.isEmpty()) {
                Collections.reverse(initialCandles);
                panel.chart.setHistoricalData(initialCandles);

                Candle last = initialCandles.get(initialCandles.size() - 1);
                panel.priceLabel.setText(currencyFormatter.format(last.getClose()));
            }

            // Métricas do símbolo
            LiveSymbolMetrics metrics = liveMetricsService.computeMetrics(symbol);
            applyMetricsToLabels(metrics, panel.winRateLabel, panel.netProfitLabel,
                    panel.tradesLabel, panel.drawdownLabel, panel.expectancyLabel, panel.sharpeLabel);
        }
    }

    // ──────────────────────── BROADCASTER ────────────────────────

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        ui.getPage().executeJs("document.documentElement.setAttribute('theme', 'dark');");

        broadcasterListener = tick -> ui.access(() -> {
            String symbol = tick.candle().getSymbol().toUpperCase();
            SymbolPanel panel = symbolPanels.get(symbol);
            if (panel == null) {
                return;
            }
            updateSymbolPanel(panel, tick.candle(), tick.regime(), tick.openPositions());
            panel.chart.updateLiveTick(tick.candle());
        });

        broadcaster.register(broadcasterListener);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        broadcaster.unregister(broadcasterListener);
    }

    // ──────────────────── SYMBOL PANEL UPDATES ──────────────────

    private void updateSymbolPanel(SymbolPanel panel, Candle candle, MarketRegime regime, List<Position> positions) {
        panel.priceLabel.setText(currencyFormatter.format(candle.getClose()));
        panel.regimeBadge.setText(regime.name().replace("_", " "));

        panel.regimeBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR,
                LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING,
                LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

        switch (regime) {
            case TRENDING_UP ->
                panel.regimeBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            case TRENDING_DOWN ->
                panel.regimeBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            case VOLATILE ->
                panel.regimeBadge.addClassNames(LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING);
            default ->
                panel.regimeBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);
        }

        // Regime change marker on chart
        if (panel.currentRegime != null && panel.currentRegime != regime) {
            panel.chart.addLiveMarker(candle.getTime(), "Regime: " + regime.name(), "#3b82f6", "aboveBar", "circle");
        }
        panel.currentRegime = regime;

        // Position & Floating PnL
        if (positions != null && !positions.isEmpty()) {
            Position active = positions.get(0);
            panel.positionBadge.setText(active.getSide() + " x" + active.getQuantity());

            panel.positionBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                    LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR,
                    LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

            if (active.getSide() == TradeSide.LONG) {
                panel.positionBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            } else {
                panel.positionBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            }

            double pnl = active.calculateFloatingPnl(candle.getClose());
            panel.pnlLabel.setText((pnl >= 0 ? "+" : "") + currencyFormatter.format(pnl));

            panel.pnlLabel.removeClassNames(LumoUtility.TextColor.SUCCESS, LumoUtility.TextColor.ERROR);
            if (pnl > 0) {
                panel.pnlLabel.addClassName(LumoUtility.TextColor.SUCCESS);
            } else if (pnl < 0) {
                panel.pnlLabel.addClassName(LumoUtility.TextColor.ERROR);
            }
        } else {
            panel.positionBadge.setText("FLAT");
            panel.positionBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                    LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            panel.positionBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

            panel.pnlLabel.setText("$0.00");
            panel.pnlLabel.removeClassNames(LumoUtility.TextColor.SUCCESS, LumoUtility.TextColor.ERROR);
        }
    }

    // ──────────────────── METRICS FORMATTING ────────────────────

    private void applyMetricsToLabels(LiveSymbolMetrics metrics,
                                       Span winRate, Span netProfit, Span trades,
                                       Span drawdown, Span expectancy, Span sharpe) {
        if (metrics.totalTrades() == 0) {
            winRate.setText("-");
            netProfit.setText("-");
            trades.setText("0");
            drawdown.setText("-");
            expectancy.setText("-");
            sharpe.setText("-");
            return;
        }

        winRate.setText(String.format(Locale.US, "%.1f%%", metrics.winRate()));

        String profitStr = String.format(Locale.US, "$%.2f", metrics.netProfit());
        netProfit.setText(profitStr);
        netProfit.getStyle().set("color", metrics.netProfit() >= 0 ? COLOR_GREEN : COLOR_RED);

        trades.setText(String.valueOf(metrics.totalTrades()));

        drawdown.setText(String.format(Locale.US, "%.1f%%", metrics.maxDrawdown()));
        if (metrics.maxDrawdown() < 10.0) {
            drawdown.getStyle().set("color", COLOR_GREEN);
        } else if (metrics.maxDrawdown() <= 20.0) {
            drawdown.getStyle().set("color", COLOR_YELLOW);
        } else {
            drawdown.getStyle().set("color", COLOR_RED);
        }

        expectancy.setText(String.format(Locale.US, "$%.2f", metrics.expectancy()));
        expectancy.getStyle().set("color", metrics.expectancy() > 0 ? COLOR_GREEN : COLOR_RED);

        sharpe.setText(String.format(Locale.US, "%.2f", metrics.sharpeRatio()));
        if (metrics.sharpeRatio() >= 1.0) {
            sharpe.getStyle().set("color", COLOR_GREEN);
        } else if (metrics.sharpeRatio() > 0.0) {
            sharpe.getStyle().set("color", COLOR_YELLOW);
        } else {
            sharpe.getStyle().set("color", COLOR_RED);
        }
    }

    // ──────────────────── TRADING TOGGLE ────────────────────

    private void configureTradingToggle() {
        if (strategyEngineService.isEmpty()) {
            tradingToggle.setVisible(false);
            return;
        }

        StrategyEngineService engine = strategyEngineService.get();
        updateToggleStyle(engine.isAcceptingNewTrades());

        tradingToggle.addClickListener(event -> {
            boolean newState = !engine.isAcceptingNewTrades();
            engine.setAcceptNewTrades(newState);
            updateToggleStyle(newState);
        });
    }

    private void updateToggleStyle(boolean isActive) {
        tradingToggle.removeThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

        if (isActive) {
            tradingToggle.setText("✅ Trading Active");
            tradingToggle.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_PRIMARY);
        } else {
            tradingToggle.setText("⛔ Trading Paused");
            tradingToggle.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        }
    }

    // ──────────────────── HELPERS ────────────────────

    private VerticalLayout createMetricCell(String title, Span valueLabel) {
        valueLabel.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);
        VerticalLayout layout = new VerticalLayout(new Span(title), valueLabel);
        layout.setSpacing(false);
        layout.setPadding(false);
        layout.setWidth("auto");
        layout.setAlignItems(Alignment.CENTER);
        return layout;
    }

    // ──────────────────── INNER CLASS: SYMBOL PANEL ────────────────────

    /**
     * Agrupa todos os componentes visuais de um símbolo individual.
     * Encapsula chart, labels de preço/regime/posição/PnL e métricas de performance.
     */
    private class SymbolPanel {

        final VerticalLayout container;
        final TradingViewChart chart;
        final Span priceLabel;
        final Span regimeBadge;
        final Span positionBadge;
        final Span pnlLabel;

        // Métricas de performance
        final Span winRateLabel = new Span("-");
        final Span netProfitLabel = new Span("-");
        final Span tradesLabel = new Span("-");
        final Span drawdownLabel = new Span("-");
        final Span expectancyLabel = new Span("-");
        final Span sharpeLabel = new Span("-");

        MarketRegime currentRegime = null;

        SymbolPanel(String symbol) {
            container = new VerticalLayout();
            container.setWidthFull();
            container.setPadding(false);
            container.setSpacing(true);

            // ── Symbol Header ──
            H3 symbolTitle = new H3(symbol);
            symbolTitle.getStyle().set("margin-top", "var(--lumo-space-l)");
            symbolTitle.getStyle().set("margin-bottom", "0");

            // ── Elementos Visuais ──
            priceLabel = new Span("Loading...");
            priceLabel.getStyle().set("font-size", "var(--lumo-font-size-xl)"); // Levemente reduzido para caber tudo
            priceLabel.getStyle().set("font-weight", "bold");
            priceLabel.getStyle().set("color", "var(--lumo-primary-text-color)");

            regimeBadge = new Span("ANALYZING");
            estilizarBadge(regimeBadge);

            positionBadge = new Span("FLAT");
            estilizarBadge(positionBadge);

            pnlLabel = new Span("$0.00");
            pnlLabel.getStyle().set("font-size", "var(--lumo-font-size-l)");
            pnlLabel.getStyle().set("font-weight", "bold");

            // ── Single Line Info Board ──
            HorizontalLayout infoBoard = new HorizontalLayout(
                    // Bloco 1: Dados ao vivo
                    createCell("Live Price", priceLabel),
                    createCell("Regime", regimeBadge),
                    createCell("Position", positionBadge),
                    createCell("Floating PnL", pnlLabel),
                    
                    // Separador visual
                    createDivider(), 
                    
                    // Bloco 2: Métricas de performance
                    createCell("Win Rate", winRateLabel),
                    createCell("Net Profit", netProfitLabel),
                    createCell("Total Trades", tradesLabel),
                    createCell("Max DD", drawdownLabel),
                    createCell("Expectancy", expectancyLabel),
                    createCell("Sharpe", sharpeLabel)
            );
            
            infoBoard.setWidthFull();
            infoBoard.setJustifyContentMode(JustifyContentMode.BETWEEN); // Distribui os 10 itens pela tela
            infoBoard.setAlignItems(Alignment.CENTER); // Alinha pelo meio para balancear os badges com o texto
            infoBoard.setPadding(true);
            
            // Estilo do Card
            infoBoard.getStyle().set("background-color", "var(--lumo-contrast-5pct)");
            infoBoard.getStyle().set("border-radius", "var(--lumo-border-radius-l)");

            // ── Chart ──
            chart = new TradingViewChart();
            chart.setHeight("400px");

            container.add(symbolTitle, infoBoard, chart);
        }

        // Método auxiliar para criar as células
        private VerticalLayout createCell(String title, com.vaadin.flow.component.Component value) {
            Span label = new Span(title);
            label.getStyle().set("font-size", "var(--lumo-font-size-xs)"); // Fonte do título um pouco menor
            label.getStyle().set("color", "var(--lumo-secondary-text-color)");

            // Aplica negrito e tamanho padrão para os valores das métricas
            if (value instanceof Span && value != priceLabel && value != regimeBadge && value != positionBadge && value != pnlLabel) {
                value.getElement().getStyle().set("font-weight", "bold");
                value.getElement().getStyle().set("font-size", "var(--lumo-font-size-m)"); 
            }

            VerticalLayout cell = new VerticalLayout(label, value);
            cell.setSpacing(false);
            cell.setPadding(false);
            cell.setWidth("auto");
            return cell;
        }

        // Método para estilizar badges de forma compacta
        private void estilizarBadge(Span badge) {
            badge.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-s)");
            badge.getStyle().set("border-radius", "var(--lumo-border-radius-m)");
            badge.getStyle().set("font-weight", "bold");
            badge.getStyle().set("font-size", "var(--lumo-font-size-xs)");
            badge.getStyle().set("background-color", "var(--lumo-contrast-10pct)");
        }

        // Novo método: cria uma linha vertical para separar os dados
        private Span createDivider() {
            Span divider = new Span();
            divider.setWidth("1px");
            divider.setHeight("35px");
            divider.getStyle().set("background-color", "var(--lumo-contrast-20pct)");
            return divider;
        }
    }
}