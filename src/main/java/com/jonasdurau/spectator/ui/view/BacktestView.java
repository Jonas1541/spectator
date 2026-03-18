package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.backtest.BacktestEngineService;
import com.jonasdurau.spectator.core.backtest.BacktestReport;
import com.jonasdurau.spectator.core.backtest.MonteCarloReport;
import com.jonasdurau.spectator.core.backtest.WalkForwardAnalyzerService;
import com.jonasdurau.spectator.core.backtest.WalkForwardReport;
import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.service.HistoricalSyncService;
import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import com.jonasdurau.spectator.ui.components.TradingViewChart;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Route("backtest")
@PageTitle("Spectator | Backtest Studio")
public class BacktestView extends VerticalLayout {

    // Helper Record for the dropdown menu
    public record StrategyOption(String name, List<TradingStrategy> strategies) {}

    private final HistoricalSyncService syncService;
    private final BacktestEngineService backtestEngine;
    private final CandleRepository candleRepository;
    private final List<TradingStrategy> availableStrategies;
    private final WalkForwardAnalyzerService walkForwardAnalyzer;

    // UI Components
    private final DatePicker startDatePicker = new DatePicker("Start Date");
    private final DatePicker endDatePicker = new DatePicker("End Date");
    private final NumberField capitalField = new NumberField("Starting Capital (USDT)");
    private final ComboBox<StrategyOption> strategySelector = new ComboBox<>("Strategy Mode");
    private final Button runButton = new Button("Sync & Run Backtest");
    private final Checkbox walkForwardToggle = new Checkbox("Walk-Forward Analysis (5 Slices)");
    
    // Results Board
    private final Span winRateLabel = new Span("-");
    private final Span pnlLabel = new Span("-");
    private final Span tradesLabel = new Span("-");
    private final Span drawdownLabel = new Span("-");
    private final Span expectancyLabel = new Span("-");
    private final Span sharpeLabel = new Span("-");
    private final Span riskOfRuinLabel = new Span("-");
    private final Span medianDdLabel = new Span("-");
    
    // Walk-Forward Data Grid
    private final Grid<BacktestReport> wfaGrid = new Grid<>(BacktestReport.class, false);
    private final Span consistencyLabel = new Span("");

    private final TradingViewChart chart = new TradingViewChart();

    public BacktestView(HistoricalSyncService syncService, 
                        BacktestEngineService backtestEngine, 
                        CandleRepository candleRepository, 
                        List<TradingStrategy> availableStrategies,
                        WalkForwardAnalyzerService walkForwardAnalyzer) {
        this.syncService = syncService;
        this.backtestEngine = backtestEngine;
        this.candleRepository = candleRepository;
        this.availableStrategies = availableStrategies;
        this.walkForwardAnalyzer = walkForwardAnalyzer;

        setSizeFull();
        setPadding(true);

        createHeader();
        createControlPanel();
        createResultsBoard();
        
        add(chart);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        attachEvent.getUI().getPage().executeJs("setTimeout(() => document.documentElement.setAttribute('theme', 'dark'), 0);");
    }

    private void createHeader() {
        H1 title = new H1("Backtest Studio");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);

        Button liveButton = new Button("⚡ Live Dashboard");
        liveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        liveButton.addClickListener(e -> UI.getCurrent().navigate(DashboardView.class)); // Volta pra tela principal

