#ifndef RADIO_TEXT_FRAGMENT_GLSL
#define RADIO_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// Radio: fragment part of the core/text override: the animated parts of the transceiver GUI.
//
// MARKER. All three gates must pass, otherwise the colour computed before this call is returned untouched:
//   1. IS_GUI is defined (GUI text only; world text never qualifies),
//   2. the text colour is #FEFCxx with xx = 255 - kind, kind 1..7 (MenuTitle.java),
//   3. the glyph texel's alpha is exactly 253. Only the radio's marked font sheets use that value (vanilla fonts
//      use 0 and 255, CCTV's 254), so ordinary text typed in exactly that colour stays ordinary text.
// The marker colour is almost white on purpose: when this shader is NOT the one running (another pack's core/text
// won), the marked glyphs are simply drawn as their still pictures.
// Does not collide with SuperbVehicles (red 253), CCTV (#0CC7xx, alpha 254) or Lighting (item shader, 217,41,..).
//
// kind 1 scope            the signal window: moving waveform
// kind 2 scope, on air    larger, noisier waveform while a transmission goes through
// kind 3 lamp             ON AIR lamp and power LED: breathing glow
// kind 4 display          the display line's glass under the text: CRT scanlines, slow roll, flicker
// kind 5 display, on air  the same with static noise
// =====================================================================================================================

#include <minecraft:globals.glsl>
#include <radio:radio_layout.glsl>

const vec3  RADIO_PHOSPHOR = vec3(0.49, 1.00, 0.60);   // trace and display colour
const vec3  RADIO_GLASS = vec3(0.031, 0.078, 0.047);   // dark glass, the same as the panel art
const float RADIO_WAVE_SPEED = 7.0;         // how fast the waveform runs, radians per second
const float RADIO_WAVE_GAIN = 0.62;         // idle amplitude, 1 = the full window height
const float RADIO_WAVE_GAIN_ON_AIR = 0.95;
const float RADIO_GLOW = 0.45;              // brightness of the pixel rows next to the trace
const float RADIO_LAMP_HZ = 1.6;            // lamp pulses per second
const float RADIO_LAMP_DEPTH = 0.30;        // how much brightness a lamp loses at the low point of a pulse
const vec3  RADIO_DISPLAY_LIT = vec3(0.055, 0.170, 0.095); // the display's backlit glass (bright scanline rows)
const float RADIO_SCAN_DARK = 0.50;         // brightness of every other display row
const float RADIO_ROLL_SPEED = 0.35;        // display roll bar, passes per second
const float RADIO_FLICKER_HZ = 12.0;
const float RADIO_FLICKER = 0.10;           // max brightness lost in a flicker step
const float RADIO_STATIC = 0.45;            // static mixed into the display while on air

#ifdef IS_GUI
layout(location = 5) in vec2 radioCorner;

float radio_hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// The glyph pixel under this fragment (pixel centres), for a picture of size.xy at the top of a PNG size.z tall.
vec2 radio_pixel(vec3 size) {
    return floor(vec2(radioCorner.x * size.x, radioCorner.y * size.z)) + 0.5;
}

vec3 radio_scope(float t, bool onAir) {
    vec2 p = radio_pixel(RADIO_SCOPE);
    float w = RADIO_SCOPE.x, h = RADIO_SCOPE.y;
    vec3 col = RADIO_GLASS;
    if (mod(p.x, 10.0) > 9.0 && mod(p.y, 2.0) < 1.0) col = vec3(0.055, 0.133, 0.078);   // graticule
    float envelope = 0.35 + 0.65 * pow(sin(3.14159265 * p.x / w), 2.0);
    float gain = onAir ? RADIO_WAVE_GAIN_ON_AIR : RADIO_WAVE_GAIN;
    float speed = onAir ? RADIO_WAVE_SPEED * 1.8 : RADIO_WAVE_SPEED;
    float wave = sin(p.x * 0.55 - t * speed) * cos(p.x * 0.13 + t * 1.3);
    if (onAir) wave = clamp(wave + (radio_hash(vec2(p.x, floor(t * 30.0))) - 0.5) * 0.9, -1.0, 1.0);
    float y = floor(h * 0.5 + envelope * gain * (h * 0.5 - 1.0) * wave) + 0.5;
    float d = abs(p.y - y);
    if (d < 0.5) col = RADIO_PHOSPHOR;
    else if (d < 1.5) col = mix(col, RADIO_PHOSPHOR, RADIO_GLOW);
    return col;
}

vec3 radio_display(float t, bool onAir) {
    vec2 p = radio_pixel(RADIO_LCD);
    vec3 col = RADIO_DISPLAY_LIT;
    if (mod(p.y, 2.0) < 1.0) col *= RADIO_SCAN_DARK;
    float roll = fract(t * RADIO_ROLL_SPEED) * (RADIO_LCD.y + 6.0) - 3.0;
    col += RADIO_PHOSPHOR * 0.16 * smoothstep(2.5, 0.0, abs(p.y - roll));
    col *= 1.0 - RADIO_FLICKER * radio_hash(vec2(floor(t * RADIO_FLICKER_HZ), 3.0));
    if (onAir) col = mix(col, RADIO_PHOSPHOR * radio_hash(p + floor(t * 30.0) * vec2(17.0, 31.0)), RADIO_STATIC);
    return col;
}
#endif

vec4 radio_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(vertColor.rgb * 255.0));
    if (c.r != 254 || c.g != 252 || c.b < 248 || c.b > 254) return color;
    if (abs(texColor.a * 255.0 - 253.0) > 0.5) return color;
    int kind = 255 - c.b;
    float t = GameTime * 1200.0;   // seconds
    vec3 rgb;
    if (kind == 1 || kind == 2) {
        rgb = radio_scope(t, kind == 2);
    } else if (kind == 3) {
        float pulse = 0.5 + 0.5 * sin(t * RADIO_LAMP_HZ * 6.2831853);
        rgb = texColor.rgb * (1.0 - RADIO_LAMP_DEPTH * (1.0 - pulse)) + vec3(0.12) * pulse;
    } else if (kind == 4 || kind == 5) {
        rgb = radio_display(t, kind == 5);
    } else {
        return color;
    }
    return vec4(rgb, vertColor.a) * colorModulator;
#else
    return color;
#endif
}

#endif
