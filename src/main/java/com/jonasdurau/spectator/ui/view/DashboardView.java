package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.service.StrategyEngineService;
import com.jonasdurau.spectator.ui.broadcaster.MarketDataBroadcaster;
import com.jonasdurau.spectator.ui.broadcaster.MarketTick;
import com.jonasdurau.spectator.ui.components.TradingViewChart;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.jonasdurau.spectator.core.domain.Position;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import org.springframework.beans.factory.annotation.Value;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.function.Consumer;

@Route("")
@PageTitle("Spectator | Trading Terminal")
public class DashboardView extends VerticalLayout {

    private final MarketDataBroadcaster broadcaster;
    private final CandleRepository candleRepository;
    private final Optional<StrategyEngineService> strategyEngineService;
    private Consumer<MarketTick> broadcasterListener;
    private MarketRegime currentRegime = null;

    // Symbol selection
    private String selectedSymbol;
    private final ComboBox<String> symbolSelector = new ComboBox<>();
    private final Span priceLabelHeader = new Span("Live Price");

    // Componentes Visuais
    private final H2 priceLabel = new H2("Loading...");
    private final Span regimeBadge = new Span("ANALYZING");
    private final Span positionBadge = new Span("NO ACTIVE TRADES");
    private final Span pnlLabel = new Span("$0.00");
    private final TradingViewChart chart = new TradingViewChart();
    private final Button tradingToggle = new Button();

    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

    public DashboardView(MarketDataBroadcaster broadcaster, CandleRepository candleRepository,
                         Optional<StrategyEngineService> strategyEngineService,
                         @Value("${spectator.symbols}") String symbolsConfig) {
        this.broadcaster = broadcaster;
        this.candleRepository = candleRepository;
        this.strategyEngineService = strategyEngineService;

        // Parse configured symbols
        String[] symbols = symbolsConfig.split(",");
        this.selectedSymbol = symbols[0].trim().toUpperCase();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        createHeader(symbols);
        createMetricsBoard();

        add(chart);

        loadInitialData();
    }

    private void createHeader(String[] symbols) {
        H1 title = new H1("Spectator | Live Dashboard");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);

        // Symbol selector dropdown
        symbolSelector.setItems(java.util.Arrays.stream(symbols).map(s -> s.trim().toUpperCase()).toList());
        symbolSelector.setValue(selectedSymbol);
        symbolSelector.setWidth("160px");
        symbolSelector.addValueChangeListener(e -> {
            if (e.getValue() != null && !e.getValue().equals(selectedSymbol)) {
                selectedSymbol = e.getValue();
                priceLabelHeader.setText(selectedSymbol + " Live Price");
                currentRegime = null;
                loadInitialData();
            }
        });

        Button backtestButton = new Button("🧪 Backtest Studio");
        backtestButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        backtestButton.addClickListener(e -> UI.getCurrent().navigate(BacktestView.class));

        configureTradingToggle();

        HorizontalLayout rightControls = new HorizontalLayout(tradingToggle, backtestButton);
        rightControls.setSpacing(true);
        rightControls.setAlignItems(Alignment.CENTER);

