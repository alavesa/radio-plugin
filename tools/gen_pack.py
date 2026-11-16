#!/usr/bin/env python3
"""Generates the art, font, item models and sounds of the Radio resource pack into resourcepack/.

    python3 tools/gen_pack.py            # everything (sounds need ffmpeg on PATH)
    python3 tools/gen_pack.py --no-sounds

Pure standard library: PNGs are written by hand, sounds are synthesised as WAV and encoded with ffmpeg.
Everything this script writes is placeholder art that can be replaced file by file (same name, same size); see
README "Replacing the art". The hand-written parts of the pack (pack.mcmeta, post effects, shaders) are not touched.

Geometry shared with fi.alavesa.radio.gui.MenuTitle (keep both in step):
  the chest container is 176x222 and its title is drawn at (8, 6); a bitmap glyph's top edge lands at
  container y = 6 + 7 - ascent, and a glyph advances the pen by its drawn width + 1.
"""
import json, math, os, random, struct, subprocess, sys, wave, zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'resourcepack')
NS = os.path.join(ROOT, 'assets', 'radio')

# ---- geometry (container pixels unless noted) ------------------------------------------------------------------------
PANEL_W, PANEL_H = 208, 256          # the panel glyph
PANEL_X, PANEL_Y = -16, -34          # where its top-left corner sits relative to the container
GLYPHS = {                           # name: (char, container x, container y, width, height)
    'panel':  (0xE010, PANEL_X, PANEL_Y, PANEL_W, PANEL_H),
    'scope':  (0xE011, 42, -22, 90, 16),
    'on_air': (0xE012, 140, -21, 34, 14),
    'led':    (0xE013, 24, -18, 6, 6),
    'lcd':    (0xE014, 5, 4, 166, 11),      # the display line's glass, drawn again under the text (see MARKER below)
}
# MARKER: the text shader (shaders/include/radio_text_*.glsl) animates glyphs whose texels have alpha 253 and whose
# text colour is #FEFCxx. Every drawn pixel of the glyphs below gets alpha 253; without the shader they look the same.
SHADER_GLYPHS = ('scope', 'on_air', 'led', 'lcd')
SHADER_ALPHA = 253
SPACE_NEG, SPACE_POS, SPACE_BITS = 0xE200, 0xE210, 10


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

    def bevel(self, x0, y0, x1, y1, light, dark):
        """A raised edge: light on top/left, dark on bottom/right (swap the colours for a sunken one)."""
        for x in range(x0, x1 + 1):
            self.set(x, y0, light); self.set(x, y1, dark)
        for y in range(y0, y1 + 1):
            self.set(x0, y, light); self.set(x1, y, dark)

    def text(self, x, y, s, c):
        for ch in s:
            rows = FONT.get(ch, FONT[' '])
            for dy, row in enumerate(rows):
                for dx, bit in enumerate(row):
                    if bit == '#': self.set(x + dx, y + dy, c)
            x += len(rows[0]) + 1

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in self.px)
        def chunk(tag, data):
            return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0))
                    + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


FONT = {  # 3x5 capitals for the lamp and plate labels
    ' ': ['...', '...', '...', '...', '...'], 'A': ['.#.', '#.#', '###', '#.#', '#.#'],
    'C': ['.##', '#..', '#..', '#..', '.##'], 'E': ['###', '#..', '##.', '#..', '###'],
    'H': ['#.#', '#.#', '###', '#.#', '#.#'], 'I': ['###', '.#.', '.#.', '.#.', '###'],
    'L': ['#..', '#..', '#..', '#..', '###'], 'N': ['#.#', '###', '###', '#.#', '#.#'],
    'O': ['.#.', '#.#', '#.#', '#.#', '.#.'], 'P': ['##.', '#.#', '##.', '#..', '#..'],
    'R': ['##.', '#.#', '##.', '#.#', '#.#'], 'S': ['.##', '#..', '.#.', '..#', '##.'],
    'W': ['#.#', '#.#', '###', '###', '#.#'],
}

BODY, BODY_LIGHT, BODY_DARK, EDGE = (58, 66, 50), (84, 94, 72), (36, 42, 32), (18, 20, 16)
PLATE, PLATE_LIGHT, PLATE_DARK = (150, 154, 132), (184, 188, 166), (104, 108, 90)
GLASS, GLASS_EDGE = (8, 20, 12), (4, 8, 6)
WELL, WELL_EDGE = (30, 34, 28), (16, 18, 14)
PHOSPHOR = (124, 255, 154)


