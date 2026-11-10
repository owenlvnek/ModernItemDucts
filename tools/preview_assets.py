#!/usr/bin/env python3
"""
Renders enlarged contact sheets of the generated art for review without starting the game.

    python3 -I tools/preview_assets.py <output-dir> [old-collar-texture-dir]

Writes ducts.png, duct_views.png, attachments.png, wrench.png and gui.png.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "modernitemducts")
TEX = os.path.join(ROOT, "textures")
DUCTS = ["item_duct", "item_duct_opaque", "fast_item_duct", "fast_item_duct_opaque"]
WEIGHTS = ["normal", "dense", "vacuum"]
KINDS = ["servo", "retriever", "filter"]
TIERS = [("copper", (200, 127, 79)), ("iron", (216, 216, 216)), ("gold", (242, 207, 60)),
         ("diamond", (82, 220, 208)), ("netherite", (110, 90, 102))]
BACKGROUNDS = [(128, 128, 128), (34, 34, 40)]


def tex(*parts):
    return Image.open(os.path.join(TEX, *parts) + ".png").convert("RGBA")


def big(img, k=8):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def on(bg, img):
    base = Image.new("RGBA", img.size, bg + (255,))
    base.alpha_composite(img)
    return base


def ducts_sheet(out):
    """Each duct's three cross-shaped sheets plus its collar sheet, 8x, on two backgrounds."""
    cell, pad = 128, 8
    sheet = Image.new("RGBA", (pad + 4 * (cell + pad), pad + 8 * (cell + pad)), (255, 255, 255, 255))
    for bi, bg in enumerate(BACKGROUNDS):
        for r, d in enumerate(DUCTS):
            row = [tex("block", f"{d}_{w}") for w in WEIGHTS] + [tex("block", f"{d}_collar")]
            for c, t in enumerate(row):
                sheet.paste(on(bg, big(t)), (pad + c * (cell + pad), pad + (bi * 4 + r) * (cell + pad)))
    sheet.save(os.path.join(out, "ducts.png"))


def front(duct, weight, conns):
    """Front (north) face of a duct block at 1 texel = 1 px: the core square plus the sheet strip of
    every connected in-plane direction. This is exactly what 1:1 footprint UVs show in game."""
    s = tex("block", f"{duct}_{weight}")
    arm = tex("block", f"{duct}_normal")
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    boxes = {"up": (5, 0, 11, 5), "down": (5, 11, 11, 16), "east": (0, 5, 5, 11), "west": (11, 5, 16, 11)}
    for c in conns:
        img.alpha_composite(arm.crop(boxes[c]), boxes[c][:2])
    img.alpha_composite(s.crop((5, 5, 11, 11)), (5, 5))
    return img


def plate_front(kind, state, tint):
    """Front of a placed cap at 1:1: tinted plate, tinted face panel, untinted glyph layer."""
    body = tinted(tex("block", "attachment_body"), tint).crop((4, 4, 12, 12))
    panel = tinted(tex("block", "attachment_panel"), tint).crop((5, 5, 11, 11))
    mark = tex("block", f"{kind}_{state}_indicator" if state else f"{kind}_indicator").crop((5, 5, 11, 11))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    img.alpha_composite(body, (4, 4))
    img.alpha_composite(panel, (5, 5))
    img.alpha_composite(mark, (5, 5))
    return img


def scene(duct, weight):
    """A 6x3-block wall, seen face-on: a duct run with a T-junction, ends at two inventories,
    a servo plate on the left end. Background = stone-ish grey, item visible in clear ducts."""
    W, H = 6, 3
    img = Image.new("RGBA", (W * 16, H * 16), (125, 125, 125, 255))
    px = img.load()
    for y in range(H * 16):  # simple stone noise so the backdrop is not flat
        for x in range(W * 16):
            if (x * 7 + y * 13) % 11 == 0:
                px[x, y] = (112, 112, 112, 255)
    chest = Image.new("RGBA", (16, 16), (150, 108, 52, 255))
    ImageDraw.Draw(chest).rectangle([0, 0, 15, 15], outline=(70, 46, 20, 255))
    ImageDraw.Draw(chest).rectangle([6, 5, 9, 8], fill=(200, 200, 200, 255))
    layout = {(0, 1): "chest", (1, 1): ["west", "east"], (2, 1): ["west", "east", "down"], (3, 1): ["west", "east"],
              (4, 1): ["west", "east"], (5, 1): "chest", (2, 2): ["up"]}
    for (bx, by), what in layout.items():
        if what == "chest":
            img.alpha_composite(chest, (bx * 16, by * 16))
            continue
        conns = ["east" if c == "west" else "west" if c == "east" else c for c in what]  # viewer's left/right
        f = front(duct, weight, conns)
        if not duct.endswith("opaque"):
            item = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            if (bx, by) == (3, 1):
                ImageDraw.Draw(item).rectangle([6, 6, 9, 9], fill=(80, 210, 235, 255))
            item.alpha_composite(f)
            f = item
        img.alpha_composite(f, (bx * 16, by * 16))
    img.alpha_composite(plate_front("servo", "active", (200, 127, 79)), (16, 16))
    return img


