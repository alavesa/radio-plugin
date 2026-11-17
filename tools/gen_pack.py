#!/usr/bin/env python3
"""Generates the art, font, item model and sounds of the Radio resource pack into resourcepack/.

    python3 tools/gen_pack.py            # everything (sounds need ffmpeg on PATH)
    python3 tools/gen_pack.py --no-sounds

Pure standard library: PNGs are written by hand, sounds are synthesised as WAV and encoded with ffmpeg.
Everything this script writes is placeholder art that can be replaced file by file (same name, same size); see
README "Replacing the art". The hand-written parts of the pack (pack.mcmeta, post effects, shaders) are not touched.

THE POPUP (fi.alavesa.radio.hud, shaders/include/radio_text_*.glsl). The radio popup is the name of a boss bar,
written in the font radio:hud and moved to the screen corner by the text shader:
- the font is monospace by construction: every cell of hud.png is CELL_W wide and has an alpha-1 pixel in its last
  column where the glyph leaves it empty (the client measures a bitmap glyph up to its last non-transparent column),
  so every character advances CELL_W + 1 px and the plugin can lay text out without knowing glyph shapes;
- signed "space" characters of +-2^n px move the pen anywhere, and every popup ends with the pen back at 0, so its
  total width is 0 and the client's centring puts pen 0 exactly at the screen centre;
- the text colour #FEFBxx marks popup glyphs: xx bits 0-3 = line (15 = art), bits 4-6 = palette / art flags. The
  vertex shader moves marked glyphs from the screen centre to the corner and to their line's row.
Geometry here (PANEL_*, LINE_Y, LAMP) is written to shaders/include/radio_layout.glsl and mirrored in HudText.java.
"""
import json, math, os, random, struct, subprocess, sys, wave, zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'resourcepack')
NS = os.path.join(ROOT, 'assets', 'radio')

# ---- popup geometry, GUI pixels ------------------------------------------------------------------------------------------
CELL_W, CELL_H = 5, 8                # glyph cell; advance = CELL_W + 1
PANEL_W, PANEL_H = 176, 104
TEXT_X = 4                           # left edge of the text columns; 28 columns fit
LINE_Y = [4, 15, 28, 38, 48, 58, 68, 82, 92]    # top of each text line: header, id, 5 message lines, 2 hint lines
SEPARATORS = [25, 79]
LAMP = (150, 3, 22, 9)               # x, y, width, height of the TX/RX lamp in the panel

SPACE_POS, SPACE_NEG, SPACE_BITS = 0xF000, 0xF100, 10
CHAR_PANEL, CHAR_LAMP = 0xE010, 0xE011
GFX_DOT, GFX_ARROW, GFX_BLOCK = 0xE100, 0xE101, 0xE102


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

    def frame(self, x0, y0, x1, y1, c):
        for x in range(x0, x1 + 1):
            self.set(x, y0, c); self.set(x, y1, c)
        for y in range(y0, y1 + 1):
            self.set(x0, y, c); self.set(x1, y, c)

    def glyph(self, x, y, rows, c):
        for dy, row in enumerate(rows):
            for dx, bit in enumerate(row):
                if bit == '#': self.set(x + dx, y + dy, c)

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in self.px)
        def chunk(tag, data):
            return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


# ---- the popup font: 5x7 capitals (the glyph shapes of CCTV's OSD font, plus a few more) -----------------------------------
G = {}
def g(ch, *rows):
    G[ch] = rows
