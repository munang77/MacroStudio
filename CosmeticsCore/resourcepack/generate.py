#!/usr/bin/env python3
"""
CosmeticsCore 기본 리소스팩 생성기.

  python3 resourcepack/generate.py

만드는 것
  src/main/resources/pack/                플러그인에 들어가는 리소스팩 (모델, 텍스처, 아이템 정의)
  resourcepack/blockbench/<모델>.bbmodel  블록벤치에서 바로 열어 고칠 수 있는 원본 (텍스처 포함)

모델은 아이템의 custom_model_data 로 바꿔 끼운다.
  1.21 ~ 1.21.3 : assets/minecraft/models/item/<아이템>.json 의 overrides
  1.21.4 이상   : assets/minecraft/items/<아이템>.json 의 range_dispatch
리소스팩이 없으면 원래 아이템 모양 그대로 보인다.

좌표 약속 (모델 픽셀, 0~16 이 한 칸): 북쪽(-z)이 몸 앞, 동쪽(+x)이 몸 오른쪽, 위(+y)가 위.
몸 장식은 모델 가운데 (8, 8, 8) 이 붙는 자리이자 돌아가는 중심이다.
"""

import base64
import json
import math
import os
import shutil
import uuid

from pixels import Canvas, bezier, hex_color, mix, shade

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(HERE)
PACK = os.path.join(PLUGIN, "src", "main", "resources", "pack")
BLOCKBENCH = os.path.join(HERE, "blockbench")
NS = "cosmeticscore"
FACES = ("north", "east", "south", "west", "up", "down")


# ── 모델 만들기 ───────────────────────────────────────────


