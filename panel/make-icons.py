#!/usr/bin/env python3
"""The panel's inventory pictures, made from Minecraft's client jar.

    make-icons.py <client.jar> <dir> [player name]

One png per item in <dir>, named by its id (wheat.png). The panel's game.py runs this once the game has downloaded its
client jar, and again when the bars below are missing; it needs Pillow and numpy.

A flat item (item/generated) is its layers on top of each other, 16×16, with the colour of the item definition over
them (leather, potions). A block is drawn the way the game draws it in the inventory: the elements of its model, turned
by that model's gui setting (mostly 30° and 225°) and lit as the game does (the top in full light, the right side
darkest), 64×64. Chests, shulker boxes, banners, the shield and the heads have no model in the jar: the game draws them
from code, as boxes on an entity texture. Their boxes are written out here as the game's code has them, and turned by
the item definition and the gui setting like any block: a banner is its pole, bar and flag, the flag in its dye colour
(no pattern); the shield its plate and handle (no pattern); a skull or head the head of its mob, the player head
Steve's with the layer over it, the piglin's with its ears and the dragon's whole head with its jaw. What is left (the
conduit, the pot, the copper golem) gets no picture: the panel shows the name in the slot.

Besides: _empty_<kind>.png (the ghost in an empty armour or off-hand slot), _c0.png to _c9.png (the digits of a count,
with their shadow), _heart_, _food_ and _armor_ with empty, half and full (the bars of health, hunger and armour), _air_ with full, bursting
and empty (the bubbles under water) and,
with a player name, _player.png (the front of the skin, flat).
"""
import base64, functools, io, json, math, os, sys, urllib.request, zipfile

import numpy as np
from PIL import Image

JAR = zipfile.ZipFile(sys.argv[1])
OUT = sys.argv[2]
NAMES = set(JAR.namelist())
A = "assets/minecraft/"
SIZE, FINE = 64, 4          # a block is 64×64, drawn four times as fine and then scaled down (smooth edges)


def read(path):
    return json.loads(JAR.read(path))


def bare(name):
    return name.split(":", 1)[-1]


@functools.cache
def model(name):
    """A model with everything of its parents in it: the textures merged, the nearest elements and gui setting."""
    name = bare(name)
    path = f"{A}models/{name}.json"
    if path not in NAMES:
        return {"root": name, "textures": {}}
    m = read(path)
    out = dict(model(m["parent"])) if "parent" in m else {"root": name, "textures": {}}
    out["textures"] = {**out["textures"], **m.get("textures", {})}
    if "elements" in m:
        out["elements"] = m["elements"]
    if "gui" in m.get("display", {}):
        out["gui"] = m["display"]["gui"]
    if "gui_light" in m:
        out["gui_light"] = m["gui_light"]
    return out


@functools.cache
def texture(name):
    """The texture as numbers from 0 to 1 (high, wide, rgba); of a moving texture its first frame."""
    path = f"{A}textures/{bare(name)}.png"
    if path not in NAMES:
        return None
    im = Image.open(io.BytesIO(JAR.read(path))).convert("RGBA")
    if path + ".mcmeta" in NAMES and "animation" in read(path + ".mcmeta") and im.height > im.width:
        im = im.crop((0, 0, im.width, im.width))
    return np.asarray(im, np.float32) / 255


def texture_of(m, ref):
    for _ in range(12):
        if isinstance(ref, dict):
            ref = ref.get("sprite")
        if not isinstance(ref, str):
            return None
        if not ref.startswith("#"):
            return texture(ref)
        ref = m["textures"].get(ref[1:])
    return None


def colour(value):
    if isinstance(value, (list, tuple)):
        return np.array([*value[:3], 1], np.float32)
    return np.array([(value >> 16 & 255) / 255, (value >> 8 & 255) / 255, (value & 255) / 255, 1], np.float32)