g(' ', *['.....'] * 7)
g('0', '.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.')
g('1', '..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('2', '.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####')
g('3', '#####', '...#.', '..#..', '...#.', '....#', '#...#', '.###.')
g('4', '...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.')
g('5', '#####', '#....', '####.', '....#', '....#', '#...#', '.###.')
g('6', '..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.')
g('7', '#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...')
g('8', '.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.')
g('9', '.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..')
g('A', '.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('B', '####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.')
g('C', '.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.')
g('D', '###..', '#..#.', '#...#', '#...#', '#...#', '#..#.', '###..')
g('E', '#####', '#....', '#....', '####.', '#....', '#....', '#####')
g('F', '#####', '#....', '#....', '####.', '#....', '#....', '#....')
g('G', '.###.', '#...#', '#....', '#.###', '#...#', '#...#', '.####')
g('H', '#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('I', '.###.', '..#..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('J', '..###', '...#.', '...#.', '...#.', '...#.', '#..#.', '.##..')
g('K', '#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#')
g('L', '#....', '#....', '#....', '#....', '#....', '#....', '#####')
g('M', '#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#')
g('N', '#...#', '#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#')
g('O', '.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('P', '####.', '#...#', '#...#', '####.', '#....', '#....', '#....')
g('Q', '.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#')
g('R', '####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#')
g('S', '.####', '#....', '#....', '.###.', '....#', '....#', '####.')
g('T', '#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
g('U', '#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('V', '#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..')
g('W', '#...#', '#...#', '#...#', '#.#.#', '#.#.#', '#.#.#', '.#.#.')
g('X', '#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#')
g('Y', '#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..')
g('Z', '#####', '....#', '...#.', '..#..', '.#...', '#....', '#####')
g('!', '..#..', '..#..', '..#..', '..#..', '..#..', '.....', '..#..')
g('"', '.#.#.', '.#.#.', '.....', '.....', '.....', '.....', '.....')
g('#', '.#.#.', '.#.#.', '#####', '.#.#.', '#####', '.#.#.', '.#.#.')
g('$', '..#..', '.####', '#.#..', '.###.', '..#.#', '####.', '..#..')
g('%', '##...', '##..#', '...#.', '..#..', '.#...', '#..##', '...##')
g('&', '.##..', '#..#.', '#.#..', '.#...', '#.#.#', '#..#.', '.##.#')
g("'", '..#..', '..#..', '.#...', '.....', '.....', '.....', '.....')
g('(', '...#.', '..#..', '.#...', '.#...', '.#...', '..#..', '...#.')
g(')', '.#...', '..#..', '...#.', '...#.', '...#.', '..#..', '.#...')
g('*', '.....', '..#..', '#.#.#', '.###.', '#.#.#', '..#..', '.....')
g('+', '.....', '..#..', '..#..', '#####', '..#..', '..#..', '.....')
g(',', '.....', '.....', '.....', '.....', '.##..', '..#..', '.#...')
g('-', '.....', '.....', '.....', '.###.', '.....', '.....', '.....')
g('.', '.....', '.....', '.....', '.....', '.....', '.##..', '.##..')
g('/', '....#', '....#', '...#.', '..#..', '.#...', '#....', '#....')
g(':', '.....', '.##..', '.##..', '.....', '.##..', '.##..', '.....')
g(';', '.....', '.##..', '.##..', '.....', '.##..', '..#..', '.#...')
g('<', '...#.', '..#..', '.#...', '#....', '.#...', '..#..', '...#.')
g('=', '.....', '.....', '#####', '.....', '#####', '.....', '.....')
g('>', '.#...', '..#..', '...#.', '....#', '...#.', '..#..', '.#...')
g('?', '.###.', '#...#', '....#', '...#.', '..#..', '.....', '..#..')
g('@', '.###.', '#...#', '#.###', '#.#.#', '#.###', '#....', '.###.')
g('[', '.###.', '.#...', '.#...', '.#...', '.#...', '.#...', '.###.')
g('\\', '#....', '#....', '.#...', '..#..', '...#.', '....#', '....#')
g(']', '.###.', '...#.', '...#.', '...#.', '...#.', '...#.', '.###.')
g('^', '..#..', '.#.#.', '#...#', '.....', '.....', '.....', '.....')
g('_', '.....', '.....', '.....', '.....', '.....', '.....', '#####')
g('|', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
g('Ä', '#...#', '.###.', '#...#', '#...#', '#####', '#...#', '#...#')
g('Ö', '#...#', '.###.', '#...#', '#...#', '#...#', '#...#', '.###.')
g('Å', '..#..', '.#.#.', '..#..', '.###.', '#...#', '#####', '#...#')
g('Ü', '#...#', '.....', '#...#', '#...#', '#...#', '#...#', '.###.')
g('É', '...#.', '#####', '#....', '####.', '#....', '#....', '#####')
g(chr(GFX_DOT), '.....', '.###.', '#####', '#####', '#####', '.###.', '.....')
g(chr(GFX_ARROW), '#....', '##...', '###..', '####.', '###..', '##...', '#....')
g(chr(GFX_BLOCK), *['#####'] * 7)

# the characters of the sheet, in cell order; fi.alavesa.radio.hud.HudFont maps everything else onto these
SHEET = [chr(c) for c in range(0x20, 0x60)] + list('ÄÖÅÜÉ') + [chr(GFX_DOT), chr(GFX_ARROW), chr(GFX_BLOCK)]
SHEET_COLS = 16


def font_sheet():
    rows = (len(SHEET) + SHEET_COLS - 1) // SHEET_COLS
    c = Canvas(SHEET_COLS * CELL_W, rows * CELL_H)
    for i, ch in enumerate(SHEET):
        cx, cy = (i % SHEET_COLS) * CELL_W, (i // SHEET_COLS) * CELL_H
        c.glyph(cx, cy, G[ch], (255, 255, 255))
        # width keeper: one alpha-1 pixel in the cell's last column (invisible; the shader and vanilla discard it)
        if all(c.px[cy + y][cx + CELL_W - 1][3] == 0 for y in range(CELL_H)):
            c.set(cx + CELL_W - 1, cy + CELL_H - 1, (0, 0, 0, 1))
    chars = [''.join(SHEET[r * SHEET_COLS:(r + 1) * SHEET_COLS]) for r in range(rows)]
    last = chars[-1]
    chars[-1] = last + ''.join(chr(0xE1F0 + i) for i in range(SHEET_COLS - len(last)))   # filler codepoints, never sent
    return c, chars


FRAME, FRAME_LIGHT, FRAME_DARK = (58, 66, 50), (96, 106, 82), (24, 28, 22)
GLASS = (8, 20, 12, 208)             # translucent: the world shows through a little
HEADER = (16, 36, 22, 232)
RULE = (40, 84, 52, 255)


def panel():
    c = Canvas(PANEL_W, PANEL_H)
    c.rect(0, 0, PANEL_W - 1, PANEL_H - 1, GLASS)
    c.rect(0, 0, PANEL_W - 1, 13, HEADER)
    for y in SEPARATORS:
        for x in range(TEXT_X, PANEL_W - TEXT_X): c.set(x, y, RULE)
    lx, ly, lw, lh = LAMP                                    # dark lamp socket; the lit lamp is its own glyph
    c.rect(lx, ly, lx + lw - 1, ly + lh - 1, (28, 12, 10, 255)); c.frame(lx, ly, lx + lw - 1, ly + lh - 1, FRAME_DARK)
    c.frame(0, 0, PANEL_W - 1, PANEL_H - 1, FRAME_DARK)      # the right-most column is opaque: advance = PANEL_W + 1
    c.frame(1, 1, PANEL_W - 2, PANEL_H - 2, FRAME)
    for x in range(1, PANEL_W - 1): c.set(x, 1, FRAME_LIGHT)
    for y in range(1, PANEL_H - 1): c.set(1, y, FRAME_LIGHT)
    return c


def lamp():
    """The lit lamp, as tall as the panel so both art glyphs share one quad height; drawn where LAMP puts it."""
    lx, ly, lw, lh = LAMP
    c = Canvas(lw, PANEL_H)
    c.rect(0, ly, lw - 1, ly + lh - 1, (214, 44, 34)); c.frame(0, ly, lw - 1, ly + lh - 1, (255, 132, 104))
    return c


RADIO_ITEM_ROWS = [
    '...a............', '...a............', '...a............', '...a............',
    '..bbbbbbbbbb....', '..bggggggggb....', '..bgppppppgb....', '..bgppppppgb....',
    '..bggggggggb....', '..bdodododgb....', '..bodododogb....', '..bdodododgb....',
    '..bggggggrgb....', '..bggggggggb....', '..bbbbbbbbbb....', '................',
]
RADIO_ITEM_PALETTE = {'a': (60, 62, 60), 'b': (18, 20, 16), 'g': FRAME, 'p': (124, 255, 154), 'd': (36, 42, 32),
                      'o': (24, 28, 22), 'r': (214, 44, 34)}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write('\n')


def art():
    sheet, chars = font_sheet()
    sheet.save(os.path.join(NS, 'textures', 'font', 'hud.png'))
    panel().save(os.path.join(NS, 'textures', 'font', 'panel.png'))
    lamp().save(os.path.join(NS, 'textures', 'font', 'lamp.png'))
    advances = {}
    for n in range(SPACE_BITS):
        advances[chr(SPACE_POS + n)] = float(1 << n)
        advances[chr(SPACE_NEG + n)] = -float(1 << n)
    write_json(os.path.join(NS, 'font', 'hud.json'), {'providers': [
        {'type': 'space', 'advances': advances},
        {'type': 'bitmap', 'file': 'radio:font/hud.png', 'height': CELL_H, 'ascent': 7, 'chars': chars},
        {'type': 'bitmap', 'file': 'radio:font/panel.png', 'height': PANEL_H, 'ascent': 7, 'chars': [chr(CHAR_PANEL)]},
        {'type': 'bitmap', 'file': 'radio:font/lamp.png', 'height': PANEL_H, 'ascent': 7, 'chars': [chr(CHAR_LAMP)]},
    ]})
    os.makedirs(os.path.join(NS, 'shaders', 'include'), exist_ok=True)
    with open(os.path.join(NS, 'shaders', 'include', 'radio_layout.glsl'), 'w') as f:
        f.write('// Generated by tools/gen_pack.py: the popup geometry in GUI pixels (also mirrored in HudText.java).\n'
                f'const vec2 RADIO_PANEL = vec2({PANEL_W}.0, {PANEL_H}.0);\n'
                f'const vec2 RADIO_CELL = vec2({CELL_W}.0, {CELL_H}.0);\n'
                f'const int RADIO_LINES = {len(LINE_Y)};\n'
                f'const float RADIO_LINE_Y[{len(LINE_Y)}] = float[{len(LINE_Y)}]({", ".join(f"{y}.0" for y in LINE_Y)});\n'
                f'const vec4 RADIO_LAMP = vec4({", ".join(f"{v}.0" for v in LAMP)});   // x, y, width, height\n')

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