def panel():
    c = Canvas(PANEL_W, PANEL_H)
    ox, oy = -PANEL_X, -PANEL_Y      # container (0,0) in panel pixels
    rnd = random.Random(7)
    # antenna
    c.rect(29, 0, 35, 12, EDGE); c.rect(30, 1, 34, 12, (40, 42, 40)); c.rect(31, 1, 32, 12, (70, 72, 70))
    # body, full glyph width (the right-most column must be opaque: MenuTitle counts on advance = width + 1)
    c.rect(0, 10, PANEL_W - 1, PANEL_H - 1, BODY)
    for _ in range(2600):            # a little grain so the plastic is not flat
        x, y = rnd.randrange(PANEL_W), rnd.randrange(10, PANEL_H)
        d = rnd.choice((-5, -3, 3, 5))
        c.set(x, y, tuple(max(0, min(255, v + d)) for v in BODY))
    c.bevel(1, 11, PANEL_W - 2, PANEL_H - 2, BODY_LIGHT, BODY_DARK)
    c.frame(0, 10, PANEL_W - 1, PANEL_H - 1, EDGE)
    for sx, sy in ((6, 16), (PANEL_W - 9, 16), (6, PANEL_H - 9), (PANEL_W - 9, PANEL_H - 9)):   # screws
        c.rect(sx, sy, sx + 2, sy + 2, BODY_DARK); c.set(sx + 1, sy + 1, BODY_LIGHT)
    # head: LED, scope window, ON AIR lamp (all dark here; the lit versions are separate glyphs)
    _, lx, ly, lw, lh = GLYPHS['led']
    c.rect(ox + lx - 1, oy + ly - 1, ox + lx + lw, oy + ly + lh, EDGE); c.rect(ox + lx, oy + ly, ox + lx + lw - 1, oy + ly + lh - 1, (50, 16, 14))
    c.text(ox + lx - 3, oy + ly + 9, 'PWR', BODY_LIGHT)
    _, sx, sy, sw, sh = GLYPHS['scope']
    c.rect(ox + sx - 2, oy + sy - 2, ox + sx + sw + 1, oy + sy + sh + 1, EDGE)
    c.bevel(ox + sx - 2, oy + sy - 2, ox + sx + sw + 1, oy + sy + sh + 1, BODY_DARK, BODY_LIGHT)
    c.rect(ox + sx, oy + sy, ox + sx + sw - 1, oy + sy + sh - 1, GLASS)
    for gx in range(ox + sx + 9, ox + sx + sw, 10):                # faint graticule
        for gy in range(oy + sy, oy + sy + sh, 2): c.set(gx, gy, (14, 34, 20))
    _, ax, ay, aw, ah = GLYPHS['on_air']
    c.rect(ox + ax - 1, oy + ay - 1, ox + ax + aw, oy + ay + ah, EDGE); c.rect(ox + ax, oy + ay, ox + ax + aw - 1, oy + ay + ah - 1, (44, 14, 12))
    c.text(ox + ax + 4, oy + ay + 5, 'ON AIR', (86, 30, 26))
    # display line behind the chest title
    c.rect(ox + 4, oy + 3, ox + 171, oy + 15, GLASS_EDGE); c.rect(ox + 5, oy + 4, ox + 170, oy + 14, GLASS)
    c.bevel(ox + 3, oy + 2, ox + 172, oy + 16, BODY_DARK, BODY_LIGHT)
    # the 54 chest slots
    for row in range(6):
        for col in range(9):
            x, y = ox + 7 + col * 18, oy + 17 + row * 18
            c.rect(x, y, x + 17, y + 17, WELL_EDGE); c.rect(x + 1, y + 1, x + 16, y + 16, WELL)
            c.bevel(x, y, x + 17, y + 17, WELL_EDGE, BODY_LIGHT)
    # a light plate under the player's inventory: vanilla draws the dark "Inventory" label and the items on it
    c.rect(ox + 3, oy + 125, ox + 172, oy + 218, PLATE); c.bevel(ox + 3, oy + 125, ox + 172, oy + 218, PLATE_LIGHT, PLATE_DARK)
    for row in range(4):
        for col in range(9):
            x, y = ox + 7 + col * 18, oy + (139 if row < 3 else 197) + (row % 3 if row < 3 else 0) * 18
            c.rect(x, y, x + 17, y + 17, PLATE_DARK); c.rect(x + 1, y + 1, x + 16, y + 16, (128, 132, 112))
            c.bevel(x, y, x + 17, y + 17, PLATE_DARK, PLATE_LIGHT)
    return c