def tint(t):
    kind = bare(t["type"])
    if "value" in t:
        return colour(t["value"])
    if "default" in t:
        return colour(t["default"])
    if kind == "grass":                       # the game's colour map, at this temperature and downfall
        colours = texture("colormap/grass")
        temp, rain = t.get("temperature", 0.5), t.get("downfall", 1.0) * t.get("temperature", 0.5)
        return np.array([*colours[int((1 - rain) * 255), int((1 - temp) * 255)][:3], 1], np.float32)
    if kind == "potion":
        return colour(0x385DC6)               # a potion without an effect is water blue
    return np.ones(4, np.float32)


def parts(m, transform=None):
    """Walks the item definition down to the models the game draws in the inventory, for a plain item: not in use,
    not broken, no trim, the gui setting."""
    kind = bare(m["type"])
    transform = m.get("transformation", transform)
    if kind == "model":
        return [("model", m["model"], m.get("tints", []), transform)]
    if kind == "special":
        return [("special", m["model"], m["base"], transform)]
    if kind == "composite":
        return [d for x in m["models"] for d in parts(x, transform)]
    if kind == "condition":
        return parts(m["on_false"], transform)
    if kind == "select":
        if bare(m.get("property", "")) == "display_context":
            for c in m["cases"]:
                if "gui" in (c["when"] if isinstance(c["when"], list) else [c["when"]]):
                    return parts(c["model"], transform)
        return parts(m["fallback"] if "fallback" in m else m["cases"][0]["model"], transform)
    if kind == "range_dispatch":              # the value is 0: the highest threshold that is not above it
        below = [e for e in m["entries"] if e["threshold"] <= 0]
        if below:
            return parts(max(below, key=lambda e: e["threshold"])["model"], transform)
        return parts(m["fallback"] if "fallback" in m else m["entries"][0]["model"], transform)
    return []


def over(under, top):
    a = top[..., 3:4]
    au = under[..., 3:4] * (1 - a)
    alpha = a + au
    rgb = np.where(alpha > 0, (top[..., :3] * a + under[..., :3] * au) / np.maximum(alpha, 1e-6), 0)
    return np.concatenate([rgb, alpha], -1)


def flat(m, tints):
    image = None
    layers = sorted((k for k in m["textures"] if k.startswith("layer") and k[5:].isdigit()), key=lambda k: int(k[5:]))
    for k in layers:
        t = texture_of(m, "#" + k)
        if t is None:
            continue
        i = int(k[5:])
        layer = t * (tint(tints[i]) if i < len(tints) else 1)
        image = layer if image is None or image.shape != layer.shape else over(image, layer)
    return image


# ---------- blocks: the faces of the model, turned and drawn from back to front ----------

