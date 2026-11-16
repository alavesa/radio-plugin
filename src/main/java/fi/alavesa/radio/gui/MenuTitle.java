package fi.alavesa.radio.gui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The title of the transceiver GUI. A chest title is ordinary text drawn at (8, 6) inside the 176x222 container; the
 * font radio:gui (tools/gen_pack.py) has the panel art as one large glyph plus "space" characters with negative
 * advances, so the art is drawn first, the cursor is walked back and the display text is written on top of it.
 *
 * <p>All positions here are in container pixels and must match gen_pack.py (GLYPHS / PANEL_*). A bitmap glyph
 * advances the cursor by its drawn width + 1.
 */
public final class MenuTitle {

    public static final Key FONT = Key.key("radio", "gui");

    /** The whole transceiver: 208 x 256, drawn from 16 px left of and 34 px above the container. */
    private static final char PANEL = '\uE010';
    /** The signal window, the lit ON AIR lamp, the lit power LED and the display glass (the panel has them dark). */
    private static final char SCOPE = '\uE011', ON_AIR = '\uE012', LED = '\uE013', LCD_GLASS = '\uE014';
    /** SPACE_NEG + i advances -(2^i), SPACE_POS + i advances +(2^i), i = 0..9. */
    private static final char SPACE_NEG = '\uE200', SPACE_POS = '\uE210';

    private static final int TITLE_X = 8;
    private static final int PANEL_X = -16, PANEL_W = 208;
    private static final int LED_X = 24, LED_W = 6;
    private static final int SCOPE_X = 42, SCOPE_W = 90;
    private static final int ON_AIR_X = 140, ON_AIR_W = 34;
    private static final int LCD_X = 5, LCD_W = 166;

    /**
     * Marker for the text shader (radio_text_fragment.glsl): text colour #FEFCxx with xx = 255 - kind. It is almost
     * white, so where the shader is not running the glyph is drawn as its still picture, untinted.
     */
    private static final int KIND_SCOPE = 1, KIND_SCOPE_ON_AIR = 2, KIND_LAMP = 3, KIND_DISPLAY = 4, KIND_DISPLAY_ON_AIR = 5;

    /** The colour of the display text: LCD green. */
    private static final TextColor LCD = TextColor.color(0x9CFFB0);

    private MenuTitle() {
    }

    /**
     * @param power   the radio is on: LED, waveform and display text are shown
     * @param onAir   a transmission is going through the tuned channel right now
     * @param display the text of the display line (plain; it is truncated to fit)
     */
    public static Component build(boolean power, boolean onAir, String display) {
        TextComponent.Builder title = Component.text();
        int[] cursor = {TITLE_X}; // container x of the pen
        // white: a container title is dark grey by default, which would darken the art. No shadow on any of it.
        glyph(title, cursor, PANEL_X, PANEL, PANEL_W, NamedTextColor.WHITE);
        if (power) {
            glyph(title, cursor, LED_X, LED, LED_W, marker(KIND_LAMP));
            glyph(title, cursor, SCOPE_X, SCOPE, SCOPE_W, marker(onAir ? KIND_SCOPE_ON_AIR : KIND_SCOPE));
            if (onAir) glyph(title, cursor, ON_AIR_X, ON_AIR, ON_AIR_W, marker(KIND_LAMP));
            glyph(title, cursor, LCD_X, LCD_GLASS, LCD_W, marker(onAir ? KIND_DISPLAY_ON_AIR : KIND_DISPLAY));
        }
        StringBuilder back = new StringBuilder();
        shift(back, TITLE_X - cursor[0]);
        title.append(Component.text(back.toString()).font(FONT));
        if (power && !display.isEmpty()) {
            String text = display.length() > 26 ? display.substring(0, 25) + "…" : display;
            title.append(Component.text(text, LCD).shadowColor(ShadowColor.none()));
        }
        return title.build();
    }

    private static TextColor marker(int kind) {
        return TextColor.color(254, 252, 255 - kind);
    }

    /** Appends the pen move to container x and the glyph, in its own colour. */
    private static void glyph(TextComponent.Builder title, int[] cursor, int x, char glyph, int width, TextColor color) {
        StringBuilder s = new StringBuilder();
        shift(s, x - cursor[0]);
        s.append(glyph);
        title.append(Component.text(s.toString(), color).font(FONT).shadowColor(ShadowColor.none()));
        cursor[0] = x + width + 1;
    }

    /** Moves the pen by {@code px} pixels (either direction) with the power-of-two space characters. */
    private static void shift(StringBuilder out, int px) {
        char base = px < 0 ? SPACE_NEG : SPACE_POS;
        int n = Math.abs(px);
        for (int bit = 9; bit >= 0; bit--) if ((n & (1 << bit)) != 0) out.append((char) (base + bit));
    }
}
