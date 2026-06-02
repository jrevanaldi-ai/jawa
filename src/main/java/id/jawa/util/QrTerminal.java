// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a QR code to a terminal-printable string.
 *
 * <p>Uses Unicode half-block characters so one terminal character pair represents
 * two vertical modules. With a normal monospaced font (~1:2 width:height ratio),
 * modules render roughly square. Total area: {@code (qrSize + 2·quiet)} columns
 * × {@code ⌈qrSize/2⌉ + quiet} rows — fits comfortably in an 80×24 terminal for
 * any QR up to version ~10.
 *
 * <p>Colour: ANSI white background + black foreground are forced regardless of the
 * terminal theme so the scanner always sees high contrast.
 */
public final class QrTerminal {

    private static final String ESC = "";
    /** White background, black foreground. */
    private static final String INK = ESC + "[48;5;255;38;5;232m";
    private static final String RESET = ESC + "[0m";

    private static final char FULL  = '█'; // U+2588 — top dark, bottom dark
    private static final char UPPER = '▀'; // U+2580 — top dark, bottom light
    private static final char LOWER = '▄'; // U+2584 — top light, bottom dark
    private static final char SPACE = ' '; //         top light, bottom light

    private QrTerminal() {}

    /** Render {@code text} as a QR code (1-char-wide modules, half-block height). */
    public static String render(String text) {
        return render(text, 2);
    }

    /**
     * Render with a custom quiet-zone width (in modules).
     * @param quiet modules of light border on each side; 2 is enough for most scanners.
     */
    public static String render(String text, int quiet) {
        final BitMatrix m = encode(text);
        final int w = m.getWidth();
        final int h = m.getHeight();

        StringBuilder sb = new StringBuilder((w + 2 * quiet) * (h / 2 + quiet) * 12);

        // top quiet zone (half-block height = each line is 2 modules tall)
        int topQuietLines = (quiet + 1) / 2;
        for (int i = 0; i < topQuietLines; i++) sb.append(blankLine(w + 2 * quiet));

        for (int y = 0; y < h; y += 2) {
            sb.append(INK);
            // left quiet
            for (int i = 0; i < quiet; i++) sb.append(SPACE);
            for (int x = 0; x < w; x++) {
                boolean top = m.get(x, y);
                boolean bot = (y + 1 < h) && m.get(x, y + 1);
                sb.append(top && bot ? FULL : top ? UPPER : bot ? LOWER : SPACE);
            }
            // right quiet
            for (int i = 0; i < quiet; i++) sb.append(SPACE);
            sb.append(RESET).append('\n');
        }

        // bottom quiet zone (mirror top)
        int bottomQuietLines = (quiet + 1) / 2;
        // if last QR row was a single (top only) the next half-line of QR already added some white;
        // add explicit blank rows for the bottom margin.
        for (int i = 0; i < bottomQuietLines; i++) sb.append(blankLine(w + 2 * quiet));

        return sb.toString();
    }

    private static BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed for input of length " + text.length(), e);
        }
    }

    private static String blankLine(int cols) {
        StringBuilder sb = new StringBuilder(cols + 16);
        sb.append(INK);
        for (int i = 0; i < cols; i++) sb.append(SPACE);
        sb.append(RESET).append('\n');
        return sb.toString();
    }
}
