package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.backtest.BacktestCsvExporter;
import com.jonasdurau.spectator.core.backtest.BacktestEngineService;
import com.jonasdurau.spectator.core.backtest.BacktestReport;
import com.jonasdurau.spectator.core.backtest.MonteCarloReport;
import com.jonasdurau.spectator.core.backtest.PortfolioBacktestReport;
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
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.theme.lumo.LumoUtility;

import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private final MultiSelectComboBox<String> symbolSelector = new MultiSelectComboBox<>("Symbols");
    private final Button runButton = new Button("Sync & Run Backtest");
    private final Checkbox walkForwardToggle = new Checkbox("Walk-Forward Analysis (5 Slices)");
    private final Button exportCsvButton = new Button("📊 Export CSV");
    private final Anchor downloadAnchor = new Anchor();
    
    // State for CSV export (uses first symbol's data)
    private List<Candle> lastChartData;
    private BacktestReport lastReport;
    
    // Results Board
    private final Span winRateLabel = new Span("-");
    private final Span pnlLabel = new Span("-");
    private final Span tradesLabel = new Span("-");
    private final Span drawdownLabel = new Span("-");
    private final Span expectancyLabel = new Span("-");
    private final Span sharpeLabel = new Span("-");
    private final Span riskOfRuinLabel = new Span("-");
    private final Span medianDdLabel = new Span("-");
    private final Span finalCapitalLabel = new Span("-");
    
    // Walk-Forward Data Grid
    private final Grid<BacktestReport> wfaGrid = new Grid<>(BacktestReport.class, false);
    private final Span consistencyLabel = new Span("");

    // Multi-Symbol Charts Container
    private final VerticalLayout chartsContainer = new VerticalLayout();

    public BacktestView(HistoricalSyncService syncService, 
                        BacktestEngineService backtestEngine, 
                        CandleRepository candleRepository, 
                        List<TradingStrategy> availableStrategies,
                        WalkForwardAnalyzerService walkForwardAnalyzer,
                        @Value("${spectator.symbols}") String symbolsConfig) {
        this.syncService = syncService;
        this.backtestEngine = backtestEngine;
        this.candleRepository = candleRepository;
        this.availableStrategies = availableStrategies;
        this.walkForwardAnalyzer = walkForwardAnalyzer;

        // Parse configured symbols for the multi-select
        List<String> symbols = java.util.Arrays.stream(symbolsConfig.split(","))
                .map(s -> s.trim().toUpperCase())
                .toList();
        symbolSelector.setItems(symbols);
        symbolSelector.select(symbols.get(0)); // Pre-select first symbol
        symbolSelector.setWidth("250px");
        symbolSelector.setPlaceholder("Select symbols...");

        setSizeFull();
        setPadding(true);

        createHeader();
        createControlPanel();
        createResultsBoard();
        
        chartsContainer.setPadding(false);
        chartsContainer.setSpacing(true);
        chartsContainer.setWidthFull();
        add(chartsContainer);
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

        exportCsvButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        exportCsvButton.setEnabled(false);
        exportCsvButton.addClickListener(e -> triggerCsvDownload());
        
        // Anchor invisível para disparar o download do browser
        downloadAnchor.getElement().setAttribute("download", true);
        downloadAnchor.getStyle().set("display", "none");

        controls.add(symbolSelector, strategySelector, startDatePicker, endDatePicker, capitalField, runButton);

        HorizontalLayout secondaryControls = new HorizontalLayout();
        secondaryControls.setAlignItems(Alignment.CENTER);
        secondaryControls.setSpacing(true);
        secondaryControls.add(walkForwardToggle, exportCsvButton);

        add(controls, secondaryControls, downloadAnchor);
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
        board.add(createMetric("Final Capital", finalCapitalLabel));

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
        Set<String> selectedSymbols = symbolSelector.getSelectedItems();
        
        if (selectedSymbols.isEmpty() || startDatePicker.getValue() == null || endDatePicker.getValue() == null || strategySelector.getValue() == null || capitalField.getValue() == null) {
            Notification.show("Please select at least one symbol and fill all fields.", 3000, Notification.Position.TOP_CENTER);
            return;
        }

        runButton.setEnabled(false);
        runButton.setText("Downloading & Processing...");
        
        Instant start = startDatePicker.getValue().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = endDatePicker.getValue().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        StrategyOption selectedOption = strategySelector.getValue();
        double initialCapital = capitalField.getValue();
        List<String> symbols = new ArrayList<>(selectedSymbols);

        UI ui = UI.getCurrent();
        Thread backgroundThread = new Thread(() -> {
            try {
                // Sync all selected symbols
                for (String symbol : symbols) {
                    syncService.syncPeriod(symbol, "4h", start, end);
                    syncService.syncPeriod(symbol, "1h", start, end);
                }

                if (walkForwardToggle.getValue()) {
                    // WALK-FORWARD MODE — uses first symbol only (as before)
                    String primarySymbol = symbols.get(0);
                    
                    BacktestReport overallReport = backtestEngine.runBacktest(
                            selectedOption.name() + " (Overall)", selectedOption.strategies(), primarySymbol, start, end, initialCapital
                    );

                    WalkForwardReport wfaReport = walkForwardAnalyzer.runAnalysis(
                            selectedOption.name(), selectedOption.strategies(), primarySymbol, start, end, initialCapital, 5
                    );
                    
                    List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(primarySymbol, "1h", start, end);

                    ui.access(() -> {
                        wfaGrid.setItems(wfaReport.sliceReports());
                        wfaGrid.setVisible(true);
                        
                        consistencyLabel.setText(String.format("Walk-Forward Consistency: %.0f%% (%d/%d profitable slices)", 
                                wfaReport.consistencyScore(), wfaReport.profitableSlices(), wfaReport.totalSlices()));
                        consistencyLabel.setVisible(true);
                        
                        updateResultsBoard(overallReport);
                        renderChartsForSymbols(java.util.Map.of(primarySymbol, overallReport), start, end);
                        
                        lastChartData = chartData;
                        lastReport = overallReport;
                        exportCsvButton.setEnabled(true);
                        
                        runButton.setEnabled(true);
                        runButton.setText("Sync & Run Backtest");
                        Notification.show("Walk-Forward completed successfully!", 3000, Notification.Position.TOP_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                    });

                } else {
                    // PORTFOLIO MODE — multi-symbol
                    PortfolioBacktestReport portfolio = backtestEngine.runPortfolioBacktest(
                            selectedOption.name(), selectedOption.strategies(), symbols, start, end, initialCapital
                    );

                    // Load chart data for all symbols
                    java.util.Map<String, List<Candle>> chartDataMap = new java.util.LinkedHashMap<>();
                    for (String symbol : symbols) {
                        List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "1h", start, end);
                        chartDataMap.put(symbol, chartData);
                    }

                    ui.access(() -> {
                        wfaGrid.setVisible(false);
                        consistencyLabel.setVisible(false);
                        
                        // Use first symbol's report for the top-level metrics board,
                        // or aggregate if multiple symbols
                        if (portfolio.symbolReports().size() == 1) {
                            BacktestReport singleReport = portfolio.symbolReports().values().iterator().next();
                            updateResultsBoard(singleReport);
                        } else {
                            updatePortfolioResultsBoard(portfolio);
                        }
                        
                        renderChartsForSymbols(portfolio.symbolReports(), start, end);
                        
                        // CSV export uses first symbol
                        String firstSymbol = symbols.get(0);
                        lastChartData = chartDataMap.get(firstSymbol);
                        lastReport = portfolio.symbolReports().get(firstSymbol);
                        exportCsvButton.setEnabled(true);
                        
                        runButton.setEnabled(true);
                        runButton.setText("Sync & Run Backtest");
                        Notification.show("Portfolio Backtest completed! (" + symbols.size() + " symbols)", 3000, Notification.Position.TOP_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
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

    private void renderChartsForSymbols(java.util.Map<String, BacktestReport> symbolReports, Instant start, Instant end) {
        chartsContainer.removeAll();
        
        for (java.util.Map.Entry<String, BacktestReport> entry : symbolReports.entrySet()) {
            String symbol = entry.getKey();
            BacktestReport report = entry.getValue();
            
            List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "1h", start, end);
            
            // Symbol header with key metrics
            String pnlStr = String.format(java.util.Locale.US, "%+.2f", report.netProfit());
            String headerText = String.format("%s — %d trades | WR: %.1f%% | PnL: $%s", 
                    symbol, report.totalTrades(), report.winRate(), pnlStr);
            H3 symbolHeader = new H3(headerText);
            symbolHeader.getStyle().set("color", report.netProfit() >= 0 ? "#26a69a" : "#ef5350");
            symbolHeader.addClassNames(LumoUtility.Margin.Bottom.NONE, LumoUtility.Margin.Top.MEDIUM);
            
            TradingViewChart chart = new TradingViewChart();
            chart.setHeight("450px");
            
            chartsContainer.add(symbolHeader, chart);
            
            // Defer setBacktestData to the next event loop so onAttach fires first
            UI.getCurrent().access(() -> {
                chart.setBacktestData(chartData, report.tradeLog(), report.regimeChanges());
            });
        }
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

        // --- FINAL CAPITAL ---
        finalCapitalLabel.setText(String.format(java.util.Locale.US, "$%.2f", report.finalCapital()));
        finalCapitalLabel.getStyle().set("color", report.finalCapital() >= report.initialCapital() ? colorGreen : colorRed);
    }

    private void updatePortfolioResultsBoard(PortfolioBacktestReport portfolio) {
        String colorGreen = "#26a69a";
        String colorRed = "#ef5350";
        String colorYellow = "#f5cb5c";

        // Aggregate metrics across all symbols
        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalNetProfit = portfolio.globalNetProfit();

        for (BacktestReport r : portfolio.symbolReports().values()) {
            totalTrades += r.totalTrades();
            totalWins += r.winningTrades();
            totalLosses += r.losingTrades();
        }

        double winRate = totalTrades > 0 ? ((double) totalWins / totalTrades) * 100 : 0.0;

        winRateLabel.setText(String.format(java.util.Locale.US, "%.2f%%", winRate));
        tradesLabel.setText(String.format("%d", totalTrades));

        String pnlStr = String.format(java.util.Locale.US, "$%.2f", totalNetProfit);
        pnlLabel.setText(pnlStr);
        pnlLabel.getStyle().set("color", totalNetProfit >= 0 ? colorGreen : colorRed);

        drawdownLabel.setText(String.format(java.util.Locale.US, "%.2f%%", portfolio.globalMaxDrawdown()));
        if (portfolio.globalMaxDrawdown() < 10.0) {
            drawdownLabel.getStyle().set("color", colorGreen);
        } else if (portfolio.globalMaxDrawdown() <= 20.0) {
            drawdownLabel.getStyle().set("color", colorYellow);
        } else {
            drawdownLabel.getStyle().set("color", colorRed);
        }

        // --- GLOBAL EXPECTANCY ---
        expectancyLabel.setText(String.format(java.util.Locale.US, "$%.2f", portfolio.globalExpectancy()));
        expectancyLabel.getStyle().set("color", portfolio.globalExpectancy() > 0 ? colorGreen : colorRed);

        // --- GLOBAL SHARPE RATIO ---
        sharpeLabel.setText(String.format(java.util.Locale.US, "%.2f", portfolio.globalSharpeRatio()));
        if (portfolio.globalSharpeRatio() >= 1.0) {
            sharpeLabel.getStyle().set("color", colorGreen);
        } else if (portfolio.globalSharpeRatio() > 0.0) {
            sharpeLabel.getStyle().set("color", colorYellow);
        } else {
            sharpeLabel.getStyle().set("color", colorRed);
        }

        // --- GLOBAL RISK OF RUIN (Monte Carlo) ---
        MonteCarloReport mc = portfolio.globalMcReport();
        riskOfRuinLabel.setText(String.format(java.util.Locale.US, "%.2f%%", mc.riskOfRuin()));
        if (mc.riskOfRuin() < 1.0) {
            riskOfRuinLabel.getStyle().set("color", colorGreen);
        } else if (mc.riskOfRuin() < 5.0) {
            riskOfRuinLabel.getStyle().set("color", colorYellow);
        } else {
            riskOfRuinLabel.getStyle().set("color", colorRed);
        }

        // --- GLOBAL MEDIAN DRAWDOWN ---
        medianDdLabel.setText(String.format(java.util.Locale.US, "%.2f%%", mc.medianMaxDrawdown()));
        medianDdLabel.getStyle().set("color", colorRed);

        // --- FINAL CAPITAL ---
        finalCapitalLabel.setText(String.format(java.util.Locale.US, "$%.2f", portfolio.globalFinalCapital()));
        finalCapitalLabel.getStyle().set("color", portfolio.globalFinalCapital() >= portfolio.globalInitialCapital() ? colorGreen : colorRed);
    }

    private void triggerCsvDownload() {
        if (lastChartData == null || lastReport == null) {
            Notification.show("Run a backtest first.", 3000, Notification.Position.TOP_CENTER);
            return;
        }

        String csvContent = BacktestCsvExporter.export(lastChartData, lastReport);
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);

        String startStr = startDatePicker.getValue() != null ? startDatePicker.getValue().toString() : "start";
        String endStr = endDatePicker.getValue() != null ? endDatePicker.getValue().toString() : "end";
        String filename = String.format("backtest_portfolio_%s_%s.csv", startStr, endStr);

        StreamResource resource = new StreamResource(filename, () -> new ByteArrayInputStream(csvBytes));
        resource.setContentType("text/csv");
        resource.setCacheTime(0);

        downloadAnchor.setHref(resource);
        downloadAnchor.getElement().setAttribute("download", filename);

        // Dispara o clique programaticamente para iniciar o download
        downloadAnchor.getElement().executeJs("this.click();");

        Notification.show("CSV exported: " + filename, 3000, Notification.Position.TOP_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}