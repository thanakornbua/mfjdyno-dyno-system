package com.dyno.fx;

import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Provides Thai-aware font selection for JavaFX UI components.
 *
 * When Thai language is active, uses Sarabun font which has complete
 * Unicode Thai support (U+0E00–U+0E7F) with proper glyph shaping for
 * combining marks and accents. Falls back to SansSerif for English.
 */
public final class ThaiAwareFont {
    private static final String THAI_FONT = "Sarabun";
    private static final String FALLBACK_FONT = "SansSerif";

    private ThaiAwareFont() {
    }

    /**
     * Returns an appropriate font for the current language.
     * Uses Sarabun for Thai, SansSerif for English.
     */
    public static Font font(FontWeight weight, double size) {
        String fontFamily = UiText.isThai() ? THAI_FONT : FALLBACK_FONT;
        return Font.font(fontFamily, weight, size);
    }

    /**
     * Returns an appropriate font for the current language (normal weight).
     */
    public static Font font(double size) {
        String fontFamily = UiText.isThai() ? THAI_FONT : FALLBACK_FONT;
        return Font.font(fontFamily, size);
    }

    /**
     * Returns the appropriate monospaced font.
     * Sarabun does not have monospaced variants, so this always uses Monospaced
     * for numeric display. For Thai labels, use the regular font() method instead.
     */
    public static Font monospaced(FontWeight weight, double size) {
        return Font.font("Monospaced", weight, size);
    }
}
