package com.dyno.export;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Saves a JavaFX WritableImage (obtained via Node.snapshot on the FX thread)
 * as a PNG file using AWT ImageIO.
 *
 * The snapshot itself must be taken on the JavaFX Application Thread before calling
 * this method. The pixel copy and file write may be performed on any thread.
 *
 * No javafx-swing dependency is required: pixel data is transferred via
 * WritableImage.getPixelReader().getPixels() into a BufferedImage directly.
 */
public final class DynoPngExporter {
    private DynoPngExporter() {
    }

    public static void write(WritableImage image, Path outputFile) throws IOException {
        if (image == null) {
            throw new IOException("Chart snapshot is null.");
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("Chart snapshot has zero dimensions: " + width + "x" + height);
        }

        // Read all pixels at once via PixelReader (safe off FX thread after snapshot completes)
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(
            0, 0, width, height,
            PixelFormat.getIntArgbInstance(),
            pixels, 0, width
        );

        // Copy into AWT BufferedImage (java.desktop is always available in the JDK)
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);

        boolean wrote = ImageIO.write(buffered, "PNG", outputFile.toFile());
        if (!wrote) {
            throw new IOException("No PNG ImageWriter found. Cannot write: " + outputFile);
        }
    }
}
