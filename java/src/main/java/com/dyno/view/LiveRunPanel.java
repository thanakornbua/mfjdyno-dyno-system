package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

public final class LiveRunPanel extends JPanel {
    private final GaugeCardPanel lambdaGauge = new GaugeCardPanel();
    private final GaugeCardPanel tempGauge = new GaugeCardPanel();
    private final GaugeCardPanel o2Gauge = new GaugeCardPanel();
    private final MetricTilePanel powerTile = new MetricTilePanel();
    private final MetricTilePanel torqueTile = new MetricTilePanel();
    private final MetricTilePanel rpmTile = new MetricTilePanel();
    private final MetricTilePanel afrTile = new MetricTilePanel();
    private final DynoChartPanel chartPanel = new DynoChartPanel();
    private final JLabel runLabel = new JLabel("NO RUN");
    private final JLabel chartCaption = new JLabel("Peak — HP / — Nm");
    private final JLabel peakLabel = new JLabel("PEAK — HP / — Nm");
    private final JLabel chartSectionTitle = new JLabel("LIVE DYNO CHART");
    private final ControlBarPanel controlBarPanel;
    private final List<SecondaryMetricPanel> secondaryMetricPanels = new ArrayList<SecondaryMetricPanel>();

    public LiveRunPanel(ControlActionListener listener) {
        setLayout(new BorderLayout(22, 22));
        setOpaque(true);
        setBackground(OperatorUi.APP_BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 24, 24, 24));

        JPanel leftRail = new JPanel();
        leftRail.setOpaque(false);
        leftRail.setLayout(new BoxLayout(leftRail, BoxLayout.Y_AXIS));
        leftRail.setPreferredSize(new Dimension(248, 0));
        leftRail.add(lambdaGauge);
        leftRail.add(Box.createRigidArea(new Dimension(0, 14)));
        leftRail.add(tempGauge);
        leftRail.add(Box.createRigidArea(new Dimension(0, 14)));
        leftRail.add(o2Gauge);

        JPanel rightRail = new JPanel();
        rightRail.setOpaque(false);
        rightRail.setLayout(new BoxLayout(rightRail, BoxLayout.Y_AXIS));
        rightRail.setPreferredSize(new Dimension(288, 0));
        rightRail.add(powerTile);
        rightRail.add(Box.createRigidArea(new Dimension(0, 14)));
        rightRail.add(torqueTile);
        rightRail.add(Box.createRigidArea(new Dimension(0, 14)));
        rightRail.add(rpmTile);
        rightRail.add(Box.createRigidArea(new Dimension(0, 14)));
        rightRail.add(afrTile);

        JPanel chartCard = new JPanel(new BorderLayout(0, 16));
        chartCard.setOpaque(true);
        chartCard.setBackground(OperatorUi.SURFACE);
        chartCard.setBorder(OperatorUi.layeredCardBorder(18, 20, 20, 20));

        JPanel chartHeader = new JPanel(new BorderLayout(16, 0));
        chartHeader.setOpaque(false);
        runLabel.setForeground(OperatorUi.TEXT_PRIMARY);
        runLabel.setFont(OperatorUi.monoFont(28f, java.awt.Font.BOLD));
        chartCaption.setForeground(OperatorUi.TEXT_MUTED);
        chartCaption.setFont(OperatorUi.bodyFont(14f));
        peakLabel.setForeground(OperatorUi.ACCENT);
        peakLabel.setFont(OperatorUi.titleFont(12f));
        peakLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        chartSectionTitle.setForeground(OperatorUi.TEXT_SUBTLE);
        chartSectionTitle.setFont(OperatorUi.titleFont(11f));

        JPanel headerLeft = new JPanel();
        headerLeft.setOpaque(false);
        headerLeft.setLayout(new BoxLayout(headerLeft, BoxLayout.Y_AXIS));
        headerLeft.add(chartSectionTitle);
        headerLeft.add(Box.createRigidArea(new Dimension(0, 8)));
        headerLeft.add(runLabel);
        headerLeft.add(Box.createRigidArea(new Dimension(0, 6)));
        headerLeft.add(chartCaption);