class Model:
    """블록벤치 java_block 형식과 같은 요소 모델 하나와 그 텍스처."""

    def __init__(self, name, base, cmd, tex=64, kind="body"):
        self.name = name
        self.base = base          # 바꿔 끼울 바닐라 아이템
        self.cmd = cmd            # custom_model_data
        self.kind = kind          # body / head / float
        self.canvas = Canvas(tex, tex)
        self.tex = tex
        self.elements = []
        self.swatches = {}
        self._next = [0, 0]       # 다음 견본 자리
        self.icon_rotation = None  # 메뉴 아이콘 각도 (없으면 앞모습)

    # 텍스처 견본: 1 모델 픽셀 = 1 텍셀 이 되도록 16x16 칸을 하나씩 쓴다
    def swatch(self, name, base, spread=0.1, seed=1, stripes=None, painter=None, size=16):
        x, y = self._next
        if x + size > self.tex:
            x, y = 0, y + size
        self._next = [x + size, y]
        if painter:
            painter(self.canvas, x, y, size)
        else:
            self.canvas.noise_rect(x, y, x + size, y + size, hex_color(base) if isinstance(base, str) else base,
                                   spread, seed, stripes)
        self.swatches[name] = (x, y, size)
        return name

    def _uv(self, swatch, width, height, flip=False):
        sx, sy, size = self.swatches[swatch]
        w = min(max(width, 0.5), size)
        h = min(max(height, 0.5), size)
        k = 16.0 / self.tex
        u1, v1, u2, v2 = sx * k, sy * k, (sx + w) * k, (sy + h) * k
        if flip:
            u1, u2 = u2, u1
        return [round(u1, 4), round(v1, 4), round(u2, 4), round(v2, 4)]

    def box(self, frm, to, swatch, rotation=None, faces=None, name=None, skip=()):
        """상자 하나. faces 로 면마다 다른 견본을 줄 수 있다."""
        faces = faces or {}
        dx, dy, dz = (to[i] - frm[i] for i in range(3))
        sizes = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                 "up": (dx, dz), "down": (dx, dz)}
        el = {"name": name or f"part{len(self.elements)}", "from": list(frm), "to": list(to), "faces": {}}
        for face in FACES:
            if face in skip:
                continue
            w, h = sizes[face]
            el["faces"][face] = {"uv": self._uv(faces.get(face, swatch), w, h), "texture": "#0"}
        if rotation:
            el["rotation"] = rotation
        self.elements.append(el)
        return el

    def plane(self, frm, to, uv=(0, 0, 16, 16), name=None):
        """두께 없는 판 (날개). 뒤(남쪽)에서 본 그림이 텍스처 그대로, 앞(북쪽)은 좌우를 뒤집어 같은 모양이 되게."""
        u1, v1, u2, v2 = uv
        el = {"name": name or "plane", "from": list(frm), "to": list(to), "faces": {
            "south": {"uv": [u1, v1, u2, v2], "texture": "#0"},
            "north": {"uv": [u2, v1, u1, v2], "texture": "#0"},
        }}
        self.elements.append(el)
        return el

    # ── 내보내기 ──

    def bounds(self):
        lo = [99, 99, 99]
        hi = [-99, -99, -99]
        for el in self.elements:
            for i in range(3):
                lo[i] = min(lo[i], el["from"][i], el["to"][i])
                hi[i] = max(hi[i], el["from"][i], el["to"][i])
        return lo, hi

    def display(self):
        """메뉴 아이콘이 칸에 맞게 보이도록 gui 변환만 정한다 (몸 장식·머리는 기본값 그대로 쓴다)."""
        lo, hi = self.bounds()
        extent = max(hi[i] - lo[i] for i in range(3))
        flat = any(hi[i] - lo[i] < 0.01 for i in range(3))
        rot = self.icon_rotation or ([0, 0, 0] if flat else [25, 200, 0])
        scale = round(min(1.0, 0.95 * 16 / extent) * (0.88 if flat else 0.8), 3)
        center = [(lo[i] + hi[i]) / 2 - 8 for i in range(3)]
        rx, ry, rz = (math.radians(a) for a in rot)
        # 가운데를 칸 가운데로: translation = -(R · S · center)
        v = [c * scale for c in center]
        v = rot_z(rot_y(rot_x(v, rx), ry), rz)
        translation = [round(max(-80, min(80, -c)), 3) for c in v]
        return {"gui": {"rotation": rot, "translation": translation, "scale": [scale] * 3},
                "ground": {"translation": [0, 2, 0], "scale": [0.4] * 3},
                "thirdperson_righthand": {"scale": [0.4] * 3},
                "firstperson_righthand": {"scale": [0.4] * 3}}

    def model_json(self):
        texture = f"{NS}:item/{self.name}"
        return {
            "credit": "CosmeticsCore (resourcepack/generate.py)",
            "texture_size": [self.tex, self.tex],
            "textures": {"0": texture, "particle": texture},
            "elements": self.elements,
            "display": self.display(),
        }

    def bbmodel(self):
        png = self.canvas.png()
        tex_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{NS}/{self.name}/texture"))
        elements = []
        for i, el in enumerate(self.elements):
            rot = el.get("rotation")
            element = {
                "name": el["name"], "box_uv": False, "rescale": False, "locked": False,
                "render_order": "default", "allow_mirror_modeling": True,
                "from": el["from"], "to": el["to"], "autouv": 0, "color": i % 8,
                "origin": rot["origin"] if rot else [(el["from"][k] + el["to"][k]) / 2 for k in range(3)],
                "faces": {f: {"uv": d["uv"], "texture": 0} for f, d in el["faces"].items()},
                "type": "cube",
                "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{NS}/{self.name}/{i}")),
            }
            if rot:
                element["rotation"] = [rot["angle"] if rot["axis"] == a else 0 for a in "xyz"]
            elements.append(element)
        return {
            "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
            "name": self.name, "parent": "", "ambientocclusion": True, "front_gui_light": False,
            "visible_box": [1, 1, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
            "unhandled_root_fields": {}, "resolution": {"width": 16, "height": 16},
            "elements": elements, "outliner": [e["uuid"] for e in elements],
            "textures": [{
                "path": "", "name": f"{self.name}.png", "folder": "item", "namespace": NS, "id": "0",
                "width": self.tex, "height": self.tex, "uv_width": 16, "uv_height": 16, "particle": False,
                "layers_enabled": False, "render_mode": "default", "render_sides": "auto", "frame_time": 1,
                "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False, "visible": True,
                "internal": True, "saved": True, "uuid": tex_uuid,
                "source": "data:image/png;base64," + base64.b64encode(png).decode("ascii"),
            }],
            "display": self.display(),
        }


def rot_x(v, a):
    x, y, z = v
    return [x, y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)]


def rot_y(v, a):
    x, y, z = v
    return [x * math.cos(a) + z * math.sin(a), y, -x * math.sin(a) + z * math.cos(a)]


def rot_z(v, a):
    x, y, z = v
    return [x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a), z]


def rotation(axis, angle, origin):
    return {"angle": angle, "axis": axis, "origin": list(origin)}


