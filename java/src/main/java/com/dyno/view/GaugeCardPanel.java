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

public final class GaugeCardPanel extends JPanel {
    private final JPanel band = new JPanel();
    private final JLabel label = new JLabel("Gauge");
    private final JLabel value = new JLabel("—");
    private final JLabel unit = new JLabel("");
    private final JLabel state = new JLabel("UNAVAILABLE");

    public GaugeCardPanel() {
        setLayout(new BorderLayout(0, 14));
        setOpaque(true);
        setBackground(OperatorUi.SURFACE);
        setBorder(OperatorUi.layeredCardBorder(16, 16, 16, 16));

        band.setPreferredSize(new Dimension(0, 5));
        band.setBackground(OperatorUi.BORDER_STRONG);
        add(band, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        label.setForeground(OperatorUi.TEXT_SUBTLE);
        label.setFont(OperatorUi.titleFont(12f));
        label.setAlignmentX(LEFT_ALIGNMENT);

        value.setForeground(OperatorUi.TEXT_PRIMARY);
        value.setFont(OperatorUi.monoFont(36f, java.awt.Font.BOLD));
        value.setAlignmentX(LEFT_ALIGNMENT);

        unit.setForeground(OperatorUi.TEXT_MUTED);
        unit.setFont(OperatorUi.bodyFont(12f));
        unit.setAlignmentX(LEFT_ALIGNMENT);

        state.setOpaque(true);
        state.setHorizontalAlignment(SwingConstants.CENTER);
        state.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        state.setFont(OperatorUi.titleFont(11f));
        state.setAlignmentX(LEFT_ALIGNMENT);

        content.add(label);
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(value);
        content.add(Box.createRigidArea(new Dimension(0, 6)));
        content.add(unit);
        content.add(Box.createVerticalGlue());
        content.add(Box.createRigidArea(new Dimension(0, 18)));
        content.add(state);

        add(content, BorderLayout.CENTER);
    }

    public void render(OperatorViewModel.GaugeModel model) {
        label.setText(model.getLabel());
        value.setText(model.getValueText());
        unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        state.setText(model.getStateText());

        Color tone = OperatorUi.toneColor(model.getTone());
        band.setBackground(tone);
        value.setForeground(tone);
        state.setBackground(OperatorUi.toneBackground(model.getTone()));
        state.setForeground(tone);
        state.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(OperatorUi.toneBorder(model.getTone()), 1, true),
            BorderFactory.createEmptyBorder(7, 12, 7, 12)
        ));
    }
}