        chartHeader.add(headerLeft, BorderLayout.WEST);
        chartHeader.add(peakLabel, BorderLayout.EAST);
        chartCard.add(chartHeader, BorderLayout.NORTH);
        chartCard.add(chartPanel, BorderLayout.CENTER);

        JPanel mainRow = new JPanel(new BorderLayout(22, 0));
        mainRow.setOpaque(false);
        mainRow.add(leftRail, BorderLayout.WEST);
        mainRow.add(chartCard, BorderLayout.CENTER);
        mainRow.add(rightRail, BorderLayout.EAST);

        JPanel secondaryRow = new JPanel(new GridLayout(1, 6, 14, 0));
        secondaryRow.setOpaque(false);
        for (int i = 0; i < 6; i++) {
            SecondaryMetricPanel panel = new SecondaryMetricPanel();
            secondaryMetricPanels.add(panel);
            secondaryRow.add(panel);
        }

        controlBarPanel = new ControlBarPanel(listener);

        JPanel bottom = new JPanel(new BorderLayout(0, 18));
        bottom.setOpaque(false);
        bottom.add(secondaryRow, BorderLayout.CENTER);
        bottom.add(controlBarPanel, BorderLayout.SOUTH);

        add(mainRow, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    public void render(OperatorViewModel model) {
        lambdaGauge.render(model.getLambdaGauge());
        tempGauge.render(model.getTempGauge());
        o2Gauge.render(model.getO2Gauge());
        powerTile.render(model.getPowerTile());
        torqueTile.render(model.getTorqueTile());
        rpmTile.render(model.getRpmTile());
        afrTile.render(model.getAfrTile());
        runLabel.setText(model.getRunLabel());
        chartCaption.setText(model.getChartCaption());
        peakLabel.setText("PEAK " + model.getPeakPowerText() + " / " + model.getPeakTorqueText());
        chartPanel.setRecording(model.isRecording());
        chartPanel.setRunPoints(model.getChartPoints());
        controlBarPanel.render(model);

        List<OperatorViewModel.SecondaryMetricModel> secondaryMetrics = model.getSecondaryMetrics();
        for (int i = 0; i < secondaryMetricPanels.size(); i++) {
            SecondaryMetricPanel panel = secondaryMetricPanels.get(i);
            if (i < secondaryMetrics.size()) {
                panel.render(secondaryMetrics.get(i));
            } else {
                panel.render(new OperatorViewModel.SecondaryMetricModel("—", "—", "", OperatorViewModel.Tone.UNAVAILABLE));
            }
        }
    }

    private static final class SecondaryMetricPanel extends JPanel {
        private final JLabel label = new JLabel("Metric");
        private final JLabel value = new JLabel("—");
        private final JLabel unit = new JLabel("");

        private SecondaryMetricPanel() {
            setLayout(new BorderLayout(0, 6));
            setOpaque(true);
            setBackground(OperatorUi.SURFACE);
            setBorder(OperatorUi.layeredCardBorder(14, 14, 14, 14));

            label.setForeground(OperatorUi.TEXT_SUBTLE);
            label.setFont(OperatorUi.titleFont(11f));

            value.setForeground(OperatorUi.TEXT_PRIMARY);
            value.setFont(OperatorUi.monoFont(24f, java.awt.Font.BOLD));

            unit.setForeground(OperatorUi.TEXT_MUTED);
            unit.setFont(OperatorUi.bodyFont(11f));

            add(label, BorderLayout.NORTH);
            add(value, BorderLayout.CENTER);
            add(unit, BorderLayout.SOUTH);
        }

        private void render(OperatorViewModel.SecondaryMetricModel model) {
            label.setText(model.getLabel());
            value.setText(model.getValueText());
            value.setForeground(OperatorUi.toneColor(model.getTone()));
            unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        }
    }
}
