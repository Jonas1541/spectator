package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.ui.broadcaster.MarketDataBroadcaster;
import com.jonasdurau.spectator.ui.broadcaster.MarketTick;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@Route("")
@PageTitle("Spectator | Trading Terminal")
public class DashboardView extends VerticalLayout {

    private final MarketDataBroadcaster broadcaster;
    private final CandleRepository candleRepository;
    private Consumer<MarketTick> broadcasterListener;

    // Componentes Visuais
    private final H2 priceLabel = new H2("Loading...");
    private final Span regimeBadge = new Span("ANALYZING");
    private final Grid<Candle> candleGrid = new Grid<>(Candle.class, false);

    // Formatadores
    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    public DashboardView(MarketDataBroadcaster broadcaster, CandleRepository candleRepository) {
        this.broadcaster = broadcaster;
        this.candleRepository = candleRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        createHeader();
        createMetricsBoard();
        createLiveGrid();
        
        // Carrega os dados iniciais do banco antes do primeiro tick chegar
        loadInitialData();
    }

    private void createHeader() {
        H1 title = new H1("Spectator Engine");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);
        add(title);
    }

    private void createMetricsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setAlignItems(Alignment.CENTER);
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        board.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        VerticalLayout priceLayout = new VerticalLayout(new Span("BTC/USDT Live Price"), priceLabel);
        priceLayout.setSpacing(false);
        priceLayout.setPadding(false);
        priceLabel.addClassNames(LumoUtility.TextColor.PRIMARY, LumoUtility.Margin.NONE);

        VerticalLayout regimeLayout = new VerticalLayout(new Span("Market Regime"), regimeBadge);
        regimeLayout.setSpacing(false);
        regimeLayout.setPadding(false);
        regimeLayout.setAlignItems(Alignment.END);
        regimeBadge.addClassNames(LumoUtility.Padding.Horizontal.MEDIUM, LumoUtility.Padding.Vertical.SMALL, LumoUtility.BorderRadius.LARGE, LumoUtility.FontWeight.BOLD);

        board.add(priceLayout, regimeLayout);
        add(board);
    }

    private void createLiveGrid() {
        candleGrid.addColumn(c -> timeFormatter.format(c.getTime())).setHeader("Time").setAutoWidth(true);
        candleGrid.addColumn(c -> currencyFormatter.format(c.getOpen())).setHeader("Open").setAutoWidth(true);
        candleGrid.addColumn(c -> currencyFormatter.format(c.getHigh())).setHeader("High").setAutoWidth(true);
        candleGrid.addColumn(c -> currencyFormatter.format(c.getLow())).setHeader("Low").setAutoWidth(true);
        candleGrid.addColumn(c -> currencyFormatter.format(c.getClose())).setHeader("Close").setAutoWidth(true);
        candleGrid.addColumn(Candle::getVolume).setHeader("Volume").setAutoWidth(true);

        // Estiliza linhas baseadas em alta/baixa
        candleGrid.setPartNameGenerator(candle -> candle.getClose() >= candle.getOpen() ? "bullish" : "bearish");
        
        candleGrid.setSizeFull();
        add(candleGrid);
    }

    private void loadInitialData() {
        List<Candle> initialCandles = candleRepository.findLastCandles("BTCUSDT", 50);
        candleGrid.setItems(initialCandles);
        if (!initialCandles.isEmpty()) {
            updateMetrics(initialCandles.get(0), MarketRegime.SIDEWAYS); // O Regime real virá no próximo tick
        }
    }

    // --- Lógica de Push e Concorrência ---

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        broadcasterListener = tick -> ui.access(() -> {
            updateMetrics(tick.candle(), tick.regime());
            // Atualiza o grid puxando os 50 mais recentes do banco para garantir consistência
            candleGrid.setItems(candleRepository.findLastCandles("BTCUSDT", 50));
        });
        broadcaster.register(broadcasterListener);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        broadcaster.unregister(broadcasterListener);
    }

    private void updateMetrics(Candle candle, MarketRegime regime) {
        priceLabel.setText(currencyFormatter.format(candle.getClose()));
        regimeBadge.setText(regime.name().replace("_", " "));

        // Limpa classes antigas e aplica as novas cores de acordo com o regime
        regimeBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                                     LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR,
                                     LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING,
                                     LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.SECONDARY);

        switch (regime) {
            case TRENDING_UP -> regimeBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            case TRENDING_DOWN -> regimeBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            case VOLATILE -> regimeBadge.addClassNames(LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING);
            default -> regimeBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.TERTIARY);
        }
    }
}