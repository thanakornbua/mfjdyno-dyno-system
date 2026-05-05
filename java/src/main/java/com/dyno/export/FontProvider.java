package com.dyno.export;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves and loads the Sarabun Thai font for PDF generation.
 *
 * The font is embedded with IDENTITY_H encoding, which is required for
 * correct Unicode/Thai glyph rendering in iText 7.
 *
 * Search order follows the project structure where the app is launched from
 * the java/ subdirectory (e.g. via Gradle runOperatorConsoleFx).
 */
public final class FontProvider {
    private static final String[] FONT_CANDIDATES = {
        "../app/dashboard/fonts/Sarabun-Regular.ttf",
        "app/dashboard/fonts/Sarabun-Regular.ttf",
        "fonts/Sarabun-Regular.ttf"
    };

    private FontProvider() {
    }

    /**
     * Creates an iText 7 PdfFont backed by the Sarabun TTF, embedded with
     * IDENTITY_H encoding for Thai + Latin shaping.
     *
     * @throws IOException if the font file cannot be located
     */
    public static PdfFont loadSarabunFont() throws IOException {
        Path fontPath = locateFont();
        return PdfFontFactory.createFont(
            fontPath.toString(),
            PdfEncodings.IDENTITY_H,
            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED
        );
    }

    /** Returns the resolved absolute path to Sarabun-Regular.ttf. */
    public static Path locateFont() throws IOException {
        for (String candidate : FONT_CANDIDATES) {
            Path path = Paths.get(candidate).normalize().toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        StringBuilder candidates = new StringBuilder();
        for (int i = 0; i < FONT_CANDIDATES.length; i++) {
            if (i > 0) {
                candidates.append(", ");
            }
            candidates.append(Paths.get(FONT_CANDIDATES[i]).normalize().toAbsolutePath());
        }
        throw new IOException(
            "Sarabun-Regular.ttf not found. Searched: " + candidates
        );
    }
}