def duct_views(out):
    """On-screen scale mocks: each duct type in a small scene at 2x and 4x (GUI-scale-like zoom)."""
    rows = [(d, w) for d in DUCTS for w in ("normal",)] + [("item_duct", "dense"), ("fast_item_duct", "vacuum")]
    pad = 8
    sc = [scene(d, w) for d, w in rows]
    w4 = sc[0].width * 4
    sheet = Image.new("RGBA", (pad * 3 + sc[0].width * 2 + w4, pad + len(sc) * (sc[0].height * 4 + pad)), (255, 255, 255, 255))
    for i, s_ in enumerate(sc):
        y = pad + i * (s_.height * 4 + pad)
        sheet.paste(big(s_, 2), (pad, y))
        sheet.paste(big(s_, 4), (pad * 2 + s_.width * 2, y))
    sheet.save(os.path.join(out, "duct_views.png"))


def tinted(img, tint):
    px = img.load()
    out = img.copy()
    o = out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            o[x, y] = (r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255, a)
    return out


def attachments_sheet(out):
    cell, pad = 128, 8
    cols = 5
    rows = 3 + 3  # item icons per kind, then block fronts (idle/active/clogged)
    sheet = Image.new("RGBA", (pad + cols * (cell + pad), pad + rows * (cell + pad) + 40), (255, 255, 255, 255))
    for r, k in enumerate(KINDS):
        for c, (mat, _t) in enumerate(TIERS):
            sheet.paste(on(BACKGROUNDS[0], big(tex("item", f"item_{k}_{mat}"))), (pad + c * (cell + pad), pad + r * (cell + pad)))
    states = ["idle", "active", "clogged"]
    for r, k in enumerate(KINDS):
        for c, (mat, tint) in enumerate(TIERS):
            state = states[c % 3] if k != "filter" else None
            face = Image.new("RGBA", (16, 16), BACKGROUNDS[1] + (255,))
            face.alpha_composite(plate_front(k, state, tint))
            sheet.paste(big(face), (pad + c * (cell + pad), pad + (3 + r) * (cell + pad)))
    sheet.save(os.path.join(out, "attachments.png"))


def wrench(out):
    w = tex("item", "duct_wrench")
    sheet = Image.new("RGBA", (2 * 136, 136), (255, 255, 255, 255))
    for i, bg in enumerate(BACKGROUNDS):
        sheet.paste(on(bg, big(w)), (4 + i * 136, 4))
    sheet.save(os.path.join(out, "wrench.png"))


def gui_mock(out):
    """Approximates AttachmentScreen for a T5 servo (5x3 grid, 6 toggles, both numerics)."""
    panel = tex("gui", "attachment")
    wid = tex("gui", "widgets")
    W, H = 176, 204
    canvas = Image.new("RGBA", (W + 40, H + 8), (60, 60, 70, 255))
    ox, oy = 32, 4

    def blit(u, v, w, h, x, y, src=wid):
        canvas.alpha_composite(src.crop((u, v, u + w, v + h)), (ox + x, oy + y))

    blit(0, 60, 27, 52, -24, 2)   # side tab (panel drawn over its open edge)
    blit(0, 0, W, H, 0, 0, panel)
    d = ImageDraw.Draw(canvas)
    d.text((ox + 8, oy + 5), "Netherite Item Servo", fill=(64, 64, 64, 255))
    d.line([(ox + 8, oy + 16), (ox + 167, oy + 16)], fill=(110, 90, 102, 255))
    cols, rows, top = 5, 3, 20
    left = 88 - cols * 9
    for r in range(rows):
        for c in range(cols):
            blit(18, 0, 18, 18, left + c * 18, top + r * 18)
    canvas.alpha_composite(big(tex("item", "item_servo_diamond"), 1), (ox + left + 1, oy + top + 1))
    y = top + rows * 18 + 5
    icons = [1, 3, 5, 7, 9, 13]
    states = [4, 0, 0, 0, 1, 0]
    x = (W - (len(icons) * 26 - 6)) // 2
    for ic, st in zip(icons, states):
        blit(st * 20, 20, 20, 20, x, y)
        blit(ic * 12, 120, 12, 12, x + 4, y + 4)
        x += 26
    for (bx, label) in ((W - 31, "64"), (17, "320")):
        blit(0, 40, 14, 14, bx, 19)
        blit(0 * 12 + 1, 133, 10, 10, bx + 2, 21)
        blit(0, 148, 24, 12, bx - 5, 36)
        d.text((ox + bx + 7 - 3 * len(label), oy + 37), label, fill=(48, 48, 48, 255))
        blit(14 * 3, 40, 14, 14, bx, 53) if label == "320" else blit(0, 40, 14, 14, bx, 53)
        blit(1 * 12 + 1, 133, 10, 10, bx + 2, 55)
    blit(0, 20, 20, 20, -22, 6)
    blit(19 * 12, 120, 12, 12, -18, 10)
    blit(20, 20, 20, 20, -22, 30)
    blit(18 * 12, 120, 12, 12, -18, 34)
    d.text((ox + 8, oy + H - 94), "Inventory", fill=(64, 64, 64, 255))
    big(canvas, 3).save(os.path.join(out, "gui.png"))
    big(wid.crop((0, 0, 256, 160)), 3).save(os.path.join(out, "gui_widgets.png"))


SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def face_corners(face, f, t):
    """Corners of a model face in block pixels, ordered to match (u0,v0) (u1,v0) (u0,v1) uv origin."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x0, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z1)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x0, y0, z0)],
    }[face]


def render_item(model_name, size, rot=None, scale=None):
    """Tiny orthographic software renderer for an item model as it sits in a GUI slot:
    display rotation, back-face culling (like the game), painter's order, nearest sampling."""
    m = json.load(open(os.path.join(ROOT, "models", "item", model_name + ".json")))
    tex_ref = m["textures"]
    gui = m.get("display", {}).get("gui", {"rotation": [30, 225, 0], "scale": [0.625] * 3})
    rot = rot or gui["rotation"]
    scale = scale or gui["scale"][0]
    rx, ry = math.radians(rot[0]), math.radians(rot[1])

    def xf(p):
        x, y, z = (p[0] - 8, p[1] - 8, p[2] - 8)
        x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
        y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
        k = scale * size / 16
        return (size / 2 + x * k, size / 2 - y * k, z)

    faces = []
    for e in m["elements"]:
        for fname, fd in e["faces"].items():
            n = xf(tuple(8 + v for v in NORMAL[fname]))
            o = xf((8, 8, 8))
            if n[2] - o[2] <= 0:  # facing away from the viewer (+z towards camera): culled
                continue
            a, b, c = (xf(p) for p in face_corners(fname, e["from"], e["to"]))
            texname = fd["texture"].lstrip("#")
            img = tex(*tex_ref[texname].split(":")[1].split("/"))
            faces.append(((a[2] + b[2] + c[2]) / 3, a, b, c, img, fd["uv"], SHADE[fname]))
    faces.sort(key=lambda f: f[0])  # far first
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = out.load()
    for _d, a, b, c, img, uv, shade in faces:
        ux, uy = b[0] - a[0], b[1] - a[1]
        vx, vy = c[0] - a[0], c[1] - a[1]
        det = ux * vy - uy * vx
        if abs(det) < 1e-6:
            continue
        tp = img.load()
        for y in range(size):
            for x in range(size):
                dx, dy = x + 0.5 - a[0], y + 0.5 - a[1]
                s_ = (dx * vy - dy * vx) / det
                t_ = (ux * dy - uy * dx) / det
                if 0 <= s_ < 1 and 0 <= t_ < 1:
                    tu = int(uv[0] + s_ * (uv[2] - uv[0]))
                    tv = int(uv[1] + t_ * (uv[3] - uv[1]))
                    r, g, bb, al = tp[min(15, tu), min(15, tv)]
                    if al:
                        px[x, y] = (int(r * shade), int(g * shade), int(bb * shade), 255)
    return out


