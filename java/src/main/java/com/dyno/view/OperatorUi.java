package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;
import java.awt.Font;

public final class OperatorUi {
    public static final Color APP_BACKGROUND = new Color(0x0E151D);
    public static final Color SURFACE = new Color(0x16212C);
    public static final Color SURFACE_ALT = new Color(0x1A2734);
    public static final Color SURFACE_SOFT = new Color(0x111A23);
    public static final Color SURFACE_RAISED = new Color(0x203040);
    public static final Color TEXT_PRIMARY = new Color(0xF2F6FA);
    public static final Color TEXT_MUTED = new Color(0x93A3B4);
    public static final Color TEXT_SUBTLE = new Color(0x6E8093);
    public static final Color BORDER = new Color(0x273747);
    public static final Color BORDER_STRONG = new Color(0x314658);
    public static final Color ACCENT = new Color(0x53C4C0);
    public static final Color SUCCESS = new Color(0x5FC482);
    public static final Color WARNING = new Color(0xE8B35A);
    public static final Color ALERT = new Color(0xE56A6A);
    public static final Color UNAVAILABLE = new Color(0x6F8193);
    public static final Color BUTTON_TEXT_DARK = new Color(0x091117);

    private OperatorUi() {
    }

    public static Color toneColor(OperatorViewModel.Tone tone) {
        if (tone == null) {
            return UNAVAILABLE;
        }
        switch (tone) {
            case NORMAL:
                return SUCCESS;
            case CAUTION:
                return WARNING;
            case ALERT:
            case FAULT:
                return ALERT;
            case ACCENT:
                return ACCENT;
            case UNAVAILABLE:
            default:
                return UNAVAILABLE;
        }
    }

    public static Color toneBackground(OperatorViewModel.Tone tone) {
        Color base = toneColor(tone);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 42);
    }

    public static Color toneBorder(OperatorViewModel.Tone tone) {
        Color base = toneColor(tone);
        return new Color(
            Math.min(255, base.getRed() + 12),
            Math.min(255, base.getGreen() + 12),
            Math.min(255, base.getBlue() + 12)
        );
    }

    public static Border cardBorder() {
        return layeredCardBorder(18, 18, 18, 18);
    }

    public static Border layeredCardBorder(int top, int left, int bottom, int right) {
        return new CompoundBorder(
            new CompoundBorder(
                new EmptyBorder(0, 0, 1, 0),
                new LineBorder(BORDER, 1, true)
            ),
            new EmptyBorder(top, left, bottom, right)
        );
    }

    public static Border softInsetBorder() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        );
    }

    public static Font titleFont(float size) {
        return new Font("SansSerif", Font.BOLD, Math.round(size));
    }

    public static Font bodyFont(float size) {
        return new Font("SansSerif", Font.PLAIN, Math.round(size));
    }

    public static Font monoFont(float size, int style) {
        return new Font("Monospaced", style, Math.round(size));
    }

    public static void styleNavButton(JButton button) {
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(12, 22, 12, 22));
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setFont(titleFont(17f));
    }

    public static void styleActionButton(JButton button, Color background, Color foreground) {
        button.setFocusPainted(false);
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(layeredCardBorder(14, 24, 14, 24));
        button.setFont(titleFont(19f));
    }

    public static void styleInputButton(JButton button) {
        styleActionButton(button, SURFACE_ALT, TEXT_PRIMARY);
        button.setBorder(layeredCardBorder(12, 18, 12, 18));
    }
}
