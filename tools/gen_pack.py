#!/usr/bin/env python3
"""Generates the art, font definitions, item model and sounds of the Radio resource pack into resourcepack/.

    java tools/GenFont.java tools/fonts resourcepack/assets/radio/textures/font tools/hud_chars.json   # the fonts (once)
    python3 tools/gen_pack.py            # panel art, font definitions, layout, item model, sounds (need ffmpeg)
    python3 tools/gen_pack.py --no-sounds

Pure standard library: PNGs are written by hand, sounds are synthesised as WAV and encoded with ffmpeg.
The hand-written parts of the pack (pack.mcmeta, post effects, shaders) are not touched.

THE POPUP (fi.alavesa.radio.hud, shaders/include/radio_text_*.glsl). The radio popup is the name of a boss bar,
written in the fonts radio:hud / radio:bold and moved to the screen corner by the text shader:
- the fonts are bitmap sheets of B612 Mono (SIL OFL, see assets/radio/licenses), rasterised by tools/GenFont.java:
  monospace by construction, every character advances CELL_W + 1 px, so the plugin lays text out without metrics;
- signed "space" characters of +-2^n px move the pen anywhere, and every popup ends with the pen back at 0, so its
  total width is 0 and the client's centring puts pen 0 exactly at the screen centre;
- the text colour #FEFBxx marks popup glyphs: xx bits 0-3 = line (15 = panel/lamp art, 14 = the tab pill), bits 4-6 =
  palette / art flags. The vertex shader moves marked glyphs from the screen centre to the corner and to their row.
Geometry here is written to shaders/include/radio_layout.glsl and mirrored in HudText.java.
"""
import json, math, os, random, struct, subprocess, sys, wave, zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'resourcepack')
NS = os.path.join(ROOT, 'assets', 'radio')
TOOLS = os.path.dirname(os.path.abspath(__file__))

# ---- popup geometry, GUI pixels ------------------------------------------------------------------------------------------
CELL_W, CELL_H = 7, 13               # glyph cell (tools/GenFont.java); advance = CELL_W + 1
PANEL_W, PANEL_H = 208, 138
TEXT_X = 8                           # left edge of the 24 text columns
# text rows: 0 header, 1 tabs, 2..6 messages (5), 7 footer
LINE_Y = [6, 24, 46, 60, 74, 88, 102, 120]
SEPARATORS = [42, 116]
PILL = (46, 17, 20)                  # tab pill: width, height, y
LAMP = (178, 6, 22, 11)              # x, y, width, height of the TX/RX lamp in the header

SPACE_POS, SPACE_NEG, SPACE_BITS = 0xF000, 0xF100, 10
CHAR_PANEL, CHAR_LAMP, CHAR_PILL = 0xE010, 0xE011, 0xE012