# ── 날개 (두께 없는 판에 실루엣 텍스처) ─────────────────────
#
# 판: x 8~32 (붙는 곳이 x=8), y -4~20. 텍스처 64x64 에서 붙는 곳은 왼쪽 가운데 (0, 32).
# 날개는 붙는 곳보다 위로 솟고(어깨 위), 아래로는 허리까지만 내려온다.

WING_FROM = (8, -4, 8)
WING_TO = (32, 20, 8)
HINGE = (1.5, 33)


def feather(c, base, tip, length, angle, width, fill, edge):
    x1 = base[0] + math.cos(angle) * length
    y1 = base[1] + math.sin(angle) * length
    c.capsule(base[0], base[1], x1, y1, width + 0.9, width * 0.45 + 0.9, edge)
    c.capsule(base[0], base[1], x1, y1, width, width * 0.45, fill,
              lambda x, y: mix(fill, tip, math.hypot(x - base[0], y - base[1]) / max(length, 1)))


def angel_wing():
    m = Model("angel_wing", "minecraft:feather", 7130001, 64)
    c = m.canvas
    white = hex_color("F8FAFF")
    tip = hex_color("D5DFF2")
    edge = hex_color("A3AFCB")
    arm = [HINGE, (20, 2), (62, 4)]
    # 긴 날개깃 (바깥쪽, 맨 뒤): 끝으로 갈수록 바깥으로 벌어진다
    for i in range(8):
        t = 0.5 + i * 0.066
        b = bezier(*arm, t)
        feather(c, b, tip, 44 - i * 2.4, math.radians(72 - i * 4.5), 3.6, white, edge)
    # 둘째 날개깃
    for i in range(9):
        t = 0.03 + i * 0.058
        b = bezier(*arm, t)
        feather(c, b, tip, 25 + i * 1.9, math.radians(97 - i * 2.2), 3.5, white, edge)
    # 덮깃 (짧고 둥글게)
    for i in range(13):
        t = 0.02 + i * 0.075
        b = bezier(*arm, t)
        feather(c, b, tip, 10 - abs(i - 6) * 0.35, math.radians(95 - i * 3.5), 3.0, hex_color("FFFFFF"), edge)
    # 윗가장자리 뼈대
    for i in range(48):
        t = i / 47
        x, y = bezier(*arm, t)
        r = 2.4 - t * 1.4
        c.capsule(x, y, x, y, r, r, hex_color("EEF2FB"))
    m.plane(WING_FROM, WING_TO, name="wing")
    return m


def demon_wing():
    m = Model("demon_wing", "minecraft:feather", 7130002, 64)
    c = m.canvas
    hinge = HINGE
    wrist = (24, 6)
    tips = [(62, 2), (62, 22), (54, 42), (40, 56), (22, 61)]
    membrane = hex_color("5B0E22")
    membrane_light = hex_color("8E1E36")
    bone = hex_color("2B0A12")
    # 막: 손목, 손가락 끝, 사이는 안쪽으로 파인 곡선
    points = [hinge, wrist]
    for i, tp in enumerate(tips):
        points.append(tp)
        if i + 1 < len(tips):
            nxt = tips[i + 1]
            mid = ((tp[0] + nxt[0]) / 2, (tp[1] + nxt[1]) / 2)
            inward = ((mid[0] * 2 + wrist[0]) / 3, (mid[1] * 2 + wrist[1]) / 3)
            for k in range(1, 8):
                points.append(bezier(tp, inward, nxt, k / 8))
    for k in range(1, 8):
        points.append(bezier(tips[-1], (8, 52), (2, 42), k / 8))
    c.polygon(points, membrane, lambda x, y: mix(membrane_light, membrane, math.hypot(x - wrist[0], y - wrist[1]) / 55))
    c.outline(hex_color("33060F"))
    # 뼈
    c.capsule(hinge[0], hinge[1], wrist[0], wrist[1], 2.6, 2.0, bone)
    for tp in tips:
        c.capsule(wrist[0], wrist[1], tp[0], tp[1], 1.6, 0.6, bone)
    c.capsule(wrist[0], wrist[1], wrist[0] - 3, wrist[1] - 4, 1.4, 0.5, hex_color("D8CFC0"))
    m.plane(WING_FROM, WING_TO, name="wing")
    return m