        HorizontalLayout headerLayout = new HorizontalLayout(title, symbolSelector, rightControls);
        headerLayout.setWidthFull();
        headerLayout.setAlignItems(Alignment.CENTER);
        headerLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);

        add(headerLayout);
    }

    private void createMetricsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setAlignItems(Alignment.CENTER);
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        board.addClassNames(LumoUtility.Background.BASE, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        priceLabelHeader.setText(selectedSymbol + " Live Price");
        VerticalLayout priceLayout = new VerticalLayout(priceLabelHeader, priceLabel);
        priceLayout.setSpacing(false);
        priceLayout.setPadding(false);
        priceLabel.addClassNames(LumoUtility.TextColor.PRIMARY, LumoUtility.Margin.NONE);

        VerticalLayout regimeLayout = new VerticalLayout(new Span("Market Regime"), regimeBadge);
        regimeLayout.setSpacing(false);
        regimeLayout.setPadding(false);
        regimeLayout.setAlignItems(Alignment.END);
        regimeBadge.addClassNames(LumoUtility.Padding.Horizontal.MEDIUM, LumoUtility.Padding.Vertical.SMALL,
                LumoUtility.BorderRadius.LARGE, LumoUtility.FontWeight.BOLD);

        VerticalLayout positionLayout = new VerticalLayout(new Span("Position"), positionBadge);
        positionLayout.setSpacing(false);
        positionLayout.setPadding(false);
        positionLayout.setAlignItems(Alignment.END);
        positionBadge.addClassNames(LumoUtility.Padding.Horizontal.MEDIUM, LumoUtility.Padding.Vertical.SMALL,
                LumoUtility.BorderRadius.LARGE, LumoUtility.FontWeight.BOLD, LumoUtility.Background.CONTRAST_10);

        VerticalLayout pnlLayout = new VerticalLayout(new Span("Floating PnL"), pnlLabel);
        pnlLayout.setSpacing(false);
        pnlLayout.setPadding(false);
        pnlLayout.setAlignItems(Alignment.END);
        pnlLabel.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);

        board.add(priceLayout, regimeLayout, positionLayout, pnlLayout);
        add(board);
    }

    private void loadInitialData() {
        List<Candle> initialCandles = candleRepository.findLastCandles(selectedSymbol, "1h", 500);

        if (!initialCandles.isEmpty()) {
            Collections.reverse(initialCandles);

            chart.setHistoricalData(initialCandles);

            Candle last = initialCandles.get(initialCandles.size() - 1);
            updateMetrics(last, MarketRegime.SIDEWAYS, Collections.emptyList());
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();

        ui.getPage().executeJs("document.documentElement.setAttribute('theme', 'dark');");

        broadcasterListener = tick -> ui.access(() -> {
            // Only process ticks for the currently selected symbol
            if (!tick.candle().getSymbol().equalsIgnoreCase(selectedSymbol)) {
                return;
            }
            updateMetrics(tick.candle(), tick.regime(), tick.openPositions());
            chart.updateLiveTick(tick.candle());
        });

        broadcaster.register(broadcasterListener);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        broadcaster.unregister(broadcasterListener);
    }

    private void updateMetrics(Candle candle, MarketRegime regime, List<Position> positions) {
        priceLabel.setText(currencyFormatter.format(candle.getClose()));
        regimeBadge.setText(regime.name().replace("_", " "));

        regimeBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR,
                LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING,
                LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

        switch (regime) {
            case TRENDING_UP ->
                regimeBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            case TRENDING_DOWN ->
                regimeBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            case VOLATILE ->
                regimeBadge.addClassNames(LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING);
            default -> regimeBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);
        }

        // Regime change marker on chart
        if (this.currentRegime != null && this.currentRegime != regime) {
            chart.addLiveMarker(candle.getTime(), "Regime: " + regime.name(), "#3b82f6", "aboveBar", "circle");
        }
        this.currentRegime = regime;

        // Position & Floating PnL
        if (positions != null && !positions.isEmpty()) {
            Position active = positions.get(0);
            positionBadge.setText(active.getSide() + " x" + active.getQuantity());

            if (active.getSide() == com.jonasdurau.spectator.core.domain.TradeSide.LONG) {
                positionBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            } else {
                positionBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            }

            double pnl = active.calculateFloatingPnl(candle.getClose());
            pnlLabel.setText((pnl >= 0 ? "+" : "") + currencyFormatter.format(pnl));

            pnlLabel.removeClassNames(LumoUtility.TextColor.SUCCESS, LumoUtility.TextColor.ERROR);
            if (pnl > 0)
                pnlLabel.addClassName(LumoUtility.TextColor.SUCCESS);
            else if (pnl < 0)
                pnlLabel.addClassName(LumoUtility.TextColor.ERROR);

        } else {
            positionBadge.setText("NO ACTIVE TRADES");
            positionBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                    LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            positionBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

            pnlLabel.setText("$0.00");
            pnlLabel.removeClassNames(LumoUtility.TextColor.SUCCESS, LumoUtility.TextColor.ERROR);
        }
    }

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
}