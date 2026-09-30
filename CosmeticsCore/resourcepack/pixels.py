"""작은 픽셀 그림 도구: 캔버스, 도형 채우기, PNG 저장 (표준 라이브러리만 씀)."""

import math
import random
import struct
import zlib


def hex_color(value, alpha=255):
    value = value.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def shade(c, factor):
    """factor > 1 밝게, < 1 어둡게 (알파는 그대로)."""
    return (min(255, int(c[0] * factor)), min(255, int(c[1] * factor)), min(255, int(c[2] * factor)), c[3])


class Canvas:
    def __init__(self, width, height, fill=(0, 0, 0, 0)):
        self.width = width
        self.height = height
        self.px = [list(fill) for _ in range(width * height)]

    def get(self, x, y):
        return tuple(self.px[y * self.width + x])

    def set(self, x, y, c):
        if 0 <= x < self.width and 0 <= y < self.height:
            if c[3] >= 255:
                self.px[y * self.width + x] = list(c)
            else:
                # 반투명은 아래 색과 섞는다
                below = self.px[y * self.width + x]
                a = c[3] / 255.0
                out_a = a + below[3] / 255.0 * (1 - a)
                if out_a <= 0:
                    self.px[y * self.width + x] = [0, 0, 0, 0]
                    return
                rgb = [
                    (c[i] * a + below[i] * below[3] / 255.0 * (1 - a)) / out_a
                    for i in range(3)
                ]
                self.px[y * self.width + x] = [int(round(v)) for v in rgb] + [int(round(out_a * 255))]

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

    def noise_rect(self, x0, y0, x1, y1, base, spread=0.12, seed=0, stripes=None):
        """바탕색에 잔무늬를 넣어 채운다. stripes=(간격, 세기) 면 가로 줄무늬."""
        rng = random.Random(seed)
        for y in range(y0, y1):
            for x in range(x0, x1):
                f = 1 + rng.uniform(-spread, spread)
                if stripes and (y - y0) % stripes[0] == 0:
                    f *= stripes[1]
                self.set(x, y, shade(base, f))

    def fill_shape(self, inside, c, shade_fn=None):
        """inside(x+0.5, y+0.5) 가 참인 칸을 채운다."""
        for y in range(self.height):
            for x in range(self.width):
                if inside(x + 0.5, y + 0.5):
                    self.set(x, y, shade_fn(x, y) if shade_fn else c)

    def polygon(self, points, c, shade_fn=None):
        self.fill_shape(lambda x, y: point_in_polygon(x, y, points), c, shade_fn)

    def ellipse(self, cx, cy, rx, ry, c, shade_fn=None):
        self.fill_shape(lambda x, y: ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1, c, shade_fn)

    def capsule(self, x0, y0, x1, y1, r0, r1, c, shade_fn=None):
        """두 점을 잇는 굵기가 변하는 막대 (깃털, 뼈)."""
        def inside(x, y):
            dx, dy = x1 - x0, y1 - y0
            length2 = dx * dx + dy * dy
            t = 0 if length2 == 0 else max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / length2))
            px, py = x0 + dx * t, y0 + dy * t
            r = r0 + (r1 - r0) * t
            return (x - px) ** 2 + (y - py) ** 2 <= r * r
        self.fill_shape(inside, c, shade_fn)

    def line(self, x0, y0, x1, y1, c, width=1.0):
        self.capsule(x0, y0, x1, y1, width / 2, width / 2, c)

    def outline(self, c, alpha_threshold=1):
        """불투명한 칸 바깥쪽 가장자리를 c 로 칠한다 (실루엣 테두리)."""
        solid = [[self.get(x, y)[3] >= alpha_threshold for x in range(self.width)] for y in range(self.height)]
        for y in range(self.height):
            for x in range(self.width):
                if not solid[y][x]:
                    continue
                edge = False
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if not (0 <= nx < self.width and 0 <= ny < self.height) or not solid[ny][nx]:
                        edge = True
                        break
                if edge:
                    self.px[y * self.width + x] = list(c)

    def clear_shape(self, inside):
        for y in range(self.height):
            for x in range(self.width):
                if inside(x + 0.5, y + 0.5):
                    self.px[y * self.width + x] = [0, 0, 0, 0]

    def png(self):
        raw = bytearray()
        for y in range(self.height):
            raw.append(0)
            for x in range(self.width):
                raw.extend(bytes(self.px[y * self.width + x]))

        def chunk(kind, data):
            body = kind + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) \
            + chunk(b"IEND", b"")


def point_in_polygon(x, y, points):
    inside = False
    n = len(points)
    j = n - 1
    for i in range(n):
        xi, yi = points[i]
        xj, yj = points[j]
        if (yi > y) != (yj > y):
            cross = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < cross:
                inside = not inside
        j = i
    return inside


def lerp_points(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def bezier(p0, p1, p2, t):
    a = lerp_points(p0, p1, t)
    b = lerp_points(p1, p2, t)
    return lerp_points(a, b, t)


def angle_between(a, b):
    return math.atan2(b[1] - a[1], b[0] - a[0])