# ---- tiny PNG canvas -------------------------------------------------------------------------------------------------
class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c if len(c) == 4 else (*c, 255)

    def rect(self, x0, y0, x1, y1, c):
        """Filled, both corners inclusive."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in self.px)
        def chunk(tag, data):
            return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def rounded(x, y, w, h, r):
    """Signed distance (px, negative inside) of a rounded rectangle at pixel centre (x+.5, y+.5)."""
    px, py = x + 0.5 - w / 2, y + 0.5 - h / 2
    qx, qy = abs(px) - (w / 2 - r), abs(py) - (h / 2 - r)
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r


def coverage(d):
    return max(0.0, min(1.0, 0.5 - d))


# ---- panel art -----------------------------------------------------------------------------------------------------------
BEZEL_OUT, BEZEL_HI, BEZEL_LO = (22, 24, 27), (92, 98, 106), (44, 48, 54)
GLASS_TOP, GLASS_BOTTOM = (13, 18, 26, 238), (7, 10, 15, 244)
HEADER_BAND = (26, 36, 50, 244)
RULE = (52, 66, 84, 255)


def panel():
    """A handheld radio's screen: graphite bezel with a bevel, deep blue-black glass, a header band, thin rules."""
    c = Canvas(PANEL_W, PANEL_H)
    for y in range(PANEL_H):
        for x in range(PANEL_W):
            d = rounded(x, y, PANEL_W, PANEL_H, 9)
            if d > 0.5:
                continue
            outer = coverage(d)
            if d > -1.5:                                   # dark outline
                col = BEZEL_OUT + (255,)
            elif d > -3.5:                                 # bevel: light from the top left
                lit = 1.0 - (x / PANEL_W * 0.55 + y / PANEL_H * 0.45)
                col = mix(BEZEL_LO, BEZEL_HI, max(0.0, min(1.0, lit * 1.4 - 0.2))) + (255,)
            else:
                di = rounded(x - 3, y - 3, PANEL_W - 6, PANEL_H - 6, 6)
                if di > -0.5:                              # inner edge of the glass
                    col = (10, 12, 15, 255)
                else:
                    col = mix(GLASS_TOP, GLASS_BOTTOM, y / PANEL_H)
                    if y < 21:
                        col = mix(col, HEADER_BAND, 0.85)
                    if x % 4 == 0 and y % 4 == 0:           # faint LCD dot grid
                        col = mix(col, (40, 56, 76, col[3]), 0.35)
            c.set(x, y, col[:3] + (int(col[3] * outer),))
    for y in SEPARATORS:
        for x in range(TEXT_X, PANEL_W - TEXT_X):
            c.set(x, y, RULE)
    lx, ly, lw, lh = LAMP                                  # dark lamp socket; the lit lamp is its own glyph
    for y in range(ly, ly + lh):
        for x in range(lx, lx + lw):
            d = rounded(x - lx, y - ly, lw, lh, 3)
            if d < 0.5:
                c.set(x, y, (16, 22, 20, 255) if d < -1 else (60, 72, 70, 255))
    return c


def lamp():
    """The lit lamp, as tall as the panel so both art glyphs share one quad height; drawn where LAMP puts it."""
    lx, ly, lw, lh = LAMP
    c = Canvas(lw, PANEL_H)
    for y in range(lh):
        for x in range(lw):
            d = rounded(x, y, lw, lh, 3)
            if d < 0.5:
                t = max(0.0, 1.0 - math.hypot((x - lw / 2) / (lw / 2), (y - lh / 2) / (lh / 2)))
                c.set(x, ly + y, mix((22, 150, 70), (170, 255, 190), t) + (int(255 * coverage(d)),))
    return c