def item_slots(out):
    """Inventory-slot mock: every duct item in a vanilla-looking slot at 2x and 4x."""
    names = [f"{d}{s}" for d in DUCTS for s in ("", "_dense", "_vacuum")]
    rows = []
    for k in (2, 4):
        slot_px = 18 * k
        row = Image.new("RGBA", (len(names) * (slot_px + 4 * k) + 4 * k, slot_px + 8 * k), (198, 198, 198, 255))
        d = ImageDraw.Draw(row)
        for i, n in enumerate(names):
            x0, y0 = 4 * k + i * (slot_px + 4 * k), 4 * k
            d.rectangle([x0, y0, x0 + slot_px - 1, y0 + slot_px - 1], fill=(139, 139, 139, 255))
            d.line([(x0, y0), (x0 + slot_px - 1, y0)], fill=(55, 55, 55, 255), width=k)
            d.line([(x0, y0), (x0, y0 + slot_px - 1)], fill=(55, 55, 55, 255), width=k)
            item = render_item(n, 16 * 8).resize((16 * k, 16 * k), Image.NEAREST)
            row.alpha_composite(item, (x0 + k, y0 + k))
        rows.append(row)
    sheet = Image.new("RGBA", (max(r.width for r in rows), sum(r.height for r in rows) + 8), (255, 255, 255, 255))
    y = 0
    for r in rows:
        sheet.paste(r, (0, y))
        y += r.height + 8
    sheet.save(os.path.join(out, "item_slots.png"))
    big(render_item("item_duct", 16 * 16), 1).save(os.path.join(out, "item_duct_closeup.png"))


def attachment_mocks(out):
    """Each attachment capping a vertical duct, seen face-on, in idle / active / clogged,
    on a stone-ish backdrop at 2x and 4x (roughly on-screen size at GUI scale 2-4)."""
    cases = [("servo", st) for st in ("idle", "active", "clogged")] + \
            [("retriever", st) for st in ("idle", "active", "clogged")] + [("filter", None)]
    tiers = [TIERS[0], TIERS[2], TIERS[4]]
    tiles = []
    for mat, tint in tiers:
        row = []
        for kind, st in cases:
            t = Image.new("RGBA", (16, 16), (125, 125, 125, 255))
            tp = t.load()
            for y in range(16):
                for x in range(16):
                    if (x * 7 + y * 13) % 11 == 0:
                        tp[x, y] = (112, 112, 112, 255)
            t.alpha_composite(front("item_duct", "normal", ["up", "down"]))
            t.alpha_composite(plate_front(kind, st, tint))
            row.append(t)
        tiles.append(row)
    pad = 6
    for k in (2, 4):
        cw = 16 * k
        sheet = Image.new("RGBA", (pad + len(cases) * (cw + pad), pad + len(tiers) * (cw + pad)), (255, 255, 255, 255))
        for r, row in enumerate(tiles):
            for c, t in enumerate(row):
                sheet.paste(big(t, k), (pad + c * (cw + pad), pad + r * (cw + pad)))
        sheet.save(os.path.join(out, f"attachments_on_duct_{k}x.png"))


def load_model(rel, textures_dir=None, tint=None):
    """Faces of a block/item model as (face, from, to, uv, image, tinted?) with textures resolved."""
    m = json.load(open(os.path.join(ROOT, "models", rel + ".json")))
    out = []
    for e in m["elements"]:
        for fname, fd in e["faces"].items():
            ref = m["textures"][fd["texture"].lstrip("#")].split(":")[1]
            path = None
            if textures_dir:
                cand = os.path.join(textures_dir, os.path.basename(ref) + ".png")
                path = cand if os.path.exists(cand) else None
            img = Image.open(path).convert("RGBA") if path else tex(*ref.split("/"))
            if tint and "tintindex" in fd:
                img = tinted(img, tint)
            out.append((fname, e["from"], e["to"], fd["uv"], img))
    return out


def solid_box(frm, to, color):
    img = Image.new("RGBA", (16, 16), color + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=tuple(int(c * 0.6) for c in color) + (255,))
    return [(f, frm, to, [0, 0, 16, 16], img) for f in ("up", "south", "east", "north", "west", "down")]