def butterfly_wing():
    m = Model("butterfly_wing", "minecraft:feather", 7130003, 64)
    c = m.canvas
    inner = hex_color("54E6FF")
    outer = hex_color("1B3FE6")
    black = hex_color("10121A")
    hx, hy = HINGE

    def curve(*pts, steps=12):
        out = []
        for i in range(0, len(pts) - 2, 2):
            for k in range(steps):
                out.append(bezier(pts[i], pts[i + 1], pts[i + 2], k / steps))
        out.append(pts[-1])
        return out

    # 앞날개: 붙는 곳에서 오른쪽 위 꼭짓점까지 솟았다가 바깥 가장자리를 따라 내려온다
    fore = curve((hx, hy - 1), (16, 8), (54, 3), (66, 14), (50, 33), (26, 37), (hx, hy + 1))
    # 뒷날개: 아래로 둥글게
    hind = curve((hx, hy), (34, 30), (50, 44), (52, 62), (30, 60), (6, 54), (hx, hy + 2))
    from pixels import point_in_polygon

    def inside(x, y):
        return point_in_polygon(x, y, fore) or point_in_polygon(x, y, hind)

    c.fill_shape(inside, inner, lambda x, y: mix(inner, outer, math.hypot(x - hx, y - hy) / 50))
    # 날개맥
    for ang in (-1.25, -0.95, -0.62, -0.3, 0.05, 0.45, 0.85, 1.2):
        c.line(hx, hy, hx + math.cos(ang) * 60, hy + math.sin(ang) * 60, hex_color("0E2A8A", 140), 0.8)
    # 검은 테두리 (가장자리에서 3칸)
    mask = [[inside(x + 0.5, y + 0.5) for x in range(64)] for y in range(64)]
    for y in range(64):
        for x in range(64):
            if not mask[y][x]:
                continue
            border = False
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    if dx * dx + dy * dy > 10:
                        continue
                    nx, ny = x + dx, y + dy
                    if not (0 <= nx < 64 and 0 <= ny < 64) or not mask[ny][nx]:
                        border = True
            # 붙는 쪽(왼쪽 가장자리)은 테두리를 두지 않는다
            if border and x > 5:
                c.set(x, y, black)
    for sx, sy in ((57, 9), (60, 16), (51, 6), (45, 5), (47, 57), (38, 59), (29, 58), (54, 26)):
        if mask[int(sy)][int(sx)]:
            c.ellipse(sx, sy, 1.1, 1.1, hex_color("FFFFFF"))
    c.clear_shape(lambda x, y: not inside(x, y))
    m.plane(WING_FROM, WING_TO, name="wing")
    return m


# ── 꼬리 (상자) ─ 붙는 곳 (8, 8, 8) 에서 뒤(+z)로 뻗는다 ───────────


def fox_tail():
    m = Model("fox_tail", "minecraft:rabbit_foot", 7130004, 64)
    m.icon_rotation = [0, 90, 0]
    fur = m.swatch("fur", "D9702A", 0.12, 3)
    fur_dark = m.swatch("fur_dark", "B85A1E", 0.1, 4)
    tip = m.swatch("tip", "F4EFE6", 0.06, 5)
    # 엉덩이에서 아래로 처졌다가 (x축 +회전은 뒤쪽이 내려간다) 끝은 위로 말려 올라간다
    m.box((6.5, 6.5, 7), (9.5, 9.5, 13), fur_dark, rotation=rotation("x", 22.5, (8, 8, 8)), name="root")
    m.box((5.2, 2.6, 11.5), (10.8, 8.4, 18.5), fur, name="fluff1")
    m.box((5, 2.6, 17.5), (11, 8.6, 23), fur, rotation=rotation("x", -22.5, (8, 5.6, 17.5)), name="fluff2")
    m.box((5.6, 5.2, 21.8), (10.4, 10, 26), tip, rotation=rotation("x", -45, (8, 7.6, 21.8)), name="tip1")
    m.box((6.5, 8.6, 24.2), (9.5, 11.6, 27.2), tip, rotation=rotation("x", -45, (8, 10.1, 24.2)), name="tip2")
    return m


