#version 330
#extension GL_ARB_separate_shader_objects : require

// =====================================================================================================================
// Radio signal effect (radio:gui, radio:burst, radio:active): static noise, vignette and chromatic aberration over
// the 3D view. The three post_effect JSONs use this one shader with different strengths (RadioConfig).
// Minecraft draws menus AFTER post effects, so this never touches a GUI, only the world behind it.
// =====================================================================================================================

const float NOISE_BLOCK_PX = 2.0;       // grain size of the static, screen pixels
const float NOISE_RATE = 30.0;          // how often the static re-rolls, per second
const float LINE_CHANCE = 0.012;        // chance per pixel row and roll of a bright interference line, times Noise
const float VIGNETTE_INNER = 0.35;      // distance from the centre where the vignette starts (0.707 = the corners)
const float ABERRATION_PX = 6.0;        // red/blue split at the screen edge at Aberration 1, screen pixels

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Same block and layout as minecraft:globals.glsl (26.3).
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform RadioConfig {
    float Noise;        // 0..1 static mixed over the picture
    float Vignette;     // 0..1 darkening toward the corners
    float Aberration;   // 0..1 colour fringing toward the edges
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    float t = GameTime * 1200.0;        // seconds
    vec2 px = texCoord * OutSize;
    vec2 fromCentre = texCoord - 0.5;
    float r = length(fromCentre);

    // chromatic aberration: red and blue sampled a little further out / in than green, growing toward the edges
    vec2 split = fromCentre * (ABERRATION_PX * Aberration * r * 2.0) / OutSize;
    vec3 col = vec3(texture(InSampler, texCoord + split).r, texture(InSampler, texCoord).g, texture(InSampler, texCoord - split).b);

    float roll = floor(t * NOISE_RATE);
    float grain = hash12(floor(px / NOISE_BLOCK_PX) + roll * vec2(131.0, 71.0));
    col = mix(col, vec3(grain), Noise * 0.6);
    float line = step(1.0 - LINE_CHANCE * Noise, hash12(vec2(floor(px.y / NOISE_BLOCK_PX), roll)));
    col += line * 0.25;

    col *= 1.0 - Vignette * smoothstep(VIGNETTE_INNER, 0.75, r);
    fragColor = vec4(col, 1.0);
}
