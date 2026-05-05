package com.dyno.view;

import com.dyno.model.RunPoint;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

public final class DynoChartPanel extends JPanel {
    private static final Color POWER_COLOR = new Color(0x3ED598);
    private static final Color TORQUE_COLOR = new Color(0xF0B44C);
    private static final Color AXIS_COLOR = new Color(0xD8E1EA);
    private static final Color GRID_COLOR = new Color(0x2A3848);
    private static final Color PLACEHOLDER_COLOR = new Color(0x8F9CAD);
    private static final Color FRAME_COLOR = new Color(0x3A5062);
    private static final Color PLOT_BACKGROUND_TOP = new Color(0x1B2A38);
    private static final Color PLOT_BACKGROUND_BOTTOM = new Color(0x141F2A);

    private final Timer repaintTimer;

    private List<RunPoint> runPoints = new ArrayList<RunPoint>();
    private boolean recording;

    public DynoChartPanel() {
        setOpaque(true);
        setBackground(OperatorUi.SURFACE_ALT);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        repaintTimer = new Timer(100, event -> repaint());
        repaintTimer.start();
    }

    public void setRunPoints(List<RunPoint> runPoints) {
        this.runPoints = runPoints;
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int left = 72;
            int right = 72;
            int top = 44;
            int bottom = 54;
            int plotWidth = Math.max(1, width - left - right);
            int plotHeight = Math.max(1, height - top - bottom);

            drawPlotBackground(g2, left, top, plotWidth, plotHeight);
            drawTitles(g2, width);
            drawLegend(g2, left, width);
            drawPlotFrame(g2, left, top, plotWidth, plotHeight);

            if (runPoints.isEmpty()) {
                drawPlaceholder(g2, left, top, plotWidth, plotHeight);
                return;
            }

            double minRpm = runPoints.get(0).getEngineRpm();
            double maxRpm = runPoints.get(runPoints.size() - 1).getEngineRpm();
            if (Math.abs(maxRpm - minRpm) < 1.0) {
                minRpm = Math.max(0.0, minRpm - 100.0);
                maxRpm = maxRpm + 100.0;
            }

            double maxPower = 1.0;
            double maxTorque = 1.0;
            for (RunPoint point : runPoints) {
                maxPower = Math.max(maxPower, point.getPowerHp());
                maxTorque = Math.max(maxTorque, point.getTorqueNm());
            }
            maxPower = maxPower * 1.1;
            maxTorque = maxTorque * 1.1;

            drawGridAndLabels(g2, left, top, plotWidth, plotHeight, minRpm, maxRpm, maxPower, maxTorque);
            drawSeries(g2, runPoints, left, top, plotWidth, plotHeight, minRpm, maxRpm, maxPower, maxTorque);
        } finally {
            g2.dispose();
        }
    }

    private void drawPlotBackground(Graphics2D g2, int left, int top, int plotWidth, int plotHeight) {
        g2.setPaint(new GradientPaint(
            left,
            top,
            PLOT_BACKGROUND_TOP,
            left,
            top + plotHeight,
            PLOT_BACKGROUND_BOTTOM
        ));
        g2.fillRoundRect(left, top, plotWidth, plotHeight, 14, 14);
    }

    private void drawTitles(Graphics2D g2, int width) {
        Font oldFont = g2.getFont();
        g2.setColor(AXIS_COLOR);
        g2.setFont(oldFont.deriveFont(Font.BOLD, 13f));
        g2.drawString("POWER / TORQUE", 16, 22);
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 12f));
        String subtitle = recording ? "Collecting points during RECORDING" : "Frozen after run";
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(subtitle, width - metrics.stringWidth(subtitle) - 16, 22);
        g2.setFont(oldFont);
    }

    private void drawLegend(Graphics2D g2, int left, int width) {
        int y = 46;
        int powerX = Math.max(left + 8, width - 248);
        g2.setColor(POWER_COLOR);
        g2.fillRoundRect(powerX, y - 9, 18, 4, 4, 4);
        g2.setColor(AXIS_COLOR);
        g2.drawString("Power (HP)", powerX + 24, y - 4);

        int torqueX = powerX + 110;
        g2.setColor(TORQUE_COLOR);
        g2.fillRoundRect(torqueX, y - 9, 18, 4, 4, 4);
        g2.setColor(AXIS_COLOR);
        g2.drawString("Torque (Nm)", torqueX + 24, y - 4);
    }

    private void drawPlotFrame(Graphics2D g2, int left, int top, int plotWidth, int plotHeight) {
        g2.setColor(FRAME_COLOR);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(left, top, plotWidth, plotHeight, 14, 14);
    }

    private void drawPlaceholder(Graphics2D g2, int left, int top, int plotWidth, int plotHeight) {
        g2.setColor(PLACEHOLDER_COLOR);
        String text = recording
            ? "Waiting for valid power/torque samples"
            : "No recorded dyno run yet";
        FontMetrics metrics = g2.getFontMetrics();
        int x = left + (plotWidth - metrics.stringWidth(text)) / 2;
        int y = top + (plotHeight / 2);
        g2.drawString(text, x, y);
        g2.drawString("Engine RPM", left + (plotWidth / 2) - 32, top + plotHeight + 34);
        g2.drawString("Power (HP)", 16, top - 8);
        g2.drawString("Torque (Nm)", left + plotWidth + 8, top - 8);
    }

    private void drawGridAndLabels(
        Graphics2D g2,
        int left,
        int top,
        int plotWidth,
        int plotHeight,
        double minRpm,
        double maxRpm,
        double maxPower,
        double maxTorque
    ) {
        g2.setColor(GRID_COLOR);
        for (int i = 1; i <= 4; i++) {
            int y = top + (i * plotHeight / 5);
            g2.drawLine(left, y, left + plotWidth, y);
        }
        for (int i = 1; i <= 5; i++) {
            int x = left + (i * plotWidth / 6);
            g2.drawLine(x, top, x, top + plotHeight);
        }

        g2.setColor(AXIS_COLOR);
        g2.drawString("Power (HP)", 16, top - 8);
        g2.drawString("Torque (Nm)", left + plotWidth + 8, top - 8);
        g2.drawString("Engine RPM", left + (plotWidth / 2) - 32, top + plotHeight + 34);

        for (int i = 0; i <= 5; i++) {
            double ratio = i / 5.0;
            double powerValue = maxPower * (1.0 - ratio);
            double torqueValue = maxTorque * (1.0 - ratio);
            int y = top + (int) Math.round(plotHeight * ratio);
            g2.drawString(Integer.toString((int) Math.round(powerValue)), 18, y + 4);
            String torqueText = Integer.toString((int) Math.round(torqueValue));
            FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(torqueText, left + plotWidth + 10, y + 4);
            int x = left + (int) Math.round(plotWidth * ratio);
            int rpm = (int) Math.round(minRpm + ((maxRpm - minRpm) * ratio));
            String rpmText = Integer.toString(rpm);
            g2.drawString(rpmText, x - (g2.getFontMetrics().stringWidth(rpmText) / 2), top + plotHeight + 18);
        }
    }

    private void drawSeries(
        Graphics2D g2,
        List<RunPoint> points,
        int left,
        int top,
        int plotWidth,
        int plotHeight,
        double minRpm,
        double maxRpm,
        double maxPower,
        double maxTorque
    ) {
        int[] powerX = new int[points.size()];
        int[] powerY = new int[points.size()];
        int[] torqueX = new int[points.size()];
        int[] torqueY = new int[points.size()];

        double rpmSpan = Math.max(1.0, maxRpm - minRpm);
        for (int i = 0; i < points.size(); i++) {
            RunPoint point = points.get(i);
            double xRatio = (point.getEngineRpm() - minRpm) / rpmSpan;
            powerX[i] = left + (int) Math.round(xRatio * plotWidth);
            torqueX[i] = powerX[i];
            powerY[i] = top + plotHeight - (int) Math.round((point.getPowerHp() / maxPower) * plotHeight);
            torqueY[i] = top + plotHeight - (int) Math.round((point.getTorqueNm() / maxTorque) * plotHeight);
        }

        g2.setStroke(new BasicStroke(2.2f));
        g2.setColor(new Color(POWER_COLOR.getRed(), POWER_COLOR.getGreen(), POWER_COLOR.getBlue(), 90));
        g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolyline(powerX, powerY, powerX.length);
        g2.setColor(POWER_COLOR);
        g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolyline(powerX, powerY, powerX.length);
        g2.setColor(new Color(TORQUE_COLOR.getRed(), TORQUE_COLOR.getGreen(), TORQUE_COLOR.getBlue(), 90));
        g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolyline(torqueX, torqueY, torqueX.length);
        g2.setColor(TORQUE_COLOR);
        g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolyline(torqueX, torqueY, torqueX.length);
    }
}
