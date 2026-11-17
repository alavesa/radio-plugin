#ifndef RADIO_TEXT_VERTEX_GLSL
#define RADIO_TEXT_VERTEX_GLSL

// =====================================================================================================================
// Radio: vertex part of the core/text override: moves the radio popup into the screen corner.
//
// The popup is the NAME of a boss bar, written in the font radio:hud. Vanilla draws that name centred at the top
// of the screen. Its total width is 0 by construction (HudText.java), so the pen origin is exactly the screen centre
// and a glyph's distance from the centre is its x inside the popup.
//
// MARKER: text colour #FEFBxx (red 254, green 251). Blue: bits 0-3 = text line, 15 = art (panel, lamp);
// bits 4-6 = palette / art flags (see the fragment part). Only GUI text is looked at (IS_GUI); world text (signs,
// name tags, text displays, maps) is compiled without this code. Does not collide with SuperbVehicles (red 253) or
// CCTV (#0CC7xx). Ordinary GUI text in exactly this colour would be moved too; nothing in vanilla uses it.
//
// Varying location 5: vanilla uses 0-3, SuperbVehicles' HUD uses 4.
// =====================================================================================================================

#ifdef IS_GUI
#include <radio:radio_layout.glsl>
#include <radio:radio_hud_config.glsl>

layout(location = 5) out vec2 radioCorner;  // (0,0) top-left ... (1,1) bottom-right of the glyph quad
#endif

// Call at the END of main(): it replaces gl_Position for popup glyphs and must not be overwritten afterwards.
// color: the vertex colour attribute (it is declared after this file is included, so it is passed in).
void radio_text_vertex(vec4 color) {
#ifdef IS_GUI
    // glyph quads are emitted top-left, bottom-left, bottom-right, top-right (26.3 BakedSheetGlyph.render)
    int corner = gl_VertexIndex % 4;
    vec2 k = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
    radioCorner = k;
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r != 254 || c.g != 251) return;
    int line = c.b & 15;
    vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);          // GUI size in GUI pixels
    // x inside the popup = distance from the screen centre (rounded: the centre is a half pixel on odd widths)
    float x = floor(gl_Position.x / gl_Position.w * 0.5 * gui.x + 0.75);
    float top = line == 15 ? 0.0 : RADIO_LINE_Y[min(line, RADIO_LINES - 1)];
    float height = line == 15 ? RADIO_PANEL.y : RADIO_CELL.y;
    vec2 local = vec2(x, top + k.y * height) * RADIO_HUD_SCALE;
    float left = RADIO_HUD_LEFT > 0.5 ? RADIO_HUD_MARGIN.x : gui.x - RADIO_HUD_MARGIN.x - RADIO_PANEL.x * RADIO_HUD_SCALE;
    vec4 clip = ProjMat * vec4(left + local.x, RADIO_HUD_MARGIN.y + local.y, 0.0, 1.0);
    gl_Position = vec4(clip.xy * gl_Position.w, gl_Position.z, gl_Position.w);
#endif
}

#endif