def padded(name, draw):
    """A small glyph: its picture sits at the top of a PNG tall enough for the ascent (ascent > height is rejected)."""
    _, _, y, w, h = GLYPHS[name]
    c = Canvas(w, max(h, ascent(name)))
    draw(c, w, h)
    return c


def ascent(name):
    return 6 + 7 - GLYPHS[name][2]


def scope(c, w, h):
    """The whole window (glass, graticule, a still waveform); the shader redraws it with a moving trace."""
    c.rect(0, 0, w - 1, h - 1, GLASS)
    for gx in range(9, w, 10):
        for gy in range(0, h, 2): c.set(gx, gy, (14, 34, 20))
    mid = h // 2
    for x in range(w):
        env = 0.35 + 0.65 * math.sin(math.pi * x / (w - 1)) ** 2
        y = mid + round(env * (h / 2 - 2) * math.sin(x * 0.55) * math.cos(x * 0.13))
        c.set(x, y, PHOSPHOR)
        c.set(x, y + (1 if y < mid else -1), (60, 150, 84))


def lcd(c, w, h):
    c.rect(0, 0, w - 1, h - 1, GLASS)


def on_air(c, w, h):
    c.rect(0, 0, w - 1, h - 1, (214, 44, 34)); c.frame(0, 0, w - 1, h - 1, (255, 120, 96))
    c.text(4, 5, 'ON AIR', (255, 236, 220))


def led(c, w, h):
    c.rect(0, 0, w - 1, h - 1, (70, 230, 96)); c.rect(1, 1, 2, 2, (200, 255, 210))


# ---- 16x16 icons -----------------------------------------------------------------------------------------------------
def icon(rows, palette):
    c = Canvas(16, 16)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in palette: c.set(x, y, palette[ch])
    return c


def button(face, light, dark, glyph_rows, glyph_colour):
    c = Canvas(16, 16)
    c.rect(1, 1, 14, 14, face); c.bevel(1, 1, 14, 14, light, dark); c.frame(0, 0, 15, 15, EDGE)
    for corner in ((0, 0), (15, 0), (0, 15), (15, 15)): c.set(*corner, (0, 0, 0, 0))
    for y, row in enumerate(glyph_rows):
        for x, ch in enumerate(row):
            if ch == '#': c.set(4 + x, 4 + y, glyph_colour)
    return c


G = (96, 104, 84), (132, 140, 116), (60, 66, 52)      # neutral button
ICONS = {
    'power_on': button((40, 120, 56), (90, 200, 110), (20, 70, 30), ['...##...', '.#.##.#.', '#..##..#', '#..##..#', '#......#', '#......#', '.#....#.', '..####..'], (220, 255, 226)),
    'power_off': button((110, 44, 40), (170, 80, 72), (64, 22, 20), ['...##...', '.#.##.#.', '#..##..#', '#..##..#', '#......#', '#......#', '.#....#.', '..####..'], (240, 200, 196)),
    'vol_down': button(*G, ['........', '........', '........', '########', '########', '........', '........', '........'], (236, 240, 220)),
    'vol_up': button(*G, ['...##...', '...##...', '...##...', '########', '########', '...##...', '...##...', '...##...'], (236, 240, 220)),
    'volume': button((44, 50, 40), (70, 78, 62), (24, 28, 22), ['...#..#.', '..##...#', '####.#.#', '####.#.#', '####.#.#', '####.#.#', '..##...#', '...#..#.'], PHOSPHOR),
    'mode_dialog': button(*G, ['########', '#......#', '#.####.#', '#......#', '#.##...#', '#......#', '########', '........'], (236, 240, 220)),
    'mode_chat': button(*G, ['.######.', '#......#', '#.#.#..#', '#......#', '.######.', '..##....', '.#......', '........'], (236, 240, 220)),
    'talk': button((150, 104, 30), (220, 170, 70), (90, 60, 16), ['..####..', '..####..', '..####..', '#.####.#', '#.####.#', '.#....#.', '..####..', '...##...'], (255, 244, 214)),
    'up': button(*G, ['...##...', '..####..', '.######.', '########', '...##...', '...##...', '...##...', '........'], (236, 240, 220)),
    'down': button(*G, ['...##...', '...##...', '...##...', '########', '.######.', '..####..', '...##...', '........'], (236, 240, 220)),
    'tuned': button((44, 50, 40), (70, 78, 62), (24, 28, 22), ['..####..', '.#....#.', '#....#.#', '#...#..#', '#..#...#', '#......#', '.#....#.', '..####..'], PHOSPHOR),
    'close': button((110, 44, 40), (170, 80, 72), (64, 22, 20), ['#......#', '.#....#.', '..#..#..', '...##...', '...##...', '..#..#..', '.#....#.', '#......#'], (255, 226, 220)),
}