        HorizontalLayout headerLayout = new HorizontalLayout(title, liveButton);
        headerLayout.setWidthFull();
        headerLayout.setAlignItems(Alignment.CENTER);
        headerLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);

        add(headerLayout);
    }

    private void createControlPanel() {
        HorizontalLayout controls = new HorizontalLayout();
        controls.setAlignItems(Alignment.BASELINE);
        controls.setWidthFull();

        startDatePicker.setValue(LocalDate.now().minusMonths(3));
        endDatePicker.setValue(LocalDate.now());
        
        List<StrategyOption> options = new ArrayList<>();
        
        // Master Engine (All strategies)
        options.add(new StrategyOption("Master Engine (Auto-Switch)", availableStrategies));
        
        // Isolated options for debugging
        for (TradingStrategy s : availableStrategies) {
            options.add(new StrategyOption(s.getName() + " (Isolated)", List.of(s)));
        }

        strategySelector.setItems(options);
        strategySelector.setItemLabelGenerator(StrategyOption::name);
        if (!options.isEmpty()) {
            strategySelector.setValue(options.get(0));
        }
        
        capitalField.setValue(10000.0);
        capitalField.setMin(10.0);

        runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        runButton.addClickListener(e -> executeBacktest());

        controls.add(strategySelector, startDatePicker, endDatePicker, capitalField, walkForwardToggle, runButton);
        add(controls);
    }

    private void createResultsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        board.addClassNames(LumoUtility.Background.BASE, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        board.add(createMetric("Win Rate", winRateLabel));
        board.add(createMetric("Net Profit", pnlLabel));
        board.add(createMetric("Total Trades", tradesLabel));
        board.add(createMetric("Max Drawdown", drawdownLabel));
        board.add(createMetric("Expectancy", expectancyLabel));
        board.add(createMetric("Sharpe Ratio", sharpeLabel));
        board.add(createMetric("Risk of Ruin", riskOfRuinLabel));
        board.add(createMetric("MC Median DD", medianDdLabel));

        add(board);

        // CONFIGURATION OF WALK-FORWARD GRID (Hidden initially)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM yyyy").withZone(ZoneId.of("UTC"));
        
        wfaGrid.addColumn(BacktestReport::strategyName).setHeader("Period").setAutoWidth(true);
        wfaGrid.addColumn(r -> formatter.format(r.startTime()) + " - " + formatter.format(r.endTime())).setHeader("Dates").setAutoWidth(true);
        wfaGrid.addColumn(r -> String.format("$%.2f", r.netProfit())).setHeader("Net Profit").setAutoWidth(true);
        wfaGrid.addColumn(r -> String.format("%.2f%%", r.winRate())).setHeader("Win Rate").setAutoWidth(true);
        wfaGrid.addColumn(r -> String.format("%.2f%%", r.maxDrawdown())).setHeader("Max DD").setAutoWidth(true);
        wfaGrid.addColumn(r -> String.format("%.2f", r.sharpeRatio())).setHeader("Sharpe").setAutoWidth(true);
        
        wfaGrid.addThemeVariants(com.vaadin.flow.component.grid.GridVariant.LUMO_COMPACT, com.vaadin.flow.component.grid.GridVariant.LUMO_ROW_STRIPES);
        wfaGrid.setVisible(false);
        consistencyLabel.setVisible(false);
        consistencyLabel.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.FontWeight.BOLD, LumoUtility.Margin.Top.MEDIUM);

        add(consistencyLabel, wfaGrid);
    }

    private VerticalLayout createMetric(String title, Span valueLabel) {
        valueLabel.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);
        VerticalLayout layout = new VerticalLayout(new Span(title), valueLabel);
        layout.setSpacing(false);
        layout.setPadding(false);
        layout.setAlignItems(Alignment.CENTER);
        return layout;
    }

    private void executeBacktest() {
        if (startDatePicker.getValue() == null || endDatePicker.getValue() == null || strategySelector.getValue() == null || capitalField.getValue() == null) {
            Notification.show("Please select all fields including Capital.", 3000, Notification.Position.TOP_CENTER);
            return;
        }

        runButton.setEnabled(false);
        runButton.setText("Downloading & Processing...");
        
        java.time.Instant start = startDatePicker.getValue().atStartOfDay().toInstant(ZoneOffset.UTC);
        java.time.Instant end = endDatePicker.getValue().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        StrategyOption selectedOption = strategySelector.getValue();
        double initialCapital = capitalField.getValue();

        UI ui = UI.getCurrent();
        Thread backgroundThread = new Thread(() -> {
            try {
                syncService.syncPeriod("BTCUSDT", "4h", start, end);
                syncService.syncPeriod("BTCUSDT", "1h", start, end);

                if (walkForwardToggle.getValue()) {
                    // MODO WALK FORWARD (5 Fatias)
                    
                    // 1. Roda o backtest COMPLETO para preencher o painel superior e o gráfico com tudo!
                    BacktestReport overallReport = backtestEngine.runBacktest(
                            selectedOption.name() + " (Overall)", selectedOption.strategies(), "BTCUSDT", start, end, initialCapital
                    );

                    // 2. Roda o fatiador para preencher a tabela de consistência
                    WalkForwardReport wfaReport = walkForwardAnalyzer.runAnalysis(
                            selectedOption.name(), selectedOption.strategies(), "BTCUSDT", start, end, initialCapital, 5
                    );
                    
                    // 3. Pega TODOS os candles do período total para o gráfico
                    List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc("BTCUSDT", "1h", start, end);

                    ui.access(() -> {
                        wfaGrid.setItems(wfaReport.sliceReports());
                        wfaGrid.setVisible(true);
                        
                        consistencyLabel.setText(String.format("Walk-Forward Consistency: %.0f%% (%d/%d profitable slices)", 
                                wfaReport.consistencyScore(), wfaReport.profitableSlices(), wfaReport.totalSlices()));
                        consistencyLabel.setVisible(true);
                        
                        // CORREÇÃO APLICADA: Agora usamos o overallReport!
                        updateResultsBoard(overallReport);
                        chart.setBacktestData(chartData, overallReport.tradeLog(), overallReport.regimeChanges());
                        
                        runButton.setEnabled(true);
                        runButton.setText("Sync & Run Backtest");
                        Notification.show("Walk-Forward completed successfully!", 3000, Notification.Position.TOP_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                    });

                } else {
                    // STANDARD MODE
                    BacktestReport report = backtestEngine.runBacktest(
                            selectedOption.name(), selectedOption.strategies(), "BTCUSDT", start, end, initialCapital
                    );
                    List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc("BTCUSDT", "1h", start, end);

                    ui.access(() -> {
                        wfaGrid.setVisible(false);
                        consistencyLabel.setVisible(false);
                        
                        updateResultsBoard(report);
                        chart.setBacktestData(chartData, report.tradeLog(), report.regimeChanges());
                        
                        runButton.setEnabled(true);
                        runButton.setText("Sync & Run Backtest");
                        Notification.show("Backtest completed successfully!", 3000, Notification.Position.TOP_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                    });
                }

            } catch (Exception e) {
                ui.access(() -> {
                    runButton.setEnabled(true);
                    runButton.setText("Sync & Run Backtest");
                    Notification.show("Error: " + e.getMessage(), 5000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
            }
        });
        
        backgroundThread.start();
    }

    private void updateResultsBoard(BacktestReport report) {
        // As cores exatas do TradingView para manter o design consistente
        String colorGreen = "#26a69a";
        String colorRed = "#ef5350";
        String colorYellow = "#f5cb5c";

        winRateLabel.setText(String.format(java.util.Locale.US, "%.2f%%", report.winRate()));
        tradesLabel.setText(String.format("%d", report.totalTrades()));
        
        // --- NET PROFIT ---
        String pnlStr = String.format(java.util.Locale.US, "$%.2f", report.netProfit());
        pnlLabel.setText(pnlStr);
        pnlLabel.getStyle().set("color", report.netProfit() >= 0 ? colorGreen : colorRed);

        // --- DRAWDOWN ---
        drawdownLabel.setText(String.format(java.util.Locale.US, "%.2f%%", report.maxDrawdown()));
        if (report.maxDrawdown() < 10.0) {
            drawdownLabel.getStyle().set("color", colorGreen);
        } else if (report.maxDrawdown() <= 20.0) {
            drawdownLabel.getStyle().set("color", colorYellow);
        } else {
            drawdownLabel.getStyle().set("color", colorRed);
        }

        // --- EXPECTANCY ---
        expectancyLabel.setText(String.format(java.util.Locale.US, "$%.2f", report.expectancy()));
        expectancyLabel.getStyle().set("color", report.expectancy() > 0 ? colorGreen : colorRed);

        // --- SHARPE RATIO ---
        sharpeLabel.setText(String.format(java.util.Locale.US, "%.2f", report.sharpeRatio()));
        if (report.sharpeRatio() >= 1.0) {
            sharpeLabel.getStyle().set("color", colorGreen);
        } else if (report.sharpeRatio() > 0.0) {
            sharpeLabel.getStyle().set("color", colorYellow);
        } else {
            sharpeLabel.getStyle().set("color", colorRed);
        }

        // --- RISK OF RUIN (Monte Carlo) ---
        MonteCarloReport mc = report.monteCarlo();
        riskOfRuinLabel.setText(String.format(java.util.Locale.US, "%.2f%%", mc.riskOfRuin()));
        if (mc.riskOfRuin() < 1.0) {
            riskOfRuinLabel.getStyle().set("color", colorGreen);
        } else if (mc.riskOfRuin() < 5.0) {
            riskOfRuinLabel.getStyle().set("color", colorYellow);
        } else {
            riskOfRuinLabel.getStyle().set("color", colorRed);
        }

        // --- MEDIAN DRAWDOWN ---
        medianDdLabel.setText(String.format(java.util.Locale.US, "%.2f%%", mc.medianMaxDrawdown()));
        medianDdLabel.getStyle().set("color", colorRed);
    }
}