def pill():
    """The active tab: a rounded key with an amber underline, drawn behind the tab label."""
    w, h, _ = PILL
    c = Canvas(w, h)
    for y in range(h):
        for x in range(w):
            d = rounded(x, y, w, h, 5)
            if d > 0.5:
                continue
            col = mix((54, 66, 86), (30, 38, 52), y / (h - 1))
            if d > -1.2:
                col = mix(col, (112, 128, 152), 0.65 if y < h // 2 else 0.25)
            if y >= h - 3 and d < -0.8 and 5 < x < w - 6:
                col = (255, 186, 60)
            c.set(x, y, col + (int(255 * coverage(d)),))
    return c


RADIO_ITEM_ROWS = [
    '...a............', '...a............', '...a............', '...a............',
    '..bbbbbbbbbb....', '..bggggggggb....', '..bgppppppgb....', '..bgppppppgb....',
    '..bggggggggb....', '..bdodododgb....', '..bodododogb....', '..bdodododgb....',
    '..bggggggrgb....', '..bggggggggb....', '..bbbbbbbbbb....', '................',
]
RADIO_ITEM_PALETTE = {'a': (60, 62, 60), 'b': (18, 20, 16), 'g': (58, 66, 50), 'p': (124, 255, 154), 'd': (36, 42, 32),
                      'o': (24, 28, 22), 'r': (214, 44, 34)}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write('\n')


def font_chars():
    with open(os.path.join(TOOLS, 'hud_chars.json')) as f:
        return json.load(f)


def art():
    advances = {}
    for n in range(SPACE_BITS):
        advances[chr(SPACE_POS + n)] = float(1 << n)
        advances[chr(SPACE_NEG + n)] = -float(1 << n)
    chars = font_chars()
    panel().save(os.path.join(NS, 'textures', 'font', 'panel.png'))
    lamp().save(os.path.join(NS, 'textures', 'font', 'lamp.png'))
    pill().save(os.path.join(NS, 'textures', 'font', 'pill.png'))
    space = {'type': 'space', 'advances': advances}
    sheet = lambda name: {'type': 'bitmap', 'file': f'radio:font/{name}.png', 'height': CELL_H, 'ascent': 10, 'chars': chars}
    art_glyph = lambda name, h, ch: {'type': 'bitmap', 'file': f'radio:font/{name}.png', 'height': h, 'ascent': 10, 'chars': [chr(ch)]}
    write_json(os.path.join(NS, 'font', 'hud.json'), {'providers': [
        space, sheet('hud'), art_glyph('panel', PANEL_H, CHAR_PANEL), art_glyph('lamp', PANEL_H, CHAR_LAMP), art_glyph('pill', PILL[1], CHAR_PILL)]})
    write_json(os.path.join(NS, 'font', 'bold.json'), {'providers': [space, sheet('bold')]})
    os.makedirs(os.path.join(NS, 'shaders', 'include'), exist_ok=True)
    with open(os.path.join(NS, 'shaders', 'include', 'radio_layout.glsl'), 'w') as f:
        f.write('// Generated by tools/gen_pack.py: the popup geometry in GUI pixels (also mirrored in HudText.java).\n'
                f'const vec2 RADIO_PANEL = vec2({PANEL_W}.0, {PANEL_H}.0);\n'
                f'const vec2 RADIO_CELL = vec2({CELL_W}.0, {CELL_H}.0);\n'
                f'const vec3 RADIO_PILL = vec3({PILL[0]}.0, {PILL[1]}.0, {PILL[2]}.0);   // width, height, y\n'
                f'const int RADIO_LINES = {len(LINE_Y)};\n'
                f'const float RADIO_LINE_Y[{len(LINE_Y)}] = float[{len(LINE_Y)}]({", ".join(f"{y}.0" for y in LINE_Y)});\n'
                f'const vec4 RADIO_LAMP = vec4({", ".join(f"{v}.0" for v in LAMP)});   // x, y, width, height\n')
    lic = os.path.join(NS, 'licenses')
    os.makedirs(lic, exist_ok=True)
    with open(os.path.join(TOOLS, 'fonts', 'OFL.txt')) as src, open(os.path.join(lic, 'B612-OFL.txt'), 'w') as dst:
        dst.write(src.read())

    # The popup rides on a BLUE boss bar. Its bar graphic is blanked so only the (moved) name is drawn. No vanilla
    # mob uses a blue bar; another plugin's blue boss bars lose their bar for players with this pack.
    for name in ('blue_background', 'blue_progress'):
        Canvas(182, 5).save(os.path.join(ROOT, 'assets', 'minecraft', 'textures', 'gui', 'sprites', 'boss_bar', name + '.png'))

    item = Canvas(16, 16)
    for y, row in enumerate(RADIO_ITEM_ROWS):
        for x, ch in enumerate(row):
            if ch in RADIO_ITEM_PALETTE: item.set(x, y, RADIO_ITEM_PALETTE[ch])
    item.save(os.path.join(NS, 'textures', 'item', 'radio.png'))
    write_json(os.path.join(NS, 'models', 'item', 'radio.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'radio:item/radio'}})
    write_json(os.path.join(NS, 'items', 'radio.json'), {'model': {'type': 'minecraft:model', 'model': 'radio:item/radio'}})


# ---- sounds ----------------------------------------------------------------------------------------------------------
RATE = 44100


def env(t, attack, hold, release):
    if t < 0: return 0.0
    if t < attack: return t / attack
    if t < attack + hold: return 1.0
    return max(0.0, 1.0 - (t - attack - hold) / release)


def speaker(samples, low=350.0, high=3200.0, drive=2.2):
    """A small radio speaker: band-limited (one-pole high-pass and low-pass) and a little overdriven."""
    out, hp, lp, prev = [], 0.0, 0.0, 0.0
    a_hp = 1.0 / (1.0 + 2 * math.pi * low / RATE)
    a_lp = (2 * math.pi * high / RATE) / (1.0 + 2 * math.pi * high / RATE)
    for s in samples:
        hp = a_hp * (hp + s - prev); prev = s
        lp += a_lp * (hp - lp)
        out.append(math.tanh(lp * drive) / math.tanh(drive))
    return out


def sounds():
    rnd = random.Random(3)
    noise = lambda: rnd.uniform(-1, 1)
    tone = lambda f, t: math.sin(2 * math.pi * f * t)
    def synth(seconds, fn):
        return [fn(i / RATE) for i in range(int(seconds * RATE))]
    def squelch(t, start, length):
        """The burst of hiss when a carrier drops: loud at once, a quick fall, a hard cut."""
        u = t - start
        return noise() * (1.0 - 0.75 * u / length) if 0 <= u < length else 0.0

    clips = {
        # transmission starts: the key-up thump, then the squelch opening
        'in': speaker(synth(0.22, lambda t: 0.9 * tone(140, t) * env(t, 0.001, 0.006, 0.02)
                            + 0.8 * noise() * env(t - 0.012, 0.003, 0.05, 0.14))),
        # "over": the roger beep (two quick tones and a longer lower one), a breath of carrier, then the squelch tail
        'out': speaker(synth(0.62, lambda t: 0.75 * tone(1480, t) * env(t, 0.003, 0.050, 0.008)
                             + 0.75 * tone(1480, t) * env(t - 0.085, 0.003, 0.050, 0.008)
                             + 0.80 * tone(1110, t) * env(t - 0.170, 0.003, 0.130, 0.012)
                             + 0.10 * noise() * env(t, 0.01, 0.32, 0.01)
                             + 0.85 * squelch(t, 0.345, 0.20)
                             + 0.9 * tone(120, t) * env(t - 0.545, 0.001, 0.008, 0.03))),
        'power_on': speaker(synth(0.34, lambda t: 0.9 * tone(160, t) * env(t, 0.001, 0.006, 0.02)
                                  + 0.5 * tone(700 + 900 * min(1.0, t / 0.2), t) * env(t - 0.03, 0.01, 0.12, 0.06)
                                  + 0.5 * noise() * env(t - 0.02, 0.01, 0.10, 0.20))),
        'power_off': speaker(synth(0.26, lambda t: 0.5 * tone(1300 - 800 * min(1.0, t / 0.18), t) * env(t, 0.005, 0.10, 0.06)
                                   + 0.7 * squelch(t, 0.09, 0.09) + 0.9 * tone(120, t) * env(t - 0.18, 0.001, 0.008, 0.04))),
        'switch': speaker(synth(0.12, lambda t: 0.9 * tone(180, t) * env(t, 0.001, 0.004, 0.012)
                                + 0.5 * noise() * env(t - 0.01, 0.002, 0.02, 0.05) + 0.3 * tone(1700, t) * env(t - 0.02, 0.002, 0.02, 0.03))),
    }
    out = os.path.join(NS, 'sounds', 'radio')
    os.makedirs(out, exist_ok=True)
    for name, samples in clips.items():
        wav = os.path.join(out, name + '.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b''.join(struct.pack('<h', int(max(-1.0, min(1.0, s)) * 28000)) for s in samples))
        # Minecraft only loads Ogg Vorbis. ffmpeg's built-in "vorbis" encoder is used because it is in every ffmpeg build
        # (libvorbis often is not); it only writes stereo, which suits these sounds: Minecraft plays stereo files
        # without positioning, and each of them is played to one listener only. -bitexact keeps the bytes stable.
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'vorbis', '-strict', '-2', '-ac', '2', '-q:a', '5',
                        '-map_metadata', '-1', '-bitexact', os.path.join(out, name + '.ogg')], check=True)
        os.remove(wav)
    write_json(os.path.join(NS, 'sounds.json'), {
        f'radio.{name}': {'sounds': [{'name': f'radio:radio/{name}'}], 'subtitle': f'subtitles.radio.{name}'} for name in clips})
    write_json(os.path.join(NS, 'lang', 'en_us.json'), {
        'subtitles.radio.in': 'Radio squelch', 'subtitles.radio.out': 'Radio: over', 'subtitles.radio.power_on': 'Radio switched on',
        'subtitles.radio.power_off': 'Radio switched off', 'subtitles.radio.switch': 'Radio dial clicks'})


if __name__ == '__main__':
    art()
    if '--no-sounds' not in sys.argv: sounds()
    print('generated into', os.path.normpath(ROOT))
