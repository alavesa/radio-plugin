#ifndef RADIO_TEXT_FRAGMENT_GLSL
#define RADIO_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// Radio: fragment part of the core/text override: colours and animates the radio popup.
//
// Acts only on GUI text (IS_GUI) whose colour is the marker #FEFBxx (see radio_text_vertex.glsl); everything else
// gets back exactly the colour computed before this call.
//   text lines (blue bits 0-3 = 0..13): the glyph is drawn in palette colour (blue bits 4-6)
//   tab pill (14): drawn as it is, with a slow glow
//   art (15): bits 4-6 = 0 panel | 1 panel while a transmission comes in (static) | 2 lit lamp (pulse)
// =====================================================================================================================

#include <minecraft:globals.glsl>

const float RADIO_SCAN_DARK = 0.93;         // brightness of every other panel row (light: the text must stay easy to read)
const float RADIO_ROLL_SPEED = 0.16;        // rolling bright bar, passes per second
const float RADIO_ROLL_GAIN = 0.05;
const float RADIO_STATIC = 0.22;            // static mixed into the glass while a transmission comes in
const float RADIO_LAMP_HZ = 2.0;            // lamp pulses per second
const float RADIO_LAMP_DEPTH = 0.35;        // brightness lost at the low point of a pulse
const float RADIO_TEXT_FLICKER = 0.025;     // max brightness the text loses in a flicker step
const float RADIO_FLICKER_HZ = 11.0;

#ifdef IS_GUI
#include <radio:radio_layout.glsl>

layout(location = 5) in vec2 radioCorner;

float radio_hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 radio_palette(int i) {
    if (i == 0) return vec3(0.94, 0.96, 0.99);  // white
    if (i == 1) return vec3(1.00, 0.73, 0.24);  // amber
    if (i == 2) return vec3(0.45, 0.88, 1.00);  // cyan
    if (i == 3) return vec3(0.62, 0.67, 0.74);  // grey
    if (i == 4) return vec3(0.36, 0.41, 0.49);  // dim
    if (i == 5) return vec3(0.42, 1.00, 0.58);  // green
    if (i == 6) return vec3(1.00, 0.38, 0.34);  // red
    return vec3(1.00, 0.93, 0.45);              // yellow
}
#endif

vec4 radio_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(vertColor.rgb * 255.0));
    if (c.r != 254 || c.g != 251) return color;
    int line = c.b & 15;
    int data = (c.b >> 4) & 7;
    float t = GameTime * 1200.0;   // seconds
    if (line == 14) {
        float glow = 0.5 + 0.5 * sin(t * 2.4);
        return vec4(texColor.rgb * (0.94 + 0.10 * glow), texColor.a * vertColor.a) * colorModulator;
    }
    if (line != 15) {
        float flicker = 1.0 - RADIO_TEXT_FLICKER * radio_hash(vec2(floor(t * RADIO_FLICKER_HZ), 7.0));
        return vec4(radio_palette(data) * flicker, texColor.a * vertColor.a) * colorModulator;
    }
    vec2 p = floor(radioCorner * RADIO_PANEL);          // art pixel (both art glyphs are as tall as the panel)
    vec3 rgb = texColor.rgb;
    if (data == 2) {
        float pulse = 0.5 + 0.5 * sin(t * RADIO_LAMP_HZ * 6.2831853);
        rgb = rgb * (1.0 - RADIO_LAMP_DEPTH * (1.0 - pulse)) + vec3(0.10) * pulse;
    } else if (texColor.a < 0.99) {
        // the translucent glass (the frame and rules are opaque and stay as drawn)
        if (mod(p.y, 2.0) < 1.0) rgb *= RADIO_SCAN_DARK;
        float roll = fract(t * RADIO_ROLL_SPEED) * (RADIO_PANEL.y + 16.0) - 8.0;
        rgb += vec3(0.45, 0.80, 1.00) * RADIO_ROLL_GAIN * smoothstep(6.0, 0.0, abs(p.y - roll));
        if (data == 1) rgb = mix(rgb, vec3(0.55, 0.85, 1.00) * radio_hash(p + floor(t * 30.0) * vec2(17.0, 31.0)), RADIO_STATIC);
    }
    return vec4(rgb, texColor.a * vertColor.a) * colorModulator;
#else
    return color;
#endif
}

#endif
