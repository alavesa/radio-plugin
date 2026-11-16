#ifndef RADIO_TEXT_VERTEX_GLSL
#define RADIO_TEXT_VERTEX_GLSL

// =====================================================================================================================
// Radio: vertex part of the core/text override. It moves nothing: it only tells the fragment shader where inside a
// glyph quad each pixel is, so the radio GUI's animated glyphs can be drawn in glyph-local coordinates.
// Compiled for GUI text only (IS_GUI); world text (signs, name tags, text displays, maps) never contains it.
// Varying location 5: vanilla uses 0-3, SuperbVehicles' HUD uses 4.
// =====================================================================================================================

#ifdef IS_GUI
layout(location = 5) out vec2 radioCorner;  // (0,0) top-left ... (1,1) bottom-right of the glyph quad
#endif

void radio_text_vertex() {
#ifdef IS_GUI
    // glyph quads are emitted top-left, bottom-left, bottom-right, top-right (26.3 BakedSheetGlyph.render)
    int corner = gl_VertexIndex % 4;
    radioCorner = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
#endif
}

#endif