def render_scene(faces, size, rot=(30, 225), scale=0.45, center=(8, 8, 8)):
    """Orthographic z-buffered software render with back-face culling and vanilla-like shading."""
    rx, ry = math.radians(rot[0]), math.radians(rot[1])

    def xf(p):
        x, y, z = (p[0] - center[0], p[1] - center[1], p[2] - center[2])
        x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
        y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
        k = scale * size / 16
        return (size / 2 + x * k, size / 2 - y * k, z)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = out.load()
    zbuf = [[-1e9] * size for _ in range(size)]
    o = xf((0, 0, 0))
    for fname, f, t, uv, img in faces:
        n = xf(tuple(v for v in NORMAL[fname]))
        if n[2] - o[2] <= 0:
            continue
        a, b, c = (xf(p) for p in face_corners(fname, f, t))
        ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], c[0] - a[0], c[1] - a[1]
        det = ux * vy - uy * vx
        if abs(det) < 1e-9:
            continue
        xs = [a[0], b[0], c[0], b[0] + vx]
        ys = [a[1], b[1], c[1], b[1] + vy]
        tp = img.load()
        sh = SHADE[fname]
        for y in range(max(0, int(min(ys))), min(size, int(max(ys)) + 1)):
            for x in range(max(0, int(min(xs))), min(size, int(max(xs)) + 1)):
                dx, dy = x + 0.5 - a[0], y + 0.5 - a[1]
                s_ = (dx * vy - dy * vx) / det
                t_ = (ux * dy - uy * dx) / det
                if not (0 <= s_ < 1 and 0 <= t_ < 1):
                    continue
                z = a[2] + s_ * (b[2] - a[2]) + t_ * (c[2] - a[2])
                if z <= zbuf[y][x]:
                    continue
                tu = int(uv[0] + s_ * (uv[2] - uv[0]))
                tv = int(uv[1] + t_ * (uv[3] - uv[1]))
                r, g, bb, al = tp[max(0, min(15, tu)), max(0, min(15, tv))]
                if al:
                    zbuf[y][x] = z
                    px[x, y] = (int(r * sh), int(g * sh), int(bb * sh), 255)
    return out


def collar_before_after(out, before_dir):
    """Duct (core + north arm with collar + south arm) against a chest-coloured block to the north,
    no attachment. Before (old collar sheets) vs after, standard and fast, clear and opaque."""
    chest = solid_box([0, 0, -16], [16, 16, 0], (150, 108, 52))
    tiles = []
    for d in DUCTS:
        row = []
        for tdir in (before_dir, None):
            faces = chest + load_model(f"block/{d}_core_normal") + load_model(f"block/{d}_arm_inventory", tdir)
            # the arm models point north already; add a plain arm to the south by mirroring z
            for (fn, f, t, uv, img) in load_model(f"block/{d}_arm"):
                faces.append((fn, [f[0], f[1], 16 - t[2]], [t[0], t[1], 16 - f[2]], uv, img))
            row.append(render_scene(faces, 256, rot=(30, 30), scale=0.5, center=(8, 8, 0)))
        tiles.append(row)
    pad = 8
    sheet = Image.new("RGBA", (pad + 2 * (256 + pad), pad + len(tiles) * (256 + pad)), (90, 90, 96, 255))
    for r, row in enumerate(tiles):
        for c, t in enumerate(row):
            sheet.alpha_composite(t, (pad + c * (256 + pad), pad + r * (256 + pad)))
    sheet.save(os.path.join(out, "collar_before_after.png"))


def attachment_side_mocks(out):
    """Each attachment cap on a duct pressed against a solid block, seen from the side/above
    (the inventory hides the front face; the side glyphs carry the state)."""
    block = solid_box([0, 0, -16], [16, 16, 0], (120, 120, 126))
    cases = [("servo", s) for s in ("idle", "active", "clogged")] + [("retriever", s) for s in ("idle", "active", "clogged")] + [("filter", None)]
    tiles = []
    for tier in TIERS:
      for kind, st in cases:
        faces = block + load_model("block/item_duct_core_normal") + load_model("block/item_duct_arm_inventory")
        name = f"block/attachment/item_{kind}_{st}" if st else f"block/attachment/item_{kind}"
        faces += load_model(name, tint=tier[1])
        tiles.append(render_scene(faces, 192, rot=(35, 35), scale=0.6, center=(8, 8, 2)))
    pad = 6
    n = len(cases)
    sheet = Image.new("RGBA", (pad + n * (192 + pad), pad + len(TIERS) * (192 + pad)), (90, 90, 96, 255))
    for i, t in enumerate(tiles):
        sheet.alpha_composite(t, (pad + (i % n) * (192 + pad), pad + (i // n) * (192 + pad)))
    sheet.save(os.path.join(out, "attachments_side_view.png"))
    small = sheet.resize((sheet.width // 3, sheet.height // 3), Image.NEAREST)
    small.save(os.path.join(out, "attachments_side_view_small.png"))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "preview"
    os.makedirs(out, exist_ok=True)
    ducts_sheet(out)
    duct_views(out)
    attachments_sheet(out)
    wrench(out)
    gui_mock(out)
    item_slots(out)
    attachment_mocks(out)
    attachment_side_mocks(out)
    before = sys.argv[2] if len(sys.argv) > 2 else None
    if before:
        collar_before_after(out, before)
    print("previews written to", out)


if __name__ == "__main__":
    main()
