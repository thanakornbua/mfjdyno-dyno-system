package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

public final class MetricTilePanel extends JPanel {
    private final JPanel accentBand = new JPanel();
    private final JLabel label = new JLabel("Metric");
    private final JLabel value = new JLabel("—");
    private final JLabel unit = new JLabel("");
    private final JLabel footer = new JLabel(" ");

    public MetricTilePanel() {
        setLayout(new BorderLayout(0, 0));
        setOpaque(true);
        setBackground(OperatorUi.SURFACE);
        setBorder(OperatorUi.layeredCardBorder(18, 18, 18, 18));

        accentBand.setPreferredSize(new Dimension(0, 4));
        accentBand.setBackground(OperatorUi.BORDER_STRONG);
        add(accentBand, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        label.setForeground(OperatorUi.TEXT_SUBTLE);
        label.setFont(OperatorUi.titleFont(12f));
        label.setAlignmentX(LEFT_ALIGNMENT);

        value.setForeground(OperatorUi.TEXT_PRIMARY);
        value.setFont(OperatorUi.monoFont(50f, java.awt.Font.BOLD));
        value.setAlignmentX(LEFT_ALIGNMENT);

        unit.setForeground(OperatorUi.TEXT_MUTED);
        unit.setFont(OperatorUi.bodyFont(13f));
        unit.setAlignmentX(LEFT_ALIGNMENT);

        footer.setForeground(OperatorUi.TEXT_MUTED);
        footer.setFont(OperatorUi.bodyFont(11f));
        footer.setHorizontalAlignment(SwingConstants.LEFT);
        footer.setAlignmentX(LEFT_ALIGNMENT);

        content.add(label);
        content.add(Box.createRigidArea(new Dimension(0, 14)));
        content.add(value);
        content.add(Box.createRigidArea(new Dimension(0, 6)));
        content.add(unit);
        content.add(Box.createVerticalGlue());
        content.add(Box.createRigidArea(new Dimension(0, 18)));
        content.add(footer);

        add(content, BorderLayout.CENTER);
    }

    public void render(OperatorViewModel.MetricTileModel model) {
        label.setText(model.getLabel());
        value.setText(model.getValueText());
        unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        footer.setText(model.getFooterText());
        Color tone = OperatorUi.toneColor(model.getTone());
        value.setForeground(tone);
        accentBand.setBackground(OperatorUi.toneBorder(model.getTone()));
        footer.setForeground(OperatorUi.TEXT_SUBTLE);
    }
}