def turn(axis, degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def quat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


# The two lamps the game lights a block in the inventory with, in the screen's orientation: the top gets the full
# light, the left side about two thirds and the right side only the ambient light (0.4).
LIGHT = [turn("y", 62) @ turn("x", 185.5) @ turn("y", -22.5) @ turn("x", 135) @ (np.array(v) / np.linalg.norm(v))
         for v in ((0.2, 1.0, -0.7), (-0.2, 1.0, 0.7))]

# per side: the normal and the four corners in the game's order (0 = from, 1 = to, per axis)
SIDES = {
    "down": ((0, -1, 0), ((0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1))),
    "up": ((0, 1, 0), ((0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0))),
    "north": ((0, 0, -1), ((1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0))),
    "south": ((0, 0, 1), ((0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1))),
    "west": ((-1, 0, 0), ((0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1))),
    "east": ((1, 0, 0), ((1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0))),
}


def faces_of_model(m, tints):
    """The faces of a block model: (four corners, uv per corner on the scale 0..16, normal, texture, colour)."""
    out = []
    for el in m.get("elements", []):
        start, end = np.array(el["from"], float), np.array(el["to"], float)
        bounds = (start, end)
        r, origin = np.eye(3), np.zeros(3)
        rot = el.get("rotation")
        if rot:
            origin = np.array(rot.get("origin", [8, 8, 8]), float)
            angles = {rot["axis"]: rot.get("angle", 0)} if "axis" in rot else {a: rot.get(a, 0) for a in "xyz"}
            for a in "xyz":
                if angles.get(a):
                    r = r @ turn(a, angles[a])
            if rot.get("rescale") and "axis" in rot:      # a slanted face is stretched until it fills the whole block again
                f = 1 / math.cos(math.radians(rot.get("angle", 0)))
                r = r @ np.diag([1 if a == rot["axis"] else f for a in "xyz"])
        for side, face in el.get("faces", {}).items():
            normal, corners = SIDES[side]
            tex = texture_of(m, "#" + str(face.get("texture", "")).lstrip("#"))   # the game takes "all" without a hash too
            if tex is None:
                continue
            (x1, y1, z1), (x2, y2, z2) = start, end
            uv = face.get("uv") or {"down": (x1, 16 - z2, x2, 16 - z1), "up": (x1, z1, x2, z2),
                                    "north": (16 - x2, 16 - y2, 16 - x1, 16 - y1), "south": (x1, 16 - y2, x2, 16 - y1),
                                    "west": (z1, 16 - y2, z2, 16 - y1), "east": (16 - z2, 16 - y2, 16 - z1, 16 - y1)}[side]
            corner_uv = ((uv[0], uv[1]), (uv[0], uv[3]), (uv[2], uv[3]), (uv[2], uv[1]))
            quarter = face.get("rotation", 0) // 90
            p = np.array([[bounds[c[a]][a] for a in range(3)] for c in corners])
            p = origin + (p - origin) @ r.T
            ti = face.get("tintindex", -1)
            out.append((p, np.array([corner_uv[(i + quarter) % 4] for i in range(4)], float), r @ np.array(normal, float),
                        tex, tint(tints[ti]) if 0 <= ti < len(tints) else None))
    return out


def box(tex, u0, v0, x, y, z, w, h, d, grow=0.0, mirror=False):
    """A box as the game draws it from code (chest, shulker box, banner, head): the six faces from the unfolded
    texture, in the model's own pixels. grow makes it larger on every side with the same texture (a hat over a head);
    mirror swaps its left and right, texture and all."""
    sx, sy = 16 / tex.shape[1], 16 / tex.shape[0]
    lo, hi = np.array([x, y, z], float) - grow, np.array([x + w, y + h, z + d], float) + grow
    if mirror:
        lo[0], hi[0] = hi[0], lo[0]
    c = {n: np.array([(hi if n[a] == "1" else lo)[a] for a in range(3)])
         for n in ("000", "100", "110", "010", "001", "101", "111", "011")}
    u = [u0, u0 + d, u0 + d + w, u0 + d + 2 * w, u0 + 2 * d + w, u0 + 2 * d + 2 * w]
    v = [v0, v0 + d, v0 + d + h]
    out = []
    for corners, u1, v1, u2, v2, normal in ((("101", "001", "000", "100"), u[1], v[0], u[2], v[1], (0, -1, 0)),
                                            (("110", "010", "011", "111"), u[2], v[1], u[3], v[0], (0, 1, 0)),
                                            (("000", "001", "011", "010"), u[0], v[1], u[1], v[2], (-1, 0, 0)),
                                            (("100", "000", "010", "110"), u[1], v[1], u[2], v[2], (0, 0, -1)),
                                            (("101", "100", "110", "111"), u[2], v[1], u[4], v[2], (1, 0, 0)),
                                            (("001", "101", "111", "011"), u[4], v[1], u[5], v[2], (0, 0, 1))):
        uv = np.array([(u2, v1), (u1, v1), (u1, v2), (u2, v2)], float) * (sx, sy)
        if mirror:                            # the game turns the corners round, so the texture comes out mirrored
            corners, normal = corners[::-1], (-normal[0], normal[1], normal[2])
        out.append((np.array([c[n] for n in corners]), uv, np.array(normal, float), tex, None))
    return out


def pose(faces, offset=(0, 0, 0), rotation=(0, 0, 0), scale=1.0):
    """A part of an entity model put where the game puts it: scaled, turned (radians, z after y after x) and moved."""
    r = turn("z", math.degrees(rotation[2])) @ turn("y", math.degrees(rotation[1])) @ turn("x", math.degrees(rotation[0]))
    return [(np.array(offset, float) + p * scale @ r.T, uv, r @ n, tex, k) for p, uv, n, tex, k in faces]


# The dye colours a banner's flag is drawn in (the texture colour of each dye, which is in the game's code, not a file).
DYES = {"white": 0xF9FFFE, "orange": 0xF9801D, "magenta": 0xC74EBD, "light_blue": 0x3AB3DA, "yellow": 0xFED83D,
        "lime": 0x80C71F, "pink": 0xF38BAA, "gray": 0x474F52, "light_gray": 0x9D9D97, "cyan": 0x169C9C,
        "purple": 0x8932B8, "blue": 0x3C44AA, "brown": 0x835432, "green": 0x5E7C16, "red": 0xB02E26, "black": 0x1D1D21}

# Which mob's texture a head is cut from; zombie and player have the layer over the head as well.
HEADS = {"skeleton": "skeleton/skeleton", "wither_skeleton": "skeleton/wither_skeleton", "zombie": "zombie/zombie",
         "creeper": "creeper/creeper", "piglin": "piglin/piglin", "dragon": "enderdragon/dragon",
         "player": "player/wide/steve"}


def head(kind):
    """A head as the game's head model has it, in the model's own pixels: upside down and facing north, as in a mob
    (the item definition turns it upright, its face to the south)."""
    tex = texture("entity/" + HEADS[kind]) if kind in HEADS else None
    if tex is None:
        return None
    if kind == "piglin":                      # a wider head with a snout, two tusks and two ears that hang out
        return (box(tex, 0, 0, -5, -8, -4, 10, 8, 8) + box(tex, 31, 1, -2, -4, -5, 4, 4, 1)
                + box(tex, 2, 4, 2, -2, -5, 1, 2, 1) + box(tex, 2, 0, -3, -2, -5, 1, 2, 1)
                + pose(box(tex, 51, 6, 0, 0, -2, 1, 5, 4), (4.5, -6, 0), (0, 0, -0.7))
                + pose(box(tex, 39, 6, -1, 0, -2, 1, 5, 4), (-4.5, -6, 0), (0, 0, 0.7)))
    if kind == "dragon":                      # the dragon's head at three quarters, the jaw a little open
        jaw = pose(box(tex, 176, 65, -6, 0, -16, 12, 4, 16), (0, 4, -8), (0.2, 0, 0))
        return pose(box(tex, 176, 44, -6, -1, -24, 12, 5, 16) + box(tex, 112, 30, -8, -8, -10, 16, 16, 16)
                    + box(tex, 0, 0, -5, -12, -4, 2, 4, 6, mirror=True) + box(tex, 112, 0, -5, -3, -22, 2, 2, 4, mirror=True)
                    + box(tex, 0, 0, 3, -12, -4, 2, 4, 6) + box(tex, 112, 0, 3, -3, -22, 2, 2, 4) + jaw,
                    (0, -7.986666, 0), scale=0.75)
    faces = box(tex, 0, 0, -4, -8, -4, 8, 8, 8)
    if kind in ("zombie", "player"):
        faces += box(tex, 32, 0, -4, -8, -4, 8, 8, 8, grow=0.25)
    return faces


def faces_of_special(what):
    kind, name = bare(what["type"]), bare(what.get("texture", ""))
    if kind == "chest":
        tex = texture(f"entity/chest/{name}")
        if tex is not None:
            return box(tex, 0, 19, 1, 0, 1, 14, 10, 14) + box(tex, 0, 0, 1, 9, 1, 14, 5, 14) + box(tex, 0, 0, 7, 7, 15, 2, 4, 1)
    if kind == "shulker_box":
        tex = texture(f"entity/shulker/{name}")
        if tex is not None:
            return box(tex, 0, 28, -8, 16, -8, 16, 8, 16) + box(tex, 0, 0, -8, 8, -8, 16, 12, 16)
    if kind == "banner":                      # the wood of banner_base, the flag's base layer over it in the dye colour
        wood, flag = texture("entity/banner/banner_base"), texture("entity/banner/base")
        if wood is not None and flag is not None:
            tex = over(wood, flag * colour(DYES.get(bare(what.get("color", "white")), DYES["white"])))
            return (box(tex, 44, 0, -1, -42, -1, 2, 42, 2) + box(tex, 0, 42, -10, -44, -1, 20, 2, 2)
                    + box(tex, 0, 0, -10, -44, -2, 20, 40, 1))
    if kind == "shield":                      # without a pattern the game takes the plain wooden texture
        tex = texture("entity/shield/shield_base_nopattern")
        if tex is not None:
            return box(tex, 0, 0, -6, -11, -2, 12, 22, 1) + box(tex, 26, 0, -1, -3, -1, 2, 6, 6)
    if kind in ("head", "player_head"):
        return head(bare(what.get("kind", "player")) if kind == "head" else "player")
    return None


def transformed(faces, transform):
    """The transformation of the item definition (in blocks): turn, scale, turn, shift."""
    if not transform:
        return faces
    if isinstance(transform, list):
        m4 = np.array(transform, float).reshape(4, 4).T
        lin, shift = m4[:3, :3], m4[:3, 3]
    else:
        lin = quat(transform.get("left_rotation", [0, 0, 0, 1])) @ np.diag(transform.get("scale", [1, 1, 1])) @ quat(transform.get("right_rotation", [0, 0, 0, 1]))
        shift = np.array(transform.get("translation", [0, 0, 0]), float)
    nlin = np.linalg.inv(lin).T
    return [(p @ lin.T + shift * 16, uv, nlin @ n, tex, k) for p, uv, n, tex, k in faces]


def draw(faces, gui, side_light):
    """Puts the faces on screen with the model's gui setting. The farthest face first, with the depth per point, so
    that glass and overlapping elements come out right too."""
    rx, ry, rz = gui.get("rotation", [0, 0, 0])
    lin = turn("x", rx) @ turn("y", ry) @ turn("z", rz) @ np.diag(gui.get("scale", [1, 1, 1]))
    shift = np.array(gui.get("translation", [0, 0, 0]), float)
    nlin = np.linalg.inv(lin).T
    n = SIZE * FINE
    image, depth = np.zeros((n, n, 4), np.float32), np.full((n, n), -1e9, np.float32)
    ready = []
    for p, uv, normal, tex, k in faces:
        q = (p - 8) @ lin.T + shift
        nv = nlin @ normal
        nv = nv / (np.linalg.norm(nv) or 1)
        if nv[2] <= 1e-6:                     # the back
            continue
        light = min(1.0, 0.4 + 0.6 * sum(max(0.0, float(l @ nv)) for l in LIGHT)) if side_light else 1.0
        ready.append((q[:, 2].mean(), q, uv, tex, k, light))
    ready.sort(key=lambda v: v[0])
    for _, q, uv, tex, k, light in ready:
        s2 = np.stack([(8 + q[:, 0]) * n / 16, (8 - q[:, 1]) * n / 16], 1)
        a, b = s2[3] - s2[0], s2[1] - s2[0]
        det = a[0] * b[1] - a[1] * b[0]
        if abs(det) < 1e-9:
            continue
        x0, x1 = max(0, int(math.floor(s2[:, 0].min()))), min(n, int(math.ceil(s2[:, 0].max())))
        y0, y1 = max(0, int(math.floor(s2[:, 1].min()))), min(n, int(math.ceil(s2[:, 1].max())))
        if x1 <= x0 or y1 <= y0:
            continue
        ys, xs = np.mgrid[y0:y1, x0:x1] + 0.5
        dx, dy = xs - s2[0, 0], ys - s2[0, 1]
        s, t = (dx * b[1] - dy * b[0]) / det, (a[0] * dy - a[1] * dx) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        u = uv[0, 0] + s * (uv[3, 0] - uv[0, 0]) + t * (uv[1, 0] - uv[0, 0])
        v = uv[0, 1] + s * (uv[3, 1] - uv[0, 1]) + t * (uv[1, 1] - uv[0, 1])
        z = q[0, 2] + s * (q[3, 2] - q[0, 2]) + t * (q[1, 2] - q[0, 2])
        th, tw = tex.shape[:2]
        px = tex[np.clip((v * th / 16).astype(int), 0, th - 1), np.clip((u * tw / 16).astype(int), 0, tw - 1)]
        if k is not None:
            px = px * k
        px = px * np.array([light, light, light, 1], np.float32)
        hit = inside & (px[..., 3] > 0.01) & (z >= depth[y0:y1, x0:x1] - 1e-3)
        piece = image[y0:y1, x0:x1]
        piece[hit] = over(piece, px)[hit]
        depth[y0:y1, x0:x1][hit] = z[hit]
    # scaled down with the colour weighed by coverage, or the edges get a dark rim
    pre = np.concatenate([image[..., :3] * image[..., 3:4], image[..., 3:4]], -1)
    pre = pre.reshape(SIZE, FINE, SIZE, FINE, 4).mean((1, 3))
    return np.concatenate([np.where(pre[..., 3:4] > 0, pre[..., :3] / np.maximum(pre[..., 3:4], 1e-6), 0), pre[..., 3:4]], -1)


def icon(name):
    pieces = parts(read(f"{A}items/{name}.json")["model"])
    flat_image, faces, gui, side_light = None, [], None, True
    for piece in pieces:
        if piece[0] == "model":
            m = model(piece[1])
            if "elements" not in m:
                layer = flat(m, piece[2]) if m["root"] == "builtin/generated" else None
                if layer is not None:
                    flat_image = layer if flat_image is None or flat_image.shape != layer.shape else over(flat_image, layer)
                continue
            new = transformed(faces_of_model(m, piece[2]), piece[3])
        else:
            m = model(piece[2])
            new = faces_of_special(piece[1])
            if new is None:
                continue
            new = transformed(new, piece[3])
        faces += new
        if gui is None:
            gui, side_light = m.get("gui", {}), m.get("gui_light", "side") == "side"
    if faces:
        return draw(faces, gui, side_light)
    return flat_image


def save(image, name):
    Image.fromarray((np.clip(image, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").save(os.path.join(OUT, name + ".png"), optimize=True)


def digits():
    """The digits of a count in the game's font: white with the dark shadow below and to the right."""
    sheet = texture("font/ascii")
    cell = sheet.shape[0] // 16
    for c in range(10):
        glyph = sheet[3 * cell:4 * cell, c * cell:c * cell + 5 * cell // 8, 3:4]
        h, w = glyph.shape[:2]
        step = cell // 8
        image = np.zeros((h + step, w + step, 4), np.float32)
        image[step:, step:] = np.concatenate([np.full((h, w, 3), 0x3f / 255, np.float32), glyph], -1)
        image[:h, :w] = over(image[:h, :w], np.concatenate([np.ones((h, w, 3), np.float32), glyph], -1))
        save(image, f"_c{c}")


def bars():
    """A heart, a drumstick and an armour piece in three states, as above the hotbar: the filling over the empty one."""
    for name, empty, half, full in (("heart", "hud/heart/container", "hud/heart/half", "hud/heart/full"),
                                    ("food", "hud/food_empty", "hud/food_half", "hud/food_full"),
                                    ("armor", "hud/armor_empty", "hud/armor_half", "hud/armor_full")):
        base = texture("gui/sprites/" + empty)
        if base is None:
            continue
        save(base, f"_{name}_empty")
        for state, path in (("half", half), ("full", full)):
            filling = texture("gui/sprites/" + path)
            if filling is not None:
                save(over(base, filling), f"_{name}_{state}")
    # the bubbles stand alone, no empty one under them
    for state, path in (("full", "hud/air"), ("bursting", "hud/air_bursting"), ("empty", "hud/air_empty")):
        bubble = texture("gui/sprites/" + path)
        if bubble is not None:
            save(bubble, f"_air_{state}")


def player(name):
    """The front of the skin, flat: head, body, arms and legs with the layer over them (hat, jacket, sleeves)."""
    def fetch(url):
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "make-icons"}), timeout=15) as r:
            return r.read()
    uuid = json.loads(fetch("https://api.mojang.com/users/profiles/minecraft/" + name))["id"]
    profile = json.loads(fetch("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
    skin = json.loads(base64.b64decode(profile["properties"][0]["value"]))["textures"]["SKIN"]
    slim = (skin.get("metadata") or {}).get("model") == "slim"
    sheet = np.asarray(Image.open(io.BytesIO(fetch(skin["url"].replace("http://", "https://")))).convert("RGBA"), np.float32) / 255
    arm = 3 if slim else 4
    image = np.zeros((32, 16, 4), np.float32)
    # (x, y in the skin), (x, y of the layer over it), width, height, x and y on the figure
    pieces = [((8, 8), (40, 8), 8, 8, 4, 0), ((20, 20), (20, 36), 8, 12, 4, 8), ((44, 20), (44, 36), arm, 12, 4 - arm, 8),
              ((4, 20), (4, 36), 4, 12, 4, 20)]
    if sheet.shape[0] >= 64:
        pieces += [((36, 52), (52, 52), arm, 12, 12, 8), ((20, 52), (4, 52), 4, 12, 8, 20)]
    for (x, y), (ox, oy), w, h, px, py in pieces:
        image[py:py + h, px:px + w] = sheet[y:y + h, x:x + w]
        if sheet.shape[0] >= 64 or (ox, oy) == (40, 8):
            image[py:py + h, px:px + w] = over(image[py:py + h, px:px + w], sheet[oy:oy + h, ox:ox + w])
    if sheet.shape[0] < 64:                   # an old skin of 64×32 has one arm and one leg: mirrored
        image[8:20, 12:16], image[20:32, 8:12] = image[8:20, 3::-1], image[20:32, 7:3:-1]
    save(image, "_player")


def main():
    os.makedirs(OUT, exist_ok=True)
    made, without = 0, []
    for path in sorted(NAMES):
        if not (path.startswith(A + "items/") and path.endswith(".json")):
            continue
        name = path.rsplit("/", 1)[1][:-5]
        try:
            image = icon(name)
        except Exception as e:                # one odd model must not hold up the rest
            print(f"{name}: {type(e).__name__}: {e}", file=sys.stderr)
            image = None
        if image is None or not (image[..., 3] > 0).any():
            without.append(name)
            continue
        save(image, name)
        made += 1
    for kind in ("helmet", "chestplate", "leggings", "boots", "shield"):
        t = texture(f"gui/sprites/container/slot/{kind}")
        if t is not None:
            save(t, "_empty_" + kind)
    digits()
    bars()
    if len(sys.argv) > 3:
        try:
            player(sys.argv[3])
        except Exception as e:
            print(f"the skin of {sys.argv[3]} was not fetched: {type(e).__name__}: {e}", file=sys.stderr)
    print(f"{made} pictures, {len(without)} without: {' '.join(without)}")


if __name__ == "__main__":
    main()