def channel(selected):
    """Two layers: layer0 is light grey and gets the channel's colour as a dye tint; layer1 stays as drawn."""
    base, over = Canvas(16, 16), Canvas(16, 16)
    base.rect(1, 1, 14, 14, (214, 214, 214)); base.bevel(1, 1, 14, 14, (255, 255, 255), (150, 150, 150))
    over.frame(0, 0, 15, 15, (255, 240, 170) if selected else EDGE)
    for corner in ((0, 0), (15, 0), (0, 15), (15, 15)): over.set(*corner, (0, 0, 0, 0))
    for y, row in enumerate(['..#..#..', '.#....#.', '#..##..#', '#..##..#', '.#....#.', '..#..#..', '...##...', '...##...']):
        for x, ch in enumerate(row):
            if ch == '#': over.set(4 + x, 4 + y, (255, 255, 255) if selected else (36, 40, 34))
    if selected: over.frame(1, 1, 14, 14, (255, 214, 90))
    return base, over


RADIO_ITEM = icon([
    '...a............', '...a............', '...a............', '...a............',
    '..bbbbbbbbbb....', '..bggggggggb....', '..bgppppppgb....', '..bgppppppgb....',
    '..bggggggggb....', '..bdodododgb....', '..bodododogb....', '..bdodododgb....',
    '..bggggggrgb....', '..bggggggggb....', '..bbbbbbbbbb....', '................',
], {'a': (60, 62, 60), 'b': EDGE, 'g': BODY, 'p': PHOSPHOR, 'd': BODY_DARK, 'o': (24, 28, 22), 'r': (214, 44, 34)})


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=1)
        f.write('\n')


def item_model(name, layers, tinted=False):
    """items/<name>.json -> models/item/<name>.json -> textures/item/<layer>.png (item_model id radio:<name>)."""
    write_json(os.path.join(NS, 'models', 'item', name + '.json'),
               {'parent': 'minecraft:item/generated', 'textures': {f'layer{i}': f'radio:item/{l}' for i, l in enumerate(layers)}})
    model = {'type': 'minecraft:model', 'model': f'radio:item/{name}'}
    if tinted: model['tints'] = [{'type': 'minecraft:dye', 'default': -1}]
    write_json(os.path.join(NS, 'items', name + '.json'), {'model': model})


def art():
    providers = [{'type': 'space', 'advances': {
        **{chr(SPACE_NEG + i): -(1 << i) for i in range(SPACE_BITS)},
        **{chr(SPACE_POS + i): (1 << i) for i in range(SPACE_BITS)}}}]
    canvases = {'panel': panel(), 'scope': padded('scope', scope), 'on_air': padded('on_air', on_air), 'led': padded('led', led),
                'lcd': padded('lcd', lcd)}
    layout = ['// Generated by tools/gen_pack.py from GLYPHS: glyph picture sizes in pixels, and the height of the PNG each',
              '// picture sits at the top of (a glyph quad spans the whole PNG).']
    for name in SHADER_GLYPHS:
        c = canvases[name]
        c.px = [[(r, g, b, SHADER_ALPHA) if a else (r, g, b, a) for (r, g, b, a) in row] for row in c.px]
        _, _, _, w, h = GLYPHS[name]
        layout.append(f'const vec3 RADIO_{name.upper()} = vec3({w}.0, {h}.0, {c.h}.0);   // width, height, PNG height')
    os.makedirs(os.path.join(NS, 'shaders', 'include'), exist_ok=True)
    with open(os.path.join(NS, 'shaders', 'include', 'radio_layout.glsl'), 'w') as f:
        f.write('\n'.join(layout) + '\n')
    for name, (char, _, _, _, _) in GLYPHS.items():
        c = canvases[name]
        c.save(os.path.join(NS, 'textures', 'font', name + '.png'))
        providers.append({'type': 'bitmap', 'file': f'radio:font/{name}.png', 'height': c.h, 'ascent': ascent(name), 'chars': [chr(char)]})
    write_json(os.path.join(NS, 'font', 'gui.json'), {'providers': providers})

    RADIO_ITEM.save(os.path.join(NS, 'textures', 'item', 'radio.png'))
    item_model('radio', ['radio'])
    for name, c in ICONS.items():
        c.save(os.path.join(NS, 'textures', 'item', 'gui', name + '.png'))
        item_model('gui/' + name, ['gui/' + name])
    for name, selected in (('channel', False), ('channel_on', True)):
        base, over = channel(selected)
        base.save(os.path.join(NS, 'textures', 'item', 'gui', name + '.png'))
        over.save(os.path.join(NS, 'textures', 'item', 'gui', name + '_overlay.png'))
        item_model('gui/' + name, ['gui/' + name, 'gui/' + name + '_overlay'], tinted=True)


