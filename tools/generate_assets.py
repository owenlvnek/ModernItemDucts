#!/usr/bin/env python3
"""
Generates every asset and data file of Modern Item Ducts: textures (simple procedural pixel art,
original to this project), block/item models, blockstates, lang, recipes, loot tables, tags and the
GameTest structure template.

Run from the repository root:  python3 -I tools/generate_assets.py
Requires Pillow.
"""
import gzip
import io
import json
import os
import struct
import sys

from PIL import Image

MODID = "modernitemducts"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", MODID)
DATA = os.path.join(ROOT, "data", MODID)

DUCTS = {
    # id: (family, opaque, display name)
    "item_duct": ("standard", False, "Item Duct"),
    "item_duct_opaque": ("standard", True, "Opaque Item Duct"),
    "fast_item_duct": ("fast", False, "Fast Item Duct"),
    "fast_item_duct_opaque": ("fast", True, "Opaque Fast Item Duct"),
}
WEIGHTS = ["normal", "dense", "vacuum"]
DIRS = ["down", "up", "north", "south", "west", "east"]
# blockstate rotation that turns a north-facing part toward each direction
ROT = {"north": {}, "south": {"y": 180}, "west": {"y": 270}, "east": {"y": 90}, "up": {"x": 270}, "down": {"x": 90}}

KINDS = {"servo": "Item Servo", "retriever": "Item Retriever", "filter": "Item Filter"}
TIERS = [  # (material id, display, tint rgb, recipe item)
    ("copper", "Copper", (200, 127, 79), "minecraft:copper_ingot"),
    ("iron", "Iron", (216, 216, 216), "minecraft:iron_ingot"),
    ("gold", "Gold", (242, 207, 60), "minecraft:gold_ingot"),
    ("diamond", "Diamond", (82, 220, 208), "minecraft:diamond"),
    ("netherite", "Netherite", (110, 90, 102), "minecraft:netherite_ingot"),
]
KEY_ITEM = {"servo": "minecraft:redstone", "retriever": "minecraft:ender_pearl", "filter": "minecraft:paper"}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def save(img, *parts):
    path = os.path.join(ASSETS, "textures", *parts) + ".png"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + ((c[3],) if len(c) == 4 else (255,))


# ----------------------------------------------------------------------------- palettes

