import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Rasterises the radio popup fonts: an OFL typeface (B612 Mono, designed for aircraft displays) into bitmap sheets of
 * fixed cells, plus the small icons of the popup (signal bars, volume segments, arrows, dots).
 *
 *   java tools/GenFont.java tools/fonts resourcepack/assets/radio/textures/font tools/hud_chars.json
 *
 * Output: hud.png (regular) and bold.png (bold), 16 columns of CELL_W x CELL_H cells, antialiased white on transparent.
 * Every cell keeps an alpha-1 pixel in its last column where the glyph leaves it empty, so every character advances
 * CELL_W + 1 px in the client (a bitmap glyph is measured up to its last non-transparent column). The icons are only
 * in hud.png. hud_chars.json lists the sheet rows for tools/gen_pack.py.
 */
public class GenFont {
    static final int CELL_W = 7, CELL_H = 13, COLS = 16, BASELINE = 10, SS = 8;
    // private-use icon characters (mirrored in HudText.java)
    static final int DOT = 0xE100, ARROW = 0xE101, BLOCK = 0xE102, BAR1 = 0xE110, SEG = 0xE120, LEFT = 0xE121, RIGHT = 0xE122, UNREAD = 0xE123, TXMARK = 0xE124, RXMARK = 0xE125;

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path fonts = Path.of(a[0]), out = Path.of(a[1]);
        Files.createDirectories(out);
        java.util.List<Integer> chars = new ArrayList<>();
        for (int c = 0x20; c < 0x7F; c++) chars.add(c);
        for (char c : "äöåÄÖÅüÜéÉèñß°·•€£¡¿".toCharArray()) chars.add((int) c);
        int[] icons = {DOT, ARROW, BLOCK, BAR1, BAR1 + 1, BAR1 + 2, BAR1 + 3, SEG, LEFT, RIGHT, UNREAD, TXMARK, RXMARK};
        for (int i : icons) chars.add(i);
        while (chars.size() % COLS != 0) chars.add(0xE1F0 + (chars.size() % COLS)); // filler, never sent
        for (String[] f : new String[][]{{"B612Mono-Regular.ttf", "hud.png"}, {"B612Mono-Bold.ttf", "bold.png"}}) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve(f[0]).toFile());
            // size the em so one character advance is exactly CELL_W px
            Font probe = font.deriveFont(100f);
            FontMetrics m = new BufferedImage(1, 1, 2).createGraphics().getFontMetrics(probe);
            float em = 100f * CELL_W / m.charWidth('0');
            Font big = font.deriveFont(em * SS);
            int rows = chars.size() / COLS;
            BufferedImage sheet = new BufferedImage(COLS * CELL_W, rows * CELL_H, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < chars.size(); i++) {
                int cx = (i % COLS) * CELL_W, cy = (i / COLS) * CELL_H, ch = chars.get(i);
                float[][] cov = f[1].equals("hud.png") && isIcon(ch, icons) ? icon(ch) : text(big, ch);
                int lastCol = -1;
                for (int y = 0; y < CELL_H; y++) for (int x = 0; x < CELL_W; x++) {
                    float v = cov[y][x];
                    if (v <= 0.004f) continue;
                    int alpha = Math.max(1, Math.min(255, Math.round((float) Math.pow(v, 0.78) * 255)));
                    sheet.setRGB(cx + x, cy + y, (alpha << 24) | 0xFFFFFF);
                    lastCol = Math.max(lastCol, x);
                }
                if (lastCol < CELL_W - 1) sheet.setRGB(cx + CELL_W - 1, cy + CELL_H - 1, (1 << 24) | 0xFFFFFF); // width keeper
            }
            ImageIO.write(sheet, "png", out.resolve(f[1]).toFile());
            System.out.printf("%s: em %.2f px, cap height %.1f px%n", f[1], em, big.createGlyphVector(new java.awt.font.FontRenderContext(null, true, true), "H").getVisualBounds().getHeight() / SS);
        }
        StringBuilder json = new StringBuilder("[\n");
        for (int r = 0; r < chars.size() / COLS; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < COLS; c++) row.append(String.format("\\u%04x", chars.get(r * COLS + c)));
            json.append("  \"").append(row).append("\"").append(r + 1 < chars.size() / COLS ? "," : "").append("\n");
        }
        Files.writeString(Path.of(a[2]), json.append("]\n").toString());
    }

    static boolean isIcon(int ch, int[] icons) { for (int i : icons) if (i == ch) return true; return false; }

    /** Coverage 0..1 of a character drawn at SS x size and box-filtered down to the cell. */
    static float[][] text(Font big, int ch) {
        if (ch == ' ' || ch >= 0xE000) return new float[CELL_H][CELL_W];
        BufferedImage img = new BufferedImage(CELL_W * SS, CELL_H * SS, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(big);
        g.drawString(new String(Character.toChars(ch)), 0, BASELINE * SS);
        g.dispose();
        return down(img);
    }

    static float[][] down(BufferedImage img) {
        float[][] out = new float[CELL_H][CELL_W];
        for (int y = 0; y < CELL_H; y++) for (int x = 0; x < CELL_W; x++) {
            int sum = 0;
            for (int yy = 0; yy < SS; yy++) for (int xx = 0; xx < SS; xx++) sum += img.getRaster().getSample(x * SS + xx, y * SS + yy, 0);
            out[y][x] = sum / (255f * SS * SS);
        }
        return out;
    }

    /** The popup's own little icons, drawn as vector shapes. */
    static float[][] icon(int ch) {
        BufferedImage img = new BufferedImage(CELL_W * SS, CELL_H * SS, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.WHITE);
        double s = SS;
        if (ch == DOT) g.fill(new Ellipse2D.Double(1 * s, 3 * s, 5 * s, 5 * s));
        else if (ch == UNREAD) g.fill(new Ellipse2D.Double(1.5 * s, 3.5 * s, 4 * s, 4 * s));
        else if (ch == ARROW || ch == RIGHT) g.fill(poly(s, 2, 2.5, 2, 9.5, 6, 6));
        else if (ch == LEFT) g.fill(poly(s, 5, 2.5, 5, 9.5, 1, 6));
        else if (ch == BLOCK) g.fillRect(0, 0, img.getWidth(), img.getHeight());
        else if (ch >= BAR1 && ch < BAR1 + 4) {          // signal bar n (1..4): grows to the right, bottom on the baseline
            int n = ch - BAR1 + 1;
            double h = 2 + 2.2 * n;
            g.fill(new RoundRectangle2D.Double(1 * s, (BASELINE + 0.5 - h) * s, 5 * s, h * s, 1 * s, 1 * s));
        } else if (ch == SEG) g.fill(new RoundRectangle2D.Double(1 * s, 3 * s, 5 * s, 6 * s, 1.2 * s, 1.2 * s)); // volume segment
        else if (ch == TXMARK) g.fill(poly(s, 1, 8.5, 6, 8.5, 3.5, 3.5));  // up
        else if (ch == RXMARK) g.fill(poly(s, 1, 3.5, 6, 3.5, 3.5, 8.5));  // down
        g.dispose();
        return down(img);
    }

    static Path2D poly(double s, double... p) {
        Path2D path = new Path2D.Double();
        path.moveTo(p[0] * s, p[1] * s);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i] * s, p[i + 1] * s);
        path.closePath();
        return path;
    }
}