# ---- sounds ----------------------------------------------------------------------------------------------------------
RATE = 44100


def synth(seconds, fn):
    return [max(-1.0, min(1.0, fn(i / RATE))) for i in range(int(seconds * RATE))]


def env(t, attack, hold, release):
    if t < attack: return t / attack
    if t < attack + hold: return 1.0
    return max(0.0, 1.0 - (t - attack - hold) / release)


def sounds():
    rnd = random.Random(3)
    noise = lambda: rnd.uniform(-1, 1)
    tone = lambda f, t: math.sin(2 * math.pi * f * t)
    clips = {
        # squelch opening: a click, then a short burst of hiss
        'in': synth(0.16, lambda t: 0.7 * noise() * env(t, 0.004, 0.06, 0.09) + 0.5 * tone(180, t) * env(t, 0.001, 0.004, 0.012)),
        # roger beep, then the squelch tail
        'out': synth(0.30, lambda t: 0.45 * tone(1040, t) * env(t, 0.004, 0.11, 0.02)
                     + (0.5 * noise() * env(t - 0.15, 0.004, 0.04, 0.09) if t > 0.15 else 0.0)),
        'power_on': synth(0.20, lambda t: 0.4 * tone(520 + 900 * t / 0.2, t) * env(t, 0.004, 0.10, 0.08) + 0.5 * noise() * env(t, 0.001, 0.003, 0.01)),
        'power_off': synth(0.20, lambda t: 0.4 * tone(900 - 500 * t / 0.2, t) * env(t, 0.004, 0.08, 0.10) + 0.5 * noise() * env(t, 0.001, 0.003, 0.01)),
        'switch': synth(0.07, lambda t: 0.6 * noise() * env(t, 0.001, 0.003, 0.012) + 0.25 * tone(1500, t) * env(t, 0.002, 0.02, 0.03)),
    }
    out = os.path.join(NS, 'sounds', 'radio')
    os.makedirs(out, exist_ok=True)
    for name, samples in clips.items():
        wav = os.path.join(out, name + '.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b''.join(struct.pack('<h', int(s * 30000)) for s in samples))
        # Minecraft only loads Ogg Vorbis. ffmpeg's built-in "vorbis" encoder is used because it is in every ffmpeg build
        # (libvorbis often is not); it only writes stereo, which suits these sounds: Minecraft plays stereo files
        # without positioning, and each of them is played to one listener only. -bitexact keeps the bytes stable.
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'vorbis', '-strict', '-2', '-ac', '2', '-q:a', '5',
                        '-map_metadata', '-1', '-bitexact', os.path.join(out, name + '.ogg')], check=True)
        os.remove(wav)
    write_json(os.path.join(NS, 'sounds.json'), {
        f'radio.{name}': {'sounds': [{'name': f'radio:radio/{name}'}], 'subtitle': f'subtitles.radio.{name}'} for name in clips})
    write_json(os.path.join(NS, 'lang', 'en_us.json'), {
        'subtitles.radio.in': 'Radio squelch', 'subtitles.radio.out': 'Radio beep', 'subtitles.radio.power_on': 'Radio switched on',
        'subtitles.radio.power_off': 'Radio switched off', 'subtitles.radio.switch': 'Radio dial clicks'})


if __name__ == '__main__':
    art()
    if '--no-sounds' not in sys.argv: sounds()
    print('generated into', os.path.normpath(ROOT))