def hexc(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


# Both duct families share a dark slate "tin" frame with light blue-grey lines; the fast duct
# swaps its accent lines (outer line, inner window outline) for cream yellow.
_SLATE = {
    "dark": hexc("#2c3238"), "dark2": hexc("#373f46"), "dark3": hexc("#2d3840"),
    "panel": hexc("#a3b5c2"), "panel_hi": hexc("#b9ccd3"), "panel_mid": hexc("#8da2af"), "panel_lo": hexc("#8196a5"),
}
METALS = {
    "standard": dict(_SLATE, line=hexc("#b9ccd3"), inner=hexc("#9cafbb"), rivet=hexc("#8196a5"), arm2=hexc("#b0c2ce")),
    "fast": dict(_SLATE, line=hexc("#edf2b9"), inner=hexc("#e7eb94"), rivet=hexc("#a3b5c2"), arm2=hexc("#e9eea2")),
}
WEIGHT_MARK = {"dense": (hexc("#851e00"), hexc("#7b1d00")), "vacuum": (hexc("#256703"), hexc("#205d02"))}
CLEAR = (0, 0, 0, 0)


def rgba(c, a=255):
    return tuple(c[:3]) + (a,)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ring(x, y, size=16):
    return min(x, y, size - 1 - x, size - 1 - y)


def from_ascii(rows, palette):
    h, w = len(rows), max(len(r) for r in rows)
    img = Image.new("RGBA", (w, h), CLEAR)
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in palette:
                px[x, y] = rgba(palette[ch])
    return img


# ----------------------------------------------------------------------------- duct textures
#
# Every model face samples the sheet at its own block-space footprint (vanilla's automatic UV
# rule), so one texel is always 1/16 of a block. A duct sheet is therefore cross-shaped: the
# 6x6 core square at [5..10] plus four 6-wide arm strips running out to the sheet edges.

CORE0, CORE1 = 5, 10  # inclusive pixel range of the core square


def weight_hint(px, weight):
    """Dense / vacuum marker: fills the 2x2 centre of the core square."""
    if weight not in WEIGHT_MARK:
        return
    main, edge = WEIGHT_MARK[weight]
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        px[x, y] = rgba(main if (x + y) % 2 == 0 else edge)


def duct_sheet(family, opaque, weight):
    m = METALS[family]
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    for y in range(16):
        for x in range(16):
            in_x = CORE0 <= x <= CORE1
            in_y = CORE0 <= y <= CORE1
            if in_x and in_y:  # core square: 1px dark outline, 1px light rim, 2x2 centre
                r = min(x - CORE0, y - CORE0, CORE1 - x, CORE1 - y)
                if r == 0:
                    c = m["dark"]
                elif r == 1:
                    c = m["line"] if not opaque else (m["line"] if x + y < 15 else m["arm2"])
                else:
                    c = None if not opaque else (m["panel_hi"] if y == 7 else m["panel"])
            elif in_x or in_y:  # arm strip; a = across the strip (0..5), d = distance from sheet edge
                a = (x if in_x else y) - CORE0
                d = min(y, 15 - y) if in_x else min(x, 15 - x)
                across = min(a, 5 - a)
                if across == 0:
                    c = m["dark"]               # rails
                elif d == 0:
                    c = m["dark"]               # tie at the block edge closes the lattice square
                elif across == 1:
                    c = m["line"] if a < 3 else m["arm2"]
                else:
                    c = None if not opaque else (m["panel"] if a == 2 else m["panel_lo"])
            else:
                c = None  # sheet corners are never sampled
            if c is not None:
                px[x, y] = rgba(c)
    weight_hint(px, weight)
    return img


def collar_sheet(family):
    """Collar around an arm's inventory end: 8x8 flange face at [4..11], 4-deep side strips.
    Light metal dominates: a 1px dark outline only at the edges, a wide light band, a 1px
    darker bevel for depth and a light centre where the arm enters. Standard ducts use the
    blue-grey family; fast ducts use only dark slate plus the cream-yellow family."""
    m = METALS[family]
    if family == "fast":  # cream-yellow family only, with a darker olive-cream bevel
        light, light2, bevel, side_bevel = hexc("#e7eb94"), hexc("#edf2b9"), hexc("#b8bd78"), hexc("#a9ad68")
    else:
        light, light2, bevel, side_bevel = hexc("#a3b5c2"), hexc("#b9ccd3"), hexc("#8196a5"), hexc("#8196a5")
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    for y in range(16):
        for x in range(16):
            in_x, in_y = 4 <= x <= 11, 4 <= y <= 11
            if in_x and in_y:  # end face
                r = min(x - 4, y - 4, 11 - x, 11 - y)
                c = m["dark"] if r == 0 else m["line"] if r == 1 else bevel if r == 2 else light2
            elif in_x or in_y:  # side faces; d = 0 at the inventory end, 3 next to the arm
                a = (x if in_x else y) - 4
                d = min(y, 15 - y) if in_x else min(x, 15 - x)
                if a in (0, 7) or d == 0:
                    c = m["dark"]
                elif d == 1:
                    c = m["line"]
                elif d == 2:
                    c = light if a not in (1, 6) else light2
                else:
                    c = side_bevel
            else:
                continue
            px[x, y] = rgba(c)
    return img


# ----------------------------------------------------------------------------- attachment art


# Glyph colours per kind: A = wide bar (darker), B = narrow block (brighter).
GLYPH_COLORS = {
    "servo": (hexc("#8a0000"), hexc("#c00000")),
    "retriever": (hexc("#8a0095"), hexc("#b000c8")),
    "filter": (hexc("#1a6fc0"), hexc("#0a80e0")),
}
CLOGGED_COLORS = (hexc("#b05a00"), hexc("#f09000"))

# 6x6 face glyphs for placed caps (the pale panel inside the 8x8 plate).
GLYPH = {
    "servo": ["......", ".AAAA.", ".AAAA.", "..BB..", "..BB..", "......"],      # stepped, pointing down
    "retriever": ["......", "..BB..", "..BB..", ".AAAA.", ".AAAA.", "......"],  # stepped, pointing up
    "filter": ["......", "..BB..", ".BBBB.", "AAAAAA", ".A..A.", "......"],     # wide low pyramid on legs
}
# 6x3 glyphs for the plate's four side faces, row 0 = the inventory end, row 2 = the duct end.
# The narrow end points the way items move: servo towards the duct, retriever/filter towards
# the inventory.
SIDE_GLYPH = {
    "servo": ["......", ".AAAA.", "..BB.."],
    "retriever": ["..BB..", ".AAAA.", "......"],
    "filter": ["..BB..", "AAAAAA", ".A..A."],
}
# 8x8 glyphs for the 16x16 inventory icons.
ICON_GLYPH = {
    "servo": ["........", ".AAAAAA.", ".AAAAAA.", "..BBBB..", "..BBBB..", "...BB...", "...BB...", "........"],
    "retriever": ["........", "...BB...", "...BB...", "..BBBB..", "..BBBB..", ".AAAAAA.", ".AAAAAA.", "........"],
    "filter": ["........", "........", "...BB...", "..BBBB..", ".AAAAAA.", "AAAAAAAA", ".AA..AA.", "........"],
}


def glyph_palette(kind, state):
    """(A, B) colours for a state: active = bright, idle = same hue much darker, clogged = amber."""
    a, b = CLOGGED_COLORS if state == "clogged" else GLYPH_COLORS[kind]
    if state == "idle":
        return mix(a, (20, 8, 12), 0.62), mix(b, (20, 8, 12), 0.62)
    return a, b


def panel_gradient(px, x0, y0, w, h, hi=252, lo=212):
    """Greyscale panel shading softly from top-left (hi) to bottom-right (lo)."""
    for y in range(h):
        for x in range(w):
            v = int(hi - (x + y) * ((hi - lo) / max(1, w + h - 2)))
            px[x0 + x, y0 + y] = (v, v, v, 255)


def draw_glyph(px, x0, y0, rows, kind, state, outline=None):
    """Draws the stepped glyph; with outline, every empty texel touching it (inside the rows'
    box) gets that colour so the glyph reads on any tier colour."""
    a, b = glyph_palette(kind, state)
    lit = state != "idle"
    top = next(i for i, r in enumerate(rows) if r.strip("."))
    h, w = len(rows), len(rows[0])
    if outline:
        for gy in range(h):
            for gx in range(w):
                if rows[gy][gx] != ".":
                    continue
                near = any(0 <= gy + dy < h and 0 <= gx + dx < w and rows[gy + dy][gx + dx] in "AB"
                           for dy in (-1, 0, 1) for dx in (-1, 0, 1))
                if near:
                    px[x0 + gx, y0 + gy] = rgba(outline)
    for gy, row in enumerate(rows):
        for gx, ch in enumerate(row):
            if ch not in "AB":
                continue
            c = a if ch == "A" else b
            if lit and gy == top:
                c = mix(c, (255, 255, 255), 0.4)  # highlight row on the lit glyph
            px[x0 + gx, y0 + gy] = rgba(c)


def strips(img, side):
    """Pastes a 6x3 side image (row 0 = inventory end) into the four footprint edge strips."""
    img.paste(side, (5, 0))                                                              # top
    img.paste(side.transpose(Image.FLIP_TOP_BOTTOM), (5, 13))                            # bottom
    img.paste(side.transpose(Image.ROTATE_90).transpose(Image.FLIP_TOP_BOTTOM), (0, 5))  # left
    img.paste(side.transpose(Image.ROTATE_270), (13, 5))                                 # right


def plate_gray():
    """Greyscale attachment plate sheet, tinted per tier at render time (tintindex 0).
    The plate is an 8x8 x 4-deep box: front face at [4..11], side strips around it (1:1 texels)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            in_x, in_y = 4 <= x <= 11, 4 <= y <= 11
            if in_x and in_y:
                r = min(x - 4, y - 4, 11 - x, 11 - y)
                v = (255 if x + y < 15 else 205) if r == 0 else 150  # tier-coloured rim around the inset
            elif in_x or in_y:
                a = (x if in_x else y) - 4
                d = min(y, 15 - y) if in_x else min(x, 15 - x)
                v = 60 if a in (0, 7) or d == 3 else 235 if d == 0 else 175
            else:
                continue
            px[x, y] = (v, v, v, 255)
    return img


def face_panel():
    """Greyscale face panel sheet (front 6x6 at [5..10] + four 6x3 side strips). Drawn with
    tintindex 0, so in game it becomes a light version of the tier colour."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    panel_gradient(img.load(), 5, 5, 6, 6)
    side = Image.new("RGBA", (6, 3), (0, 0, 0, 0))
    panel_gradient(side.load(), 0, 0, 6, 3)
    strips(img, side)
    return img


def inset_mark(kind, state):
    """Untinted glyph layer (transparent elsewhere), drawn in front of the tinted face panel.
    Front glyph at [5..10], 6x3 side glyphs in the edge strips. The glyph is the state
    indicator: bright when active, dark when idle/unpowered, amber when clogged. A light outline
    keeps it readable on every tier colour."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw_glyph(img.load(), 5, 5, GLYPH[kind], kind, state)
    side = Image.new("RGBA", (6, 3), (0, 0, 0, 0))
    draw_glyph(side.load(), 0, 0, SIDE_GLYPH[kind], kind, state)
    strips(img, side)
    return img


def item_icon(kind, tint):
    """16x16 inventory icon: tier-coloured border around a pale face with the bright glyph."""
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    t_dark, t_light = mix(tint, (0, 0, 0), 0.55), mix(tint, (255, 255, 255), 0.35)
    for y in range(1, 15):
        for x in range(1, 15):
            r = min(x - 1, y - 1, 14 - x, 14 - y)
            if r == 0:
                if (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
                    continue  # rounded corners
                c = t_dark
            elif r == 1:
                c = t_light if x + y < 15 else tint
            else:
                c = tint
            px[x, y] = rgba(c)
    face = Image.new("RGBA", (8, 8))
    panel_gradient(face.load(), 0, 0, 8, 8)
    fp = face.load()
    lift = mix(tint, (255, 255, 255), 0.3)
    for y in range(8):
        for x in range(8):
            v = fp[x, y][0]
            px[4 + x, 4 + y] = rgba(tuple(c * v // 255 for c in lift))
    luma = 0.3 * tint[0] + 0.59 * tint[1] + 0.11 * tint[2]
    outline = (44, 40, 46) if luma > 110 else (196, 188, 194)  # dark edge, light only on the darkest tier
    draw_glyph(px, 4, 4, ICON_GLYPH[kind], kind, "active", outline=outline)
    return img


def wrench_icon():
    """Open-end wrench: steel head top-right (jaw opening up-right), red grip bottom-left."""
    mask = {}
    for y in range(16):
        for x in range(16):
            hx, hy = x - 11.5, y - 4.5
            head = hx * hx + hy * hy <= 3.4 ** 2
            along, across = (hx - hy) / 1.414, (hx + hy) / 1.414
            if head and along > -0.6 and abs(across) < 1.25:
                head = False  # the jaw opening
            handle = abs(x + y - 15) <= 0.6 and 1 <= x <= 10
            if head:
                mask[(x, y)] = "head"
            elif handle:
                mask[(x, y)] = "grip" if x <= 5 else "shaft"
    img = Image.new("RGBA", (16, 16), CLEAR)
    px = img.load()
    for (x, y) in list(mask):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < 16 and 0 <= n[1] < 16 and n not in mask:
                px[n] = rgba((36, 38, 44))
    for (x, y), part in mask.items():
        if part == "grip":
            c = (186, 70, 46) if (x + y) % 2 == 0 else (150, 52, 34)
        else:
            lit = (x - 11.5) + (y - 4.5) < -1 if part == "head" else False
            c = (236, 240, 246) if lit else (190, 196, 206) if part == "head" else (170, 176, 188)
        px[x, y] = rgba(c)
    return img


# ----------------------------------------------------------------------------- GUI art

GUI_BG, GUI_LIGHT, GUI_DARK, GUI_EDGE = (198, 198, 198), (255, 255, 255), (85, 85, 85), (0, 0, 0)


def panel(img, x0, y0, w, h, open_right=False):
    """Vanilla-style raised panel: black outline with rounded corners, white top/left band,
    dark bottom/right band. With open_right the right side is left open (side tabs)."""
    px = img.load()
    for y in range(h):
        for x in range(w):
            right = 99 if open_right else w - 1 - x
            bottom = h - 1 - y
            if x + y < 2 or right + bottom < 2 or right + y < 2 or x + bottom < 2:
                continue  # rounded outer corner
            if x == 0 or y == 0 or right == 0 or bottom == 0 or (x == 1 and y == 1) or (right == 1 and bottom == 1) \
                    or (right == 1 and y == 1) or (x == 1 and bottom == 1):
                c = GUI_EDGE
            elif (x <= 2 or y <= 2) and not (right <= 2 or bottom <= 2):
                c = GUI_LIGHT
            elif (right <= 2 or bottom <= 2) and not (x <= 2 or y <= 2):
                c = GUI_DARK
            elif x <= 2 or y <= 2:
                c = GUI_BG  # the two mixed corners
            else:
                c = GUI_BG
            px[x0 + x, y0 + y] = rgba(c)


def slot(img, x0, y0, fill=(139, 139, 139), top=(55, 55, 55), bottom=(255, 255, 255)):
    px = img.load()
    for y in range(18):
        for x in range(18):
            if (x == 17 and y == 0) or (x == 0 and y == 17):
                c = fill
            elif x == 0 or y == 0:
                c = top
            elif x == 17 or y == 17:
                c = bottom
            else:
                c = fill
            px[x0 + x, y0 + y] = rgba(c)


def button(img, x0, y0, w, h, state):
    """state: normal | hover | pressed | disabled | on | on_hover"""
    face = {"normal": (176, 176, 176), "hover": (170, 184, 214), "pressed": (140, 140, 140), "disabled": (120, 120, 120),
            "on": (150, 196, 150), "on_hover": (160, 206, 186)}[state]
    hi, lo = mix(face, (255, 255, 255), 0.55), mix(face, (0, 0, 0), 0.45)
    if state == "pressed":
        hi, lo = lo, hi
    if state == "disabled":
        hi, lo = mix(face, (255, 255, 255), 0.15), mix(face, (0, 0, 0), 0.25)
    px = img.load()
    for y in range(h):
        for x in range(w):
            right, bottom = w - 1 - x, h - 1 - y
            if (x == 0 or right == 0) and (y == 0 or bottom == 0):
                continue
            if x == 0 or y == 0 or right == 0 or bottom == 0:
                c = (24, 24, 24) if state != "hover" and state != "on_hover" else (250, 250, 250)
            elif x == 1 or y == 1:
                c = hi
            elif right == 1 or bottom == 1:
                c = lo
            else:
                c = face
            px[x0 + x, y0 + y] = rgba(c)


ICON_PAL = {
    "K": (34, 34, 38), "W": (250, 250, 250), "w": (200, 200, 206), "G": (64, 186, 72), "g": (28, 110, 36),
    "R": (220, 56, 44), "r": (130, 26, 20), "Y": (246, 214, 80), "y": (190, 150, 40), "B": (70, 130, 230),
    "b": (30, 60, 140), "S": (150, 150, 158), "s": (90, 90, 98), "T": (196, 150, 96), "t": (120, 86, 48),
    "O": (240, 140, 40), "P": (150, 90, 200), "C": (90, 200, 210),
}
ICONS = [  # 12x12, order matters (indices used by AttachmentScreen)
    # 0 allow list (include)
    ["............", "..........Gg", ".........GGg", "........GGg.", ".......GGg..", "Gg....GGg...",
     "gGg..GGg....", ".gGgGGg.....", "..gGGg......", "...gg.......", "............", "............"],
    # 1 deny list (exclude)
    ["............", ".Rr......rR.", ".rRr....rRr.", "..rRr..rRr..", "...rRrrRr...", "....rRRr....",
     "....rRRr....", "...rRrrRr...", "..rRr..rRr..", ".rRr....rRr.", ".Rr......rR.", "............"],
    # 2 durability compared (pickaxe)
    ["............", "...SSSSS....", "..S.....SK..", ".......KTS..", "......KTK.S.", ".....KTK..S.",
     "....KTK.....", "...KTK......", "..KTK.......", ".KTK........", ".KK.........", "............"],
    None,  # 3 = 2 crossed out
    # 4 NBT compared (braces)
    ["............", "...bB..Bb...", "..B......B..", "..B......B..", "..B......B..", ".B........B.",
     "..B......B..", "..B......B..", "..B......B..", "...bB..Bb...", "............", "............"],
    None,  # 5
    # 6 tag matching on (label)
    ["............", "....yyyyyyy.", "...yYYYYYYy.", "..yYYYYYYYy.", ".yYKYYYYYYy.", ".yYYYYYYYYy.",
     "..yYYYYYYYy.", "...yYYYYYYy.", "....yyyyyyy.", "............", "............", "............"],
    None,  # 7
    # 8 mod matching on (cube)
    ["............", ".....KK.....", "...KKwwKK...", ".KKwwWWwwKK.", ".KwwWWWWwwK.", ".KSSwwwwTTK.",
     ".KSSSSTTTTK.", ".KSSSSTTTTK.", ".KSSSSTTTTK.", "..KKSSTTKK..", "....KKKK....", "............"],
    None,  # 9
    # 10 sort: nearest first
    ["............", "............", "............", "..GG........", ".GGGG.K.....", ".GGGGKKKKKK.",
     ".GGGG.K.....", "..GG........", "............", "............", "............", "............"],
    # 11 sort: furthest first
    ["............", "............", "............", "........GG..", "......KGGGG.", ".KKKKKKGGGG.",
     "......KGGGG.", "........GG..", "............", "............", "............", "............"],
    # 12 sort: random (die)
    ["............", ".KKKKKKKKKK.", ".KWWWWWWWWK.", ".KWKWWWWKWK.", ".KWWWWWWWWK.", ".KWWWKKWWWK.",
     ".KWWWKKWWWK.", ".KWWWWWWWWK.", ".KWKWWWWKWK.", ".KWWWWWWWWK.", ".KKKKKKKKKK.", "............"],
    # 13 sort: round robin (circular arrows)
    ["............", "....BBBB....", "..BB....BB..", ".B.......bB.", ".B......bbb.", ".B..........",
     "..........B.", ".bbb......B.", ".Bb.......B.", "..BB....BB..", "....BBBB....", "............"],
    # 14 over-send prevention on (box, green tick)
    ["............", "............", ".K........K.", ".K....Gg..K.", ".K...GGg..K.", ".KGg.GGg..K.",
     ".K.GGGg...K.", ".K..Gg....K.", ".KKKKKKKKKK.", "............", "............", "............"],
    # 15 over-send prevention off (box overflowing)
    ["....OO.OO...", "...O..O..O..", "....OOOOO...", ".K..TTTTT.K.", ".K.TTTTTTTK.", ".KTTTTTTTTK.",
     ".KTTTTTTTTK.", ".KTTTTTTTTK.", ".KKKKKKKKKK.", "............", "............", "............"],
    # 16 redstone: ignore (scattered dust)
    ["............", "............", "...rr.......", "..rRRr...r..", "...rr...rRr.", ".........r..",
     "....r.......", "...rRr..rr..", "....r..rRRr.", "........rr..", "............", "............"],
    # 17 redstone: active when unpowered (unlit torch)
    ["............", "....sss.....", "....srs.....", "....sss.....", ".....t......", ".....t......",
     ".....t......", ".....t......", ".....t......", ".....t......", "....KKK.....", "............"],
    # 18 redstone: active when powered (lit torch)
    ["...R...R....", "....RRR.....", "...RRYRR....", "....RRR.....", ".....t......", ".....t......",
     ".....t......", ".....t......", ".....t......", ".....t......", "....KKK.....", "............"],
    # 19 info
    ["....BBBB....", "..BBBBBBBB..", ".BBBBWWBBBB.", ".BBBBBBBBBB.", "BBBBWWWBBBBB", "BBBBBWWBBBBB",
     "BBBBBWWBBBBB", "BBBBBWWBBBBB", ".BBBWWWWBBB.", ".BBBBBBBBBB.", "..BBBBBBBB..", "....BBBB...."],
    # 20 plus
    ["............", "............", "............", ".....KK.....", ".....KK.....", "...KKKKKK...",
     "...KKKKKK...", ".....KK.....", ".....KK.....", "............", "............", "............"],
    # 21 minus
    ["............", "............", "............", "............", "............", "...KKKKKK...",
     "...KKKKKK...", "............", "............", "............", "............", "............"],
]


def crossed(img):
    """Greyed-out copy with a red diagonal slash: 'this comparison is ignored'."""
    out = img.copy()
    px = out.load()
    for y in range(12):
        for x in range(12):
            r, g, b, a = px[x, y]
            if a:
                v = (r + g + b) // 3
                px[x, y] = (v // 3 + 60, v // 3 + 60, v // 3 + 64, a)
    for i in range(0, 12):
        px[i, i] = rgba((236, 44, 36))
        if i < 11:
            px[i + 1, i] = rgba((236, 44, 36))
        if i > 0:
            px[i - 1, i] = rgba((120, 20, 16))
    return out


def gui_textures():
    # --- main panel, player inventory baked in at the vanilla position
    w, h = 176, 204
    img = Image.new("RGBA", (256, 256), CLEAR)
    panel(img, 0, 0, w, h)
    inv_top = h - 82
    for r in range(3):
        for c in range(9):
            slot(img, 7 + c * 18, inv_top - 1 + r * 18)
    for c in range(9):
        slot(img, 7 + c * 18, inv_top + 57)
    save(img, "gui", "attachment")

    # --- widget sheet
    s = Image.new("RGBA", (256, 256), CLEAR)
    slot(s, 0, 0)                                                                  # (0,0) plain slot
    slot(s, 18, 0, fill=(118, 132, 170), top=(36, 44, 78), bottom=(214, 226, 255))  # (18,0) ghost slot
    for i, st in enumerate(["normal", "hover", "pressed", "disabled", "on", "on_hover"]):
        button(s, i * 20, 20, 20, 20, st)                                          # (i*20, 20) 20x20 buttons
    for i, st in enumerate(["normal", "hover", "pressed", "disabled"]):
        button(s, i * 14, 40, 14, 14, st)                                          # (i*14, 40) 14x14 buttons
    panel(s, 0, 60, 27, 52, open_right=True)                                       # (0,60) tall side tab
    panel(s, 30, 60, 27, 28, open_right=True)                                      # (30,60) short side tab
    for y in range(12):                                                            # (0,148) 24x12 readout well
        for x in range(24):
            edge_tl = x == 0 or y == 0
            edge_br = x == 23 or y == 11
            s.putpixel((x, 148 + y), rgba((55, 55, 55) if edge_tl else (255, 255, 255) if edge_br else (232, 232, 220)))
    icons = []
    for i, rows in enumerate(ICONS):
        icons.append(None if rows is None else from_ascii(rows, ICON_PAL))
    for i, ic in enumerate(icons):
        if ic is None:
            icons[i] = crossed(icons[i - 1])
    for i, ic in enumerate(icons):  # 12x12 icons, 20 per row from (0,120): ((i%20)*12, 120+(i//20)*12)
        s.paste(ic, ((i % 20) * 12, 120 + (i // 20) * 12), ic)
    save(s, "gui", "widgets")
    return img, s


# ----------------------------------------------------------------------------- models

def footprint(face, frm, to):
    """Block-space UV of a face (the same rule vanilla uses when "uv" is omitted)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    return {
        "down": [x0, 16 - z1, x1, 16 - z0],
        "up": [x0, z0, x1, z1],
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0],
        "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }[face]


def element(frm, to, faces, texture, tint=False, grow=0.0):
    """Element whose faces use explicit 1:1 (footprint) UVs, so one texel = 1/16 block.

    grow pushes every face outward by that many pixels while keeping the UVs of the nominal box.
    Used to keep a box that encloses another one from sharing its planes (z-fighting)."""
    out = {}
    for f in faces:
        e = {"texture": texture, "uv": [round(v, 3) for v in footprint(f, frm, to)]}
        if tint:
            e["tintindex"] = 0
        out[f] = e
    if grow:
        frm = [round(v - grow, 3) for v in frm]
        to = [round(v + grow, 3) for v in to]
    return {"from": frm, "to": to, "faces": out}


C0, C1 = 5, 11  # core cube


def core_element(tex):
    return element([C0, C0, C0], [C1, C1, C1], DIRS, tex)


def arm_element(tex):
    # North-facing arm from the block edge to the core, open at both ends.
    return element([C0, C0, 0], [C1, C1, C0], ["up", "down", "west", "east"], tex)


def render_type(opaque):
    return "minecraft:solid" if opaque else "minecraft:cutout"


def gen_duct(block_id, family, opaque):
    m = f"{MODID}:block/"
    base = f"{m}{block_id}"
    for w in WEIGHTS:
        save(duct_sheet(family, opaque, w), "block", f"{block_id}_{w}")
        write_json(os.path.join(ASSETS, "models", "block", f"{block_id}_core_{w}.json"), {
            "parent": "minecraft:block/block", "render_type": render_type(opaque), "ambientocclusion": False,
            "textures": {"particle": f"{base}_{w}", "core": f"{base}_{w}"},
            "elements": [core_element("#core")]})
    save(collar_sheet(family), "block", f"{block_id}_collar")
    # Arm strips are identical on every weight's sheet, so arms always use the normal sheet.
    write_json(os.path.join(ASSETS, "models", "block", f"{block_id}_arm.json"), {
        "render_type": render_type(opaque), "ambientocclusion": False,
        "textures": {"particle": f"{base}_normal", "arm": f"{base}_normal"},
        "elements": [arm_element("#arm")]})
    write_json(os.path.join(ASSETS, "models", "block", f"{block_id}_arm_inventory.json"), {
        "render_type": render_type(opaque), "ambientocclusion": False,
        "textures": {"particle": f"{base}_normal", "arm": f"{base}_normal", "collar": f"{base}_collar"},
        "elements": [arm_element("#arm"), element([4, 4, 0], [12, 12, 4], DIRS, "#collar")]})

    parts = []
    for w in WEIGHTS:
        parts.append({"when": {"weight": w}, "apply": {"model": f"{m}{block_id}_core_{w}"}})
    for d in DIRS:
        for kind, model in (("duct", "arm"), ("inventory", "arm_inventory")):
            parts.append({"when": {d: kind}, "apply": dict({"model": f"{m}{block_id}_{model}"}, **ROT[d])})
    write_json(os.path.join(ASSETS, "blockstates", f"{block_id}.json"), {"multipart": parts})

    # Item models: a vertical segment (core + both arms) as ONE 6x16x6 column. With 1:1 footprint
    # UVs its four long faces sample sheet columns 5..10 over the full height, which is exactly the
    # top arm strip, the core square and the bottom arm strip, so the rails run along the length.
    # End caps are not drawn.
    #
    # Every duct item also gets four inward-facing planes on the same four walls (clear AND opaque).
    # Items render with back-face culling, so without them the far long sides (whose outward faces
    # point away from the camera) were culled: clear ducts showed nothing through their windows and
    # opaque ducts looked like an open "V" because the column has no end caps. The planes sit
    # INNER px inside the outer walls so no face pair is coplanar even if culling were off.
    INNER = 0.05
    for w in WEIGHTS:
        tex = f"{base}_{w}"
        name = block_id if w == "normal" else f"{block_id}_{w}"
        elements = [element([C0, 0, C0], [C1, 16, C1], ["north", "south", "west", "east"], "#core")]
        lo, hi = C0 + INNER, C1 - INNER
        for face, frm, to in (("south", [C0, 0, lo], [C1, 16, lo]),   # inner side of the north wall
                              ("north", [C0, 0, hi], [C1, 16, hi]),   # inner side of the south wall
                              ("east", [lo, 0, C0], [lo, 16, C1]),    # inner side of the west wall
                              ("west", [hi, 0, C0], [hi, 16, C1])):   # inner side of the east wall
            e = element(frm, to, [face], "#core")
            e["faces"][face]["uv"] = [C0, 0, C1, 16]  # same sheet column as the outer walls
            elements.append(e)
        model = {
            "parent": "minecraft:block/block", "render_type": render_type(opaque),
            "textures": {"particle": tex, "core": tex},
            "elements": elements,
            "display": {
                "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
                "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
                "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
                "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
                "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            },
        }
        if w == "normal":
            model["overrides"] = [
                {"predicate": {f"{MODID}:weight": 0.5}, "model": f"{MODID}:item/{block_id}_dense"},
                {"predicate": {f"{MODID}:weight": 1.0}, "model": f"{MODID}:item/{block_id}_vacuum"},
            ]
        write_json(os.path.join(ASSETS, "models", "item", f"{name}.json"), model)

    # loot table keeps the routing weight
    write_json(os.path.join(DATA, "loot_tables", "blocks", f"{block_id}.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1, "bonus_rolls": 0,
            "entries": [{"type": "minecraft:item", "name": f"{MODID}:{block_id}", "functions": [
                {"function": "minecraft:copy_state", "block": f"{MODID}:{block_id}", "properties": ["weight"]}]}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }]})


# Attachment caps are drawn by the block entity renderer on top of the chunk-rendered duct.
# 0.25 px (~0.016 block) keeps coplanar-looking faces apart well beyond depth-buffer precision
# at normal view distances; 0.01-0.02 px was not enough at range.
PLATE_GROW = 0.25


def mark_element(texture, layer, tint=False):
    """Front face quad, layer * PLATE_GROW in front of the nominal plate front (layer >= 2)."""
    z = -layer * PLATE_GROW
    e = element([5, 5, z], [11, 11, z + 0.01], ["north"], texture, tint=tint)
    e["faces"]["north"]["uv"] = footprint("north", [5, 5, 0], [11, 11, 0])
    return e


def side_mark_elements(texture, layer, tint=False):
    """Four flat quads, one per plate side, layer * PLATE_GROW outside the nominal plate (so no
    face is coplanar). Each covers x/y 5..11 across and z 0..3 deep (the back 1px stays rim)."""
    out = []
    o = layer * PLATE_GROW
    planes = {  # face: (nominal box for the UV footprint, actual zero-thickness plane)
        "up": (([5, 12, 0], [11, 12, 3]), ([5, 12 + o, 0], [11, 12 + o, 3])),
        "down": (([5, 4, 0], [11, 4, 3]), ([5, 4 - o, 0], [11, 4 - o, 3])),
        "west": (([4, 5, 0], [4, 11, 3]), ([4 - o, 5, 0], [4 - o, 11, 3])),
        "east": (([12, 5, 0], [12, 11, 3]), ([12 + o, 5, 0], [12 + o, 11, 3])),
    }
    for face, (nominal, actual) in planes.items():
        e = element(actual[0], actual[1], [face], texture, tint=tint)
        e["faces"][face]["uv"] = footprint(face, *nominal)
        out.append(e)
    return out


def gen_attachments():
    save(plate_gray(), "block", "attachment_body")
    save(face_panel(), "block", "attachment_panel")
    for kind in KINDS:
        states = ["idle", "active", "clogged"] if kind != "filter" else [None]
        for s in states:
            tex_name = f"{kind}_{s}_indicator" if s else f"{kind}_indicator"
            save(inset_mark(kind, s or "active"), "block", tex_name)  # filters: one, always-lit look
            model_name = f"item_{kind}_{s}" if s else f"item_{kind}"
            write_json(os.path.join(ASSETS, "models", "block", "attachment", f"{model_name}.json"), {
                "ambientocclusion": False,
                "textures": {"particle": f"{MODID}:block/attachment_body", "body": f"{MODID}:block/attachment_body",
                             "panel": f"{MODID}:block/attachment_panel", "mark": f"{MODID}:block/{tex_name}"},
                "elements": [
                    # 8x8 plate, 4 deep. Grown by PLATE_GROW so it fully encloses the inventory collar
                    # ([4,4,0]-[12,12,4], same nominal box) instead of sharing all of its planes.
                    element([4, 4, 0], [12, 12, 4], DIRS, "#body", tint=True, grow=PLATE_GROW),
                    # Face panel (tinted: light tier colour) on the front and all four sides, one
                    # PLATE_GROW outside the plate; the untinted glyph layer one PLATE_GROW further out.
                    mark_element("#panel", 2, tint=True),
                    *side_mark_elements("#panel", 2, tint=True),
                    mark_element("#mark", 3),
                    *side_mark_elements("#mark", 3),
                ]})
        for (mat, _disp, tint, _item) in TIERS:
            name = f"item_{kind}_{mat}"
            save(item_icon(kind, tint), "item", name)
            write_json(os.path.join(ASSETS, "models", "item", f"{name}.json"),
                       {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}})