def cat_tail():
    m = Model("cat_tail", "minecraft:rabbit_foot", 7130005, 64)
    m.icon_rotation = [0, 90, 0]
    fur = m.swatch("fur", "3B3A44", 0.12, 6)
    tip = m.swatch("tip", "EDEAF2", 0.05, 7)
    m.box((7, 7, 7), (9, 9, 15), fur, name="base")
    m.box((7, 6.2, 14.5), (9, 8.2, 20.5), fur, name="lower")
    m.box((7, 6.2, 19), (9, 16, 21), fur, name="rise")
    m.box((7, 15.2, 17), (9, 17.2, 21), tip, name="tip")
    return m


def dragon_tail():
    m = Model("dragon_tail", "minecraft:rabbit_foot", 7130006, 64)
    m.icon_rotation = [0, 90, 0]

    def scales(cv, x, y, size):
        base = hex_color("3F8F3B")
        for yy in range(size):
            for xx in range(size):
                f = 1.0 if (xx + (yy // 2) * 2) % 4 else 0.78
                if yy % 2 == 0 and xx % 4 == 1:
                    f = 1.18
                cv.set(x + xx, y + yy, shade(base, f))

    green = m.swatch("scales", None, painter=scales)
    belly = m.swatch("belly", "D6C27A", 0.06, 8, stripes=(2, 0.85))
    spike = m.swatch("spike", "EFE6CC", 0.05, 9)
    under = {"down": belly}
    m.box((5.5, 5.5, 7), (10.5, 10.5, 13), green, faces=under, rotation=rotation("x", 22.5, (8, 8, 8)), name="base")
    m.box((6, 3.2, 11.5), (10, 7.2, 18.5), green, faces=under, name="mid")
    m.box((6.5, 2.7, 18), (9.5, 5.7, 24.5), green, faces=under, name="thin")
    m.box((7, 2.4, 24), (9, 4.4, 28.5), green, faces=under, name="end")
    for z, top, h in ((9.5, 10.2, 2.2), (14.5, 7.2, 1.9), (20.5, 5.7, 1.5), (25.5, 4.4, 1.1)):
        m.box((7.5, top, z), (8.5, top + h, z + 1.5), spike, name="spike")
    # 끝의 마름모 날
    m.box((5.5, 2.6, 27.8), (10.5, 4.2, 31.2), spike, rotation=rotation("y", 45, (8, 3.4, 29.5)), name="spade")
    return m


# ── 허리 (몸 가운데 기준, 몸통은 x 4~12, z 6~10) ─────────────


def belt_pouch():
    m = Model("belt_pouch", "minecraft:leather", 7130007, 64)
    strap = m.swatch("strap", "6B4226", 0.08, 10, stripes=(3, 0.9))
    leather = m.swatch("leather", "8A5A33", 0.1, 11)
    dark = m.swatch("dark", "5A3820", 0.08, 12)
    gold = m.swatch("gold", "E8B530", 0.1, 13)
    m.box((3.5, 7, 5.4), (12.5, 9, 6), strap, name="belt_front")
    m.box((3.5, 7, 10), (12.5, 9, 10.6), strap, name="belt_back")
    m.box((3.4, 7, 5.4), (4, 9, 10.6), strap, name="belt_left")
    m.box((12, 7, 5.4), (12.6, 9, 10.6), strap, name="belt_right")
    m.box((7, 6.7, 5.1), (9, 9.3, 5.5), gold, name="buckle")
    m.box((9.6, 4.2, 4.4), (12.2, 7.4, 5.6), leather, name="pouch")
    m.box((9.5, 6.6, 4.3), (12.3, 7.6, 5.7), dark, name="flap")
    m.box((10.6, 6.2, 4.2), (11.2, 6.9, 4.4), gold, name="button")
    m.box((2.6, 4.6, 7), (3.6, 7.6, 9.6), leather, name="side_pouch")
    return m


def waist_katana():
    m = Model("waist_katana", "minecraft:leather", 7130008, 64)
    m.icon_rotation = [0, 90, 0]
    sheath = m.swatch("sheath", "1E1B24", 0.08, 14, stripes=(5, 1.35))

    def wrap(cv, x, y, size):
        for yy in range(size):
            for xx in range(size):
                on = (xx + yy) % 4 in (0, 1) and (xx - yy) % 4 in (0, 1)
                cv.set(x + xx, y + yy, hex_color("2A2438") if on else hex_color("EDE7DA"))

    handle = m.swatch("handle", None, painter=wrap)
    gold = m.swatch("gold", "D9A62E", 0.1, 15)
    tilt = rotation("x", 22.5, (2.5, 7, 7))
    m.box((2, 6.5, 6), (3, 7.5, 20), sheath, rotation=tilt, name="sheath")
    m.box((2.1, 6.6, 0), (2.9, 7.4, 6), handle, rotation=tilt, name="handle")
    m.box((1.5, 5.9, 5.6), (3.5, 8.1, 6.2), gold, rotation=tilt, name="guard")
    return m


# ── 상체 (몸 가운데 기준, 목은 y 12.8) ───────────────────────


def scarf():
    m = Model("scarf", "minecraft:string", 7130009, 64)
    knit = m.swatch("knit", "C62828", 0.1, 16, stripes=(4, 1.35))
    white = m.swatch("white", "F2E8E8", 0.05, 17)
    m.box((4, 11.3, 4.9), (12, 14, 6.2), knit, name="front")
    m.box((4, 11.3, 9.8), (12, 14, 11.1), knit, name="back")
    m.box((3.4, 11.3, 4.9), (4.7, 14, 11.1), knit, name="left")
    m.box((11.3, 11.3, 4.9), (12.6, 14, 11.1), knit, name="right")
    m.box((4.3, 4.2, 5.0), (6.9, 11.8, 6.0), knit, name="hang")
    m.box((4.3, 3.2, 5.0), (6.9, 4.2, 6.0), white, name="fringe")
    return m


def medal():
    m = Model("medal", "minecraft:gold_nugget", 7130010, 64)

    def ribbon(cv, x, y, size):
        for yy in range(size):
            for xx in range(size):
                cv.set(x + xx, y + yy, hex_color("1F4FD1") if (xx // 2) % 2 == 0 else hex_color("D12B2B"))

    rib = m.swatch("ribbon", None, painter=ribbon)
    gold = m.swatch("gold", "F2C230", 0.12, 18)

    def star(cv, x, y, size):
        cv.noise_rect(x, y, x + size, y + size, hex_color("E3AE1E"), 0.08, 19)
        cx, cy = x + 2, y + 2
        pts = []
        for k in range(10):
            r = 1.9 if k % 2 == 0 else 0.8
            a = -math.pi / 2 + k * math.pi / 5
            pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
        cv.polygon(pts, hex_color("FFF3B0"))

    face = m.swatch("star", None, painter=star)
    m.box((4, 12.4, 5.2), (12, 13.2, 10.8), rib, name="neck_band")
    m.box((6.8, 8.5, 5.6), (9.2, 12.8, 6.0), rib, name="ribbon")
    m.box((6.3, 4.5, 5.4), (9.7, 8.7, 6.0), gold, faces={"north": face}, name="disc")
    return m


# ── 모자 (머리에 쓰는 아이템: 머리는 모델 좌표 1.6~14.4, 정수리 y=14.4) ──


def top_hat():
    m = Model("top_hat", "minecraft:paper", 7130011, 64, kind="head")
    felt = m.swatch("felt", "1C1C22", 0.08, 20)
    band = m.swatch("band", "B3202A", 0.06, 21)
    m.box((0, 14.5, 0), (16, 15.5, 16), felt, name="brim")
    m.box((3, 15.5, 3), (13, 26, 13), felt, name="crown")
    m.box((2.9, 15.5, 2.9), (13.1, 17.6, 13.1), band, name="band")
    return m


def witch_hat():
    m = Model("witch_hat", "minecraft:paper", 7130012, 64, kind="head")
    cloth = m.swatch("cloth", "3A1F5C", 0.1, 22)
    band = m.swatch("band", "7B4BB5", 0.06, 23)
    gold = m.swatch("gold", "E8B530", 0.08, 24)
    m.box((-2, 14.5, -2), (18, 15.3, 18), cloth, name="brim")
    m.box((2.5, 15.3, 2.5), (13.5, 19.5, 13.5), cloth, name="cone1")
    m.box((2.4, 15.3, 2.4), (13.6, 16.9, 13.6), band, name="band")
    m.box((6.5, 15.1, 2.1), (9.5, 17.1, 2.45), gold, name="buckle")
    m.box((4, 19.5, 4.5), (12, 23.5, 12.5), cloth, name="cone2")
    m.box((5.5, 23.5, 6), (10.5, 27, 11), cloth, name="cone3")
    m.box((6.5, 26.5, 7.5), (9.5, 30, 10.5), cloth, rotation=rotation("x", 22.5, (8, 27, 9)), name="cone4")
    m.box((7.2, 29, 9.5), (8.8, 32, 11), cloth, rotation=rotation("x", 45, (8, 29.5, 10)), name="tip")
    return m


def bunny_ears():
    m = Model("bunny_ears", "minecraft:paper", 7130013, 64, kind="head")
    fur = m.swatch("fur", "F5F3F7", 0.05, 25)
    pink = m.swatch("pink", "F4A6C0", 0.05, 26)
    left = rotation("z", 22.5, (4.75, 14, 7.75))
    right = rotation("z", -22.5, (11.25, 14, 7.75))
    m.box((3.5, 14, 7), (6, 27, 8.5), fur, faces={"north": pink}, rotation=left, name="ear_left")
    m.box((10, 14, 7), (12.5, 27, 8.5), fur, faces={"north": pink}, rotation=right, name="ear_right")
    return m


# ── 등 (몸 가운데 기준으로 만들고 cosmetics.yml 에서 offset [0, 0, 0.28]) ─


def cape():
    m = Model("cape", "minecraft:leather", 7130014, 64)
    m.icon_rotation = [25, 20, 0]
    red = m.swatch("velvet", "9E1B24", 0.08, 27)
    inner = m.swatch("inner", "5E0F16", 0.06, 28)
    gold = m.swatch("gold", "E3B23C", 0.1, 29)

    def trimmed(cv, x, y, size):
        cv.noise_rect(x, y, x + size, y + size, hex_color("A81E28"), 0.08, 30)
        for i in range(size):
            cv.set(x + i, y, hex_color("E3B23C"))
            cv.set(x, y + i, hex_color("E3B23C"))
            cv.set(x + size - 1, y + i, hex_color("E3B23C"))

    back = m.swatch("back", None, painter=trimmed)
    m.box((3, -6, 10.2), (13, 14.4, 11.2), red, faces={"north": inner, "south": back}, name="cape")
    m.box((3.5, 14.2, 5.4), (12.5, 14.9, 11.2), red, name="collar")
    m.box((7, 12.8, 5.1), (9, 14.6, 5.5), gold, name="clasp")
    return m


def adventurer_backpack():
    m = Model("adventurer_backpack", "minecraft:leather", 7130015, 64)
    m.icon_rotation = [25, 20, 0]
    bag = m.swatch("bag", "8B5A2B", 0.1, 31)
    dark = m.swatch("dark", "5E3B1B", 0.08, 32)
    roll = m.swatch("roll", "4C7A3A", 0.1, 33, stripes=(3, 0.8))
    metal = m.swatch("metal", "B8B8C0", 0.08, 34)
    m.box((3.5, 2, 10.1), (12.5, 13.5, 14.5), bag, name="bag")
    m.box((3.3, 9.5, 10), (12.7, 13.8, 14.8), dark, name="flap")
    m.box((5, 3.2, 14.5), (11, 8, 15.8), bag, name="pocket")
    m.box((7.4, 6.6, 15.8), (8.6, 7.6, 16), metal, name="clasp")
    m.box((2.6, 13.8, 10.8), (13.4, 16.4, 13.4), roll, name="bedroll")
    for x in (4.5, 10.5):
        m.box((x, 5.5, 5.5), (x + 1, 14.6, 5.9), dark, name="strap_front")
        m.box((x, 14.4, 5.5), (x + 1, 14.9, 10.2), dark, name="strap_top")
    return m


# ── 풍선 ─────────────────────────────────────────────


def heart_balloon():
    m = Model("heart_balloon", "minecraft:red_dye", 7130016, 64, kind="float")
    red = m.swatch("red", "E0283C", 0.06, 35)
    shine = m.swatch("shine", "FF8FA0", 0.04, 36)
    rows = [(12, 14, [(3, 7), (9, 13)]), (10, 12, [(2, 14)]), (8, 10, [(2, 14)]), (6, 8, [(3, 13)]),
            (4, 6, [(4, 12)]), (2, 4, [(5.5, 10.5)]), (0.5, 2, [(7, 9)])]
    for y0, y1, spans in rows:
        for x0, x1 in spans:
            m.box((x0, y0, 6), (x1, y1, 10), red, name="heart")
    m.box((4, 5, 5.3), (12, 12, 10.7), red, name="bulge")
    m.box((4.2, 10, 5.1), (6.2, 12, 5.3), shine, name="shine")
    m.box((7.5, -0.5, 7.5), (8.5, 0.5, 8.5), red, name="knot")
    return m


MODELS = [angel_wing, demon_wing, butterfly_wing, fox_tail, cat_tail, dragon_tail, belt_pouch, waist_katana,
          scarf, medal, top_hat, witch_hat, bunny_ears, cape, adventurer_backpack, heart_balloon]


# ── 리소스팩 쓰기 ─────────────────────────────────────


def pack_icon():
    c = Canvas(64, 64)
    c.fill_shape(lambda x, y: True, None, lambda x, y: mix(hex_color("2B1055"), hex_color("7597DE"), y / 64))
    crown = [(12, 44), (12, 22), (22, 32), (32, 16), (42, 32), (52, 22), (52, 44)]
    c.polygon(crown, hex_color("F2C230"), lambda x, y: mix(hex_color("FFE27A"), hex_color("D99A12"), (y - 16) / 28))
    c.rect(12, 44, 53, 50, hex_color("D99A12"))
    for x, col in ((22, "E0283C"), (32, "41E0FF"), (42, "4CD964")):
        c.ellipse(x, 38, 2.6, 2.6, hex_color(col))
    return c


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mode = "wb" if isinstance(data, bytes) else "w"
    with open(path, mode, **({} if mode == "wb" else {"encoding": "utf-8"})) as f:
        f.write(data)


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def main():
    if os.path.isdir(PACK):
        shutil.rmtree(PACK)
    os.makedirs(BLOCKBENCH, exist_ok=True)
    files = []

    def put(rel, data):
        write(os.path.join(PACK, rel), data)
        files.append(rel)

    put("pack.mcmeta", dump({"pack": {
        "description": "CosmeticsCore 기본 코스메틱 모델",
        "pack_format": 34, "supported_formats": {"min_inclusive": 34, "max_inclusive": 1000},
        "min_format": 34, "max_format": 1000,
    }}))
    put("pack.png", pack_icon().png())

    models = [make() for make in MODELS]
    by_base = {}
    for m in models:
        put(f"assets/{NS}/models/item/{m.name}.json", dump(m.model_json()))
        put(f"assets/{NS}/textures/item/{m.name}.png", m.canvas.png())
        write(os.path.join(BLOCKBENCH, f"{m.name}.bbmodel"), json.dumps(m.bbmodel(), ensure_ascii=False))
        by_base.setdefault(m.base, []).append(m)

    for base, group in sorted(by_base.items()):
        item = base.split(":")[1]
        group.sort(key=lambda x: x.cmd)
        ours = {m.cmd for m in group}
        vanilla = f"{NS}:item/vanilla/{item}"
        # 원래 모양 (overrides 에서 우리 번호 뒤를 원래대로 돌리는 데 쓴다)
        put(f"assets/{NS}/models/item/vanilla/{item}.json",
            dump({"parent": "minecraft:item/generated", "textures": {"layer0": f"minecraft:item/{item}"}}))
        overrides = []
        entries = []
        for m in group:
            overrides.append({"predicate": {"custom_model_data": m.cmd}, "model": f"{NS}:item/{m.name}"})
            entries.append({"threshold": m.cmd, "model": {"type": "minecraft:model", "model": f"{NS}:item/{m.name}"}})
            if m.cmd + 1 not in ours:
                # 다른 플러그인이 더 큰 번호를 쓰면 우리 모델이 아니라 원래 모양이 나오게
                overrides.append({"predicate": {"custom_model_data": m.cmd + 1}, "model": vanilla})
                entries.append({"threshold": m.cmd + 1, "model": {"type": "minecraft:model", "model": f"minecraft:item/{item}"}})
        put(f"assets/minecraft/models/item/{item}.json", dump({
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"minecraft:item/{item}"},
            "overrides": overrides,
        }))
        put(f"assets/minecraft/items/{item}.json", dump({"model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "index": 0,
            "fallback": {"type": "minecraft:model", "model": f"minecraft:item/{item}"},
            "entries": entries,
        }}))

    files.sort()
    write(os.path.join(PACK, "index.txt"), "\n".join(files) + "\n")
    print(f"모델 {len(models)}개, 파일 {len(files)}개 -> {PACK}")
    for m in models:
        print(f"  {m.name:22s} {m.base:24s} custom-model-data {m.cmd}")


if __name__ == "__main__":
    main()