# ----------------------------------------------------------------------------- recipes

def shaped(name, pattern, key, result, count=1, nbt=None):
    res = {"item": result, "count": count}
    if nbt:
        res["nbt"] = nbt
    write_json(os.path.join(DATA, "recipes", f"{name}.json"), {
        "type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
        "key": {k: ({"item": v} if isinstance(v, str) else v) for k, v in key.items()}, "result": res})


def shapeless(name, ingredients, result, count=1, nbt=None):
    res = {"item": result, "count": count}
    if nbt:
        res["nbt"] = nbt
    write_json(os.path.join(DATA, "recipes", f"{name}.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [({"item": i} if isinstance(i, str) else i) for i in ingredients], "result": res})


def gen_recipes():
    m = MODID + ":"
    shaped("item_duct", ["CGC"], {"C": "minecraft:copper_ingot", "G": "minecraft:glass"}, m + "item_duct", 6)
    shaped("item_duct_opaque", ["CIC"], {"C": "minecraft:copper_ingot", "I": "minecraft:iron_ingot"}, m + "item_duct_opaque", 6)
    shapeless("item_duct_from_opaque", [m + "item_duct_opaque"] * 6 + ["minecraft:glass"], m + "item_duct", 6)
    shapeless("item_duct_opaque_from_clear", [m + "item_duct"] * 6 + ["minecraft:iron_ingot"], m + "item_duct_opaque", 6)
    shaped("fast_item_duct", ["DDD", "DGD", "DDD"], {"D": m + "item_duct", "G": "minecraft:glowstone"}, m + "fast_item_duct", 8)
    shaped("fast_item_duct_opaque", ["DDD", "DGD", "DDD"], {"D": m + "item_duct_opaque", "G": "minecraft:glowstone"},
           m + "fast_item_duct_opaque", 8)
    shapeless("fast_item_duct_from_opaque", [m + "fast_item_duct_opaque"] * 6 + ["minecraft:glass"], m + "fast_item_duct", 6)
    shapeless("fast_item_duct_opaque_from_clear", [m + "fast_item_duct"] * 6 + ["minecraft:iron_ingot"],
              m + "fast_item_duct_opaque", 6)
    for d in DUCTS:
        shapeless(f"{d}_dense", [m + d] + ["minecraft:iron_nugget"] * 3, m + d, 1, nbt='{BlockStateTag:{weight:"dense"}}')
        shapeless(f"{d}_vacuum", [m + d] + ["minecraft:gold_nugget"] * 3, m + d, 1, nbt='{BlockStateTag:{weight:"vacuum"}}')
        shapeless(f"{d}_normal", [m + d, "minecraft:water_bucket"], m + d, 1)
    shaped("duct_wrench", ["I I", " C ", " I "], {"I": "minecraft:iron_ingot", "C": "minecraft:copper_ingot"}, m + "duct_wrench")
    for kind in KINDS:
        for i, (mat, _d, _t, item) in enumerate(TIERS):
            shaped(f"item_{kind}_{mat}", ["NKN", "GTG"],
                   {"N": "minecraft:iron_nugget", "K": KEY_ITEM[kind], "G": "minecraft:glass", "T": item},
                   m + f"item_{kind}_{mat}", 2)
            if i > 0:
                lower = [{"item": m + f"item_{kind}_{TIERS[j][0]}"} for j in range(i)]
                shapeless(f"item_{kind}_{mat}_upgrade", [lower, item], m + f"item_{kind}_{mat}", 1)


# ----------------------------------------------------------------------------- tags

def gen_tags():
    write_json(os.path.join(DATA, "tags", "items", "wrenches.json"), {"replace": False, "values": [f"{MODID}:duct_wrench"]})
    write_json(os.path.join(ROOT, "data", "forge", "tags", "items", "tools", "wrench.json"),
               {"replace": False, "values": [f"{MODID}:duct_wrench"]})
    write_json(os.path.join(ROOT, "data", "minecraft", "tags", "blocks", "mineable", "pickaxe.json"),
               {"replace": False, "values": [f"{MODID}:{d}" for d in DUCTS]})


# ----------------------------------------------------------------------------- lang

def gen_lang():
    lang = {"itemGroup.modernitemducts": "Modern Item Ducts"}
    for d, (_f, _o, name) in DUCTS.items():
        lang[f"block.{MODID}.{d}"] = name
    lang[f"item.{MODID}.weighted.dense"] = "%s (Dense)"
    lang[f"item.{MODID}.weighted.vacuum"] = "%s (Vacuum)"
    lang[f"tooltip.{MODID}.duct.normal"] = "Moves items between inventories."
    lang[f"tooltip.{MODID}.duct.dense"] = "Heavy: destinations through it are tried last."
    lang[f"tooltip.{MODID}.duct.vacuum"] = "Light: destinations through it are tried first."
    lang[f"item.{MODID}.duct_wrench"] = "Item Duct Wrench"
    lang[f"tooltip.{MODID}.wrench"] = "Click a duct side to block/unblock it, an attachment to remove it. Sneak-click to pick up the duct."
    for kind, name in KINDS.items():
        for (mat, disp, _t, _i) in TIERS:
            lang[f"item.{MODID}.item_{kind}_{mat}"] = f"{disp} {name}"
    lang[f"tooltip.{MODID}.servo"] = "Pulls items from the inventory it faces into the duct network."
    lang[f"tooltip.{MODID}.retriever"] = "Fetches items from other inventories on the network into the one it faces."
    lang[f"tooltip.{MODID}.filter"] = "Limits which items the network may put into the inventory it faces."
    lang[f"tooltip.{MODID}.stats"] = "Up to %s items every %s s, speed %s"
    lang[f"tooltip.{MODID}.slots"] = "Filter slots: %s"
    lang[f"message.{MODID}.blocked"] = "Closed the %s side"
    lang[f"message.{MODID}.unblocked"] = "Opened the %s side"
    for d in DIRS:
        lang[f"direction.{MODID}.{d}"] = {"down": "bottom", "up": "top"}.get(d, d)
    g = f"gui.{MODID}."
    flags = {
        "exclude": ("Deny list: matching items are blocked", "Allow list: only matching items pass"),
        "ignore_damage": ("Durability is ignored", "Durability must match"),
        "ignore_nbt": ("Extra item data is ignored", "Extra item data must match"),
        "ignore_tags": ("Item tags are not compared", "Items sharing any tag also match"),
        "ignore_mod": ("The owning mod is not compared", "Any item from the same mod also matches"),
    }
    for f, (on, off) in flags.items():
        lang[g + f"flag.{f}.on"] = on
        lang[g + f"flag.{f}.off"] = off
    lang[g + "sort.nearest"] = "Order: closest first"
    lang[g + "sort.furthest"] = "Order: farthest first"
    lang[g + "sort.random"] = "Order: random"
    lang[g + "sort.round_robin"] = "Order: take turns"
    lang[g + "oversend.on"] = "Counts items already on their way (no over-filling)"
    lang[g + "oversend.off"] = "Ignores items already on their way"
    lang[g + "redstone.ignore"] = "Redstone: always on"
    lang[g + "redstone.low"] = "Redstone: on while unpowered"
    lang[g + "redstone.high"] = "Redstone: on only while powered"
    lang[g + "powered"] = "Currently powered"
    lang[g + "unpowered"] = "Currently unpowered"
    lang[g + "max_op"] = "Items per operation"
    lang[g + "max_stock"] = "Keep at most this many of each item in the target (∞ = no limit)"
    lang[g + "info.servo"] = "Item Servo: pulls items out of the inventory it faces and sends them into the ducts. The grid filters what it pulls."
    lang[g + "info.retriever"] = "Item Retriever: fetches items matching its grid from other inventories on the network."
    lang[g + "info.filter"] = "Item Filter: only items passing the grid may be delivered into the inventory it faces."
    lang[g + "info.shift"] = "+/-: click = 1, Shift+left = 16, Shift+right = 4. Right-click cycles buttons backwards."
    write_json(os.path.join(ASSETS, "lang", "en_us.json"), lang)


# ----------------------------------------------------------------------------- GameTest structure (NBT)

def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag(tag_type, name, payload):
    return bytes([tag_type]) + nbt_string(name) + payload


def nbt_int(v):
    return struct.pack(">i", v)


def list_payload(elem_type, items):
    return bytes([elem_type]) + struct.pack(">i", len(items)) + b"".join(items)


def compound_payload(entries):
    return b"".join(entries) + b"\x00"


def gen_structure(name, sx, sy, sz):
    palette = [compound_payload([tag(8, "Name", nbt_string("minecraft:stone"))]),
               compound_payload([tag(8, "Name", nbt_string("minecraft:air"))])]
    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                pos = list_payload(3, [nbt_int(x), nbt_int(y), nbt_int(z)])
                blocks.append(compound_payload([tag(9, "pos", pos), tag(3, "state", nbt_int(0 if y == 0 else 1))]))
    root = compound_payload([
        tag(3, "DataVersion", nbt_int(3465)),
        tag(9, "size", list_payload(3, [nbt_int(sx), nbt_int(sy), nbt_int(sz)])),
        tag(9, "palette", list_payload(10, palette)),
        tag(9, "blocks", list_payload(10, blocks)),
        tag(9, "entities", list_payload(10, [])),
    ])
    data = bytes([10]) + nbt_string("") + root
    path = os.path.join(DATA, "structures", f"{name}.nbt")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
        gz.write(data)
    with open(path, "wb") as f:
        f.write(buf.getvalue())


def main():
    for block_id, (family, opaque, _n) in DUCTS.items():
        gen_duct(block_id, family, opaque)
    gen_attachments()
    gui_textures()
    save(wrench_icon(), "item", "duct_wrench")
    write_json(os.path.join(ASSETS, "models", "item", "duct_wrench.json"),
               {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{MODID}:item/duct_wrench"}})
    gen_recipes()
    gen_tags()
    gen_lang()
    gen_structure("platform", 9, 4, 9)
    print("assets generated under", os.path.normpath(ROOT))


if __name__ == "__main__":
    sys.exit(main())
