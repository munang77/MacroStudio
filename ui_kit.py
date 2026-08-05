# -*- coding: utf-8 -*-
"""MacroStudio UI 킷 - Pillow 로 안티에일리어싱 렌더링한 커스텀 tkinter 위젯 모음.

tkinter 기본 위젯은 모서리도 각지고 색도 안 먹는 구석이 많아서,
카드/버튼/토글/스테퍼/슬라이더/드롭다운을 전부 직접 그린다.
"""

import tkinter as tk
import tkinter.font as tkfont

from PIL import Image, ImageDraw, ImageTk

SS = 4  # 슈퍼샘플링 배율 (4배로 그린 뒤 축소해서 계단현상 제거)

# ---------------------------------------------------------------- 팔레트
BG = "#0f1117"        # 창 배경
SIDE = "#12151d"      # 사이드바
CARD = "#171b24"      # 카드
FIELD = "#1e2330"     # 입력 필드
FIELD_HI = "#262c3b"  # 필드 hover
LINE = "#262c3a"      # 경계선
TXT = "#e8ecf5"
TXT_DIM = "#98a1b6"
TXT_MUTE = "#646d82"
ACCENT = "#5b8cff"
ACCENT2 = "#8b6cff"   # 그라데이션 끝색
OK = "#3ddc97"
WARN = "#ffc857"
DANGER = "#ff5b6e"
DANGER2 = "#ff7a6b"


def UI(size=10, weight="normal"):
    return ("맑은 고딕", size, weight)


def MONO(size=9, weight="normal"):
    return ("Cascadia Mono", size, weight)


_fonts = {}


def text_width(font, text):
    """글자 폭 실측 (칩·버튼 크기를 글자에 맞추려고)."""
    if font not in _fonts:
        _fonts[font] = tkfont.Font(family=font[0], size=font[1],
                                   weight=font[2] if len(font) > 2 else "normal")
    return _fonts[font].measure(text)


# ---------------------------------------------------------------- 색 유틸
def rgb(c):
    c = c.lstrip("#")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def hexc(t):
    return "#%02x%02x%02x" % tuple(max(0, min(255, int(round(v)))) for v in t)


def mix(c1, c2, t):
    """색 c1 과 c2 를 t(0~1) 비율로 섞는다."""
    a, b = rgb(c1), rgb(c2)
    return hexc(tuple(a[i] + (b[i] - a[i]) * t for i in range(3)))


# ---------------------------------------------------------------- 이미지 캐시
_cache = {}


def _grad_image(w, h, c1, c2):
    base = Image.new("RGB", (w, h), c1)
    d = ImageDraw.Draw(base)
    a, b = rgb(c1), rgb(c2)
    for x in range(w):
        t = x / max(1, w - 1)
        d.line([(x, 0), (x, h)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)))
    return base


def rounded(w, h, r, fill, bg, border=None, bw=1, grad=None):
    """모서리가 둥근 사각형 이미지. bg 위에 미리 합성해서 알파 없이 깔끔하게."""
    w, h = max(1, int(w)), max(1, int(h))
    key = ("rr", w, h, r, fill, bg, border, bw, grad)
    if key in _cache:
        return _cache[key]

    W, H, R = w * SS, h * SS, int(r * SS)
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=R, fill=255)

    base = Image.new("RGB", (W, H), bg)
    body = _grad_image(W, H, grad[0], grad[1]) if grad else Image.new("RGB", (W, H), fill)
    base.paste(body, (0, 0), mask)

    if border:
        d = ImageDraw.Draw(base)
        d.rounded_rectangle([0, 0, W - 1, H - 1], radius=R, outline=border, width=max(1, bw * SS))

    img = ImageTk.PhotoImage(base.resize((w, h), Image.LANCZOS))
    _cache[key] = img
    return img


def corner(r, k, inner, outer, border=None):
    """카드 모서리 조각. k: 0=좌상 1=우상 2=우하 3=좌하."""
    key = ("cn", r, k, inner, outer, border)
    if key in _cache:
        return _cache[key]
    S = r * SS
    base = Image.new("RGB", (S * 2, S * 2), outer)
    ImageDraw.Draw(base).rounded_rectangle(
        [0, 0, S * 2 - 1, S * 2 - 1], radius=S, fill=inner,
        outline=border, width=SS if border else 0)
    box = {0: (0, 0), 1: (S, 0), 2: (S, S), 3: (0, S)}[k]
    piece = base.crop((box[0], box[1], box[0] + S, box[1] + S))
    img = ImageTk.PhotoImage(piece.resize((r, r), Image.LANCZOS))
    _cache[key] = img
    return img


def circle(d, color, bg, ring=None, rw=2):
    key = ("ci", d, color, bg, ring, rw)
    if key in _cache:
        return _cache[key]
    D = d * SS
    im = Image.new("RGB", (D, D), bg)
    dr = ImageDraw.Draw(im)
    dr.ellipse([0, 0, D - 1, D - 1], fill=color, outline=ring, width=rw * SS if ring else 0)
    img = ImageTk.PhotoImage(im.resize((d, d), Image.LANCZOS))
    _cache[key] = img
    return img


# ---------------------------------------------------------------- 아이콘
def icon(name, size, color, bg):
    key = ("ic", name, size, color, bg)
    if key in _cache:
        return _cache[key]
    S = size * SS
    im = Image.new("RGB", (S, S), bg)
    d = ImageDraw.Draw(im)
    m = S * 0.16          # 여백

    if name == "record":
        d.ellipse([m, m, S - m, S - m], fill=color)
    elif name == "play":
        d.polygon([(S * 0.26, S * 0.16), (S * 0.84, S * 0.5), (S * 0.26, S * 0.84)], fill=color)
    elif name == "stop":
        d.rounded_rectangle([S * 0.24, S * 0.24, S * 0.76, S * 0.76], radius=S * 0.1, fill=color)
    elif name == "mouse":
        d.rounded_rectangle([S * 0.28, S * 0.1, S * 0.72, S * 0.9], radius=S * 0.22,
                            outline=color, width=int(S * 0.075))
        d.line([(S * 0.5, S * 0.22), (S * 0.5, S * 0.42)], fill=color, width=int(S * 0.075))
    elif name == "keyboard":
        d.rounded_rectangle([S * 0.08, S * 0.24, S * 0.92, S * 0.76], radius=S * 0.12,
                            outline=color, width=int(S * 0.07))
        for i in range(3):
            x = S * (0.24 + i * 0.2)
            d.ellipse([x, S * 0.4, x + S * 0.08, S * 0.48], fill=color)
        d.line([(S * 0.3, S * 0.62), (S * 0.7, S * 0.62)], fill=color, width=int(S * 0.07))
    elif name == "gear":
        import math
        cx = cy = S / 2
        outer, inner = S * 0.44, S * 0.32
        pts = []
        teeth = 8
        for i in range(teeth * 4):
            ang = math.pi * 2 * i / (teeth * 4)
            rad = outer if (i % 4) in (0, 1) else inner
            pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
        d.polygon(pts, fill=color)
        d.ellipse([cx - S * 0.13, cy - S * 0.13, cx + S * 0.13, cy + S * 0.13], fill=bg)
    elif name == "folder":
        d.rounded_rectangle([S * 0.1, S * 0.26, S * 0.9, S * 0.82], radius=S * 0.1, fill=color)
        d.rounded_rectangle([S * 0.1, S * 0.18, S * 0.45, S * 0.34], radius=S * 0.06, fill=color)
    elif name == "chevron":
        d.line([(S * 0.3, S * 0.42), (S * 0.5, S * 0.62), (S * 0.7, S * 0.42)],
               fill=color, width=int(S * 0.09), joint="curve")
    elif name == "logo":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.28, fill=color)

    img = ImageTk.PhotoImage(im.resize((size, size), Image.LANCZOS))
    _cache[key] = img
    return img


def logo_image(size, bg):
    """그라데이션 사각형 안에 커서 모양이 들어간 앱 마크."""
    key = ("logo", size, bg)
    if key in _cache:
        return _cache[key]
    S = size * SS
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.3, fill=255)
    base = Image.new("RGB", (S, S), bg)
    base.paste(_grad_image(S, S, ACCENT, ACCENT2), (0, 0), mask)
    d = ImageDraw.Draw(base)
    d.polygon([(S * 0.36, S * 0.24), (S * 0.36, S * 0.74), (S * 0.48, S * 0.62),
               (S * 0.56, S * 0.8), (S * 0.66, S * 0.74), (S * 0.58, S * 0.58),
               (S * 0.72, S * 0.55)], fill="#ffffff")
    img = ImageTk.PhotoImage(base.resize((size, size), Image.LANCZOS))
    _cache[key] = img
    return img


# ---------------------------------------------------------------- 카드 / 패널
class Card(tk.Frame):
    """모서리가 둥근 컨테이너. 내부에는 평범하게 pack/grid 로 위젯을 넣으면 된다."""

    def __init__(self, master, bg=CARD, outer=BG, radius=14, border=LINE, pad=18, **kw):
        super().__init__(master, bg=bg, highlightthickness=0, bd=0, **kw)
        self._bg, self._outer, self._r, self._border = bg, outer, radius, border
        self.body = tk.Frame(self, bg=bg)
        self.body.pack(fill="both", expand=True, padx=pad, pady=pad)

        self._corners = []
        for i in range(4):
            im = corner(radius, i, bg, outer, border)
            lb = tk.Label(self, image=im, bd=0, highlightthickness=0)
            lb.image = im
            self._corners.append(lb)
        self._edges = [tk.Frame(self, bg=border or bg, height=1, width=1) for _ in range(4)]
        self.bind("<Configure>", self._redraw)

    def _redraw(self, _e=None):
        w, h, r = self.winfo_width(), self.winfo_height(), self._r
        if w < 4 or h < 4:
            return
        pos = [(0, 0), (w - r, 0), (w - r, h - r), (0, h - r)]
        for i, lb in enumerate(self._corners):
            lb.place(x=pos[i][0], y=pos[i][1])
            lb.lift()
        e = self._edges
        e[0].place(x=r, y=0, width=w - 2 * r, height=1)
        e[1].place(x=r, y=h - 1, width=w - 2 * r, height=1)
        e[2].place(x=0, y=r, width=1, height=h - 2 * r)
        e[3].place(x=w - 1, y=r, width=1, height=h - 2 * r)
        for f in e:
            f.lift()


# ---------------------------------------------------------------- 버튼
class Btn(tk.Canvas):
    VARIANTS = {
        # 이름: (채움, 글자, 테두리, hover 밝기)
        "primary": (None, "#ffffff", None, 0.12),
        "danger": (DANGER, "#ffffff", None, 0.12),
        "soft": (FIELD, TXT, LINE, 0.10),
        "ghost": (None, TXT_DIM, LINE, 0.08),
    }

    def __init__(self, master, text, command=None, width=None, height=38,
                 variant="primary", bg=CARD, hint=None, font=None, radius=10):
        self.txt = text
        self.hint = hint
        self.variant = variant
        self.command = command
        self.font = font or UI(10, "bold")
        self.parent_bg = bg
        self.radius = radius
        self._enabled = True

        if width is None:
            tmp = tk.Label(master, text=text, font=self.font)
            width = tmp.winfo_reqwidth() + 44 + (self._chip_w() + 14 if hint else 0)
            tmp.destroy()
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h = width, height
        self._state = "normal"
        self._imgs = {}
        self._build()
        self.bind("<Enter>", lambda e: self._set("hover"))
        self.bind("<Leave>", lambda e: self._set("normal"))
        self.bind("<Button-1>", lambda e: self._set("press"))
        self.bind("<ButtonRelease-1>", self._release)

    def _chip_w(self):
        """단축키 칩 폭을 글자 길이에 맞춘다 (F6 / Ctrl+Q / M5 모두 대응)."""
        return max(30, text_width(MONO(8, "bold"), str(self.hint or "")) + 16)

    def _colors(self, state):
        fill, fg, border, lift = self.VARIANTS[self.variant]
        grad = None
        if self.variant == "primary":
            c1, c2 = ACCENT, ACCENT2
            if state == "hover":
                c1, c2 = mix(c1, "#ffffff", lift), mix(c2, "#ffffff", lift)
            elif state == "press":
                c1, c2 = mix(c1, "#000000", 0.14), mix(c2, "#000000", 0.14)
            grad = (c1, c2)
            fill = c1
        elif self.variant == "ghost":
            fill = self.parent_bg if state == "normal" else mix(self.parent_bg, "#ffffff", lift)
        else:
            if state == "hover":
                fill = mix(fill, "#ffffff", lift)
            elif state == "press":
                fill = mix(fill, "#000000", 0.12)
        if not self._enabled:
            fill = mix(self.parent_bg, "#ffffff", 0.04)
            grad = None
            fg = TXT_MUTE
        return fill, fg, border, grad

    def _build(self):
        self.delete("all")
        fill, fg, border, grad = self._colors(self._state)
        img = rounded(self.w, self.h, self.radius, fill, self.parent_bg,
                      border=border, grad=grad)
        self._imgs["bg"] = img
        self.create_image(0, 0, anchor="nw", image=img)

        if self.hint:
            chip_w, chip_h = self._chip_w(), 20
            cx = self.w - 14 - chip_w
            chip = rounded(chip_w, chip_h, 6, mix(fill, "#ffffff", 0.22), fill)
            self._imgs["chip"] = chip
            self.create_image(cx, (self.h - chip_h) // 2, anchor="nw", image=chip)
            self.create_text(cx + chip_w / 2, self.h / 2 + 1, text=self.hint,
                             fill=fg, font=MONO(8, "bold"))
            self.create_text((self.w - chip_w - 14) / 2 + 6, self.h / 2,
                             text=self.txt, fill=fg, font=self.font)
        else:
            self.create_text(self.w / 2, self.h / 2, text=self.txt, fill=fg, font=self.font)

    def _set(self, state):
        if not self._enabled:
            return
        if state != self._state:
            self._state = state
            self._build()

    def _release(self, event):
        if not self._enabled:
            return
        inside = 0 <= event.x <= self.w and 0 <= event.y <= self.h
        self._set("hover" if inside else "normal")
        if inside and self.command:
            self.command()

    def config_text(self, text=None, variant=None, hint=None):
        if text is not None:
            self.txt = text
        if variant is not None:
            self.variant = variant
        if hint is not None:
            self.hint = hint
        self._build()

    def set_enabled(self, on):
        self._enabled = bool(on)
        self._state = "normal"
        self._build()


# ---------------------------------------------------------------- 토글 스위치
class Toggle(tk.Canvas):
    def __init__(self, master, text="", value=False, bg=CARD, command=None, width=None):
        self.text = text
        self.value = bool(value)
        self.command = command
        self.bg = bg
        self.sw, self.sh = 44, 24
        h = 26
        if width is None:
            tmp = tk.Label(master, text=text, font=UI(10))
            width = tmp.winfo_reqwidth() + self.sw + 14
            tmp.destroy()
        super().__init__(master, width=width, height=h, bg=bg, highlightthickness=0, bd=0)
        self.w, self.h = width, h
        self._knob = 0.0 if not self.value else 1.0
        self._imgs = {}
        self._hover = False
        self._draw()
        self.bind("<Button-1>", lambda e: self.toggle())
        self.bind("<Enter>", lambda e: self._set_hover(True))
        self.bind("<Leave>", lambda e: self._set_hover(False))

    def _set_hover(self, on):
        self._hover = on
        self._draw()
        self.configure(cursor="hand2" if on else "")

    def _draw(self):
        self.delete("all")
        x0 = self.w - self.sw
        on_col = mix(ACCENT, "#ffffff", 0.08) if self._hover else ACCENT
        off_col = mix(FIELD, "#ffffff", 0.06) if self._hover else FIELD
        track_col = mix(off_col, on_col, self._knob)
        track = rounded(self.sw, self.sh, self.sh // 2, track_col, self.bg,
                        border=None if self._knob > 0.5 else LINE)
        self._imgs["t"] = track
        self.create_image(x0, (self.h - self.sh) // 2, anchor="nw", image=track)

        kd = self.sh - 6
        kx = x0 + 3 + self._knob * (self.sw - kd - 6)
        knob = circle(kd, "#ffffff", track_col)
        self._imgs["k"] = knob
        self.create_image(kx, (self.h - kd) // 2, anchor="nw", image=knob)

        if self.text:
            self.create_text(0, self.h / 2, text=self.text, anchor="w",
                             fill=TXT if self.value else TXT_DIM, font=UI(10))

    def toggle(self):
        self.set(not self.value, animate=True)
        if self.command:
            self.command(self.value)

    def set(self, value, animate=False):
        self.value = bool(value)
        target = 1.0 if self.value else 0.0
        if not animate:
            self._knob = target
            self._draw()
            return
        self._animate(target)

    def _animate(self, target, step=0, start=None):
        frames = 7
        if not self.winfo_exists():
            return
        if start is None:
            start = self._knob
        ease = 1 - (1 - (step + 1) / frames) ** 3       # ease-out cubic
        self._knob = start + (target - start) * ease
        self._draw()
        if step + 1 < frames:
            self.after(14, lambda: self._animate(target, step + 1, start))
        else:
            self._knob = target
            self._draw()

    def get(self):
        return self.value


# ---------------------------------------------------------------- 숫자 스테퍼
class Stepper(tk.Canvas):
    def __init__(self, master, value=0, lo=0, hi=999999, step=1, width=168, height=36,
                 bg=CARD, suffix="", command=None):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h = width, height
        self.lo, self.hi, self.step = lo, hi, step
        self.bg, self.suffix, self.command = bg, suffix, command
        self.var = tk.StringVar(value=str(value))
        self._imgs = {}
        self._hover = None
        self._repeat_job = None
        self._enabled = True
        self._last = int(value)          # 글자가 잘못 들어왔을 때 돌아갈 값

        self._imgs["bg"] = rounded(width, height, 9, FIELD, bg, border=LINE)
        self.create_image(0, 0, anchor="nw", image=self._imgs["bg"])

        self.entry = tk.Entry(self, textvariable=self.var, bg=FIELD, fg=TXT, bd=0,
                              highlightthickness=0, justify="center",
                              insertbackground=ACCENT, font=MONO(10),
                              disabledbackground=FIELD, disabledforeground=TXT_MUTE)
        self.create_window(width / 2, height / 2, window=self.entry, width=width - 76)
        self.entry.bind("<FocusOut>", lambda e: self._commit())
        self.entry.bind("<Return>", lambda e: self._commit())
        self.entry.bind("<Up>", lambda e: self._bump(+self.step))
        self.entry.bind("<Down>", lambda e: self._bump(-self.step))
        self.entry.bind("<MouseWheel>", self._wheel)

        for tag, sx in (("minus", 5), ("plus", width - 33)):
            im = rounded(28, height - 10, 7, FIELD, FIELD)
            self._imgs[tag] = im
            self.create_image(sx, 5, anchor="nw", image=im, tags=(tag, tag + "bg"))
            self.create_text(sx + 14, height / 2 - 1, text="+" if tag == "plus" else "−",
                             fill=TXT_DIM, font=UI(12, "bold"), tags=(tag, tag + "tx"))
            self.tag_bind(tag, "<Enter>", lambda e, t=tag: self._hl(t, True))
            self.tag_bind(tag, "<Leave>", lambda e, t=tag: self._hl(t, False))
            self.tag_bind(tag, "<Button-1>", lambda e, t=tag: self._press(t))
            self.tag_bind(tag, "<ButtonRelease-1>", lambda e: self._release())

    def _hl(self, tag, on):
        if not self._enabled:
            return
        col = FIELD_HI if on else FIELD
        im = rounded(28, self.h - 10, 7, col, FIELD)
        self._imgs[tag] = im
        self.itemconfigure(tag + "bg", image=im)
        self.configure(cursor="hand2" if on else "")

    def _press(self, tag):
        if not self._enabled:
            return
        self._bump(self.step if tag == "plus" else -self.step)
        self._repeat_job = self.after(380, lambda: self._auto(tag))

    def _auto(self, tag):
        self._bump(self.step if tag == "plus" else -self.step)
        self._repeat_job = self.after(55, lambda: self._auto(tag))

    def _release(self):
        if self._repeat_job:
            self.after_cancel(self._repeat_job)
            self._repeat_job = None

    def _wheel(self, event):
        if not self._enabled:
            return "break"
        self._bump(self.step * (1 if event.delta > 0 else -1))
        return "break"

    def _bump(self, delta):
        if not self._enabled:
            return
        self.set(self.get() + delta)

    def _commit(self):
        self.set(self.get())

    def get(self):
        """숫자가 아니면 마지막으로 유효했던 값을 돌려준다 (오타로 0=무한 이 되는 사고 방지)."""
        try:
            value = int(float(self.var.get()))
        except (ValueError, TypeError):
            return self._last
        self._last = max(self.lo, min(self.hi, value))
        return self._last

    def set(self, value):
        value = max(self.lo, min(self.hi, int(value)))
        self._last = value
        self.var.set(str(value))
        if self.command:
            self.command(value)

    def set_enabled(self, on):
        self._enabled = bool(on)
        self.entry.configure(state="normal" if on else "disabled",
                             fg=TXT if on else TXT_MUTE)
        for tag in ("minus", "plus"):
            self.itemconfigure(tag + "tx", fill=TXT_DIM if on else TXT_MUTE)
            im = rounded(28, self.h - 10, 7, FIELD, FIELD)
            self._imgs[tag] = im
            self.itemconfigure(tag + "bg", image=im)
        if not on:
            self.configure(cursor="")


# ---------------------------------------------------------------- 세그먼트
class Segmented(tk.Canvas):
    def __init__(self, master, options, value=None, bg=CARD, width=None, height=34,
                 command=None):
        self.options = options                      # [(label, value), ...]
        self.value = value if value is not None else options[0][1]
        self.command = command
        self.bg = bg
        seg_w = 0
        probe = tk.Label(master, font=UI(10, "bold"))
        for label, _ in options:
            probe.configure(text=label)
            seg_w = max(seg_w, probe.winfo_reqwidth() + 30)
        probe.destroy()
        self.seg_w = seg_w
        width = width or seg_w * len(options) + 8
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h = width, height
        self._imgs = {}
        self._hover = None
        self._draw()
        self.bind("<Button-1>", self._click)
        self.bind("<Motion>", self._motion)
        self.bind("<Leave>", lambda e: self._motion(None))

    def _index(self, x):
        i = int((x - 4) // self.seg_w)
        return max(0, min(len(self.options) - 1, i))

    def _draw(self):
        self.delete("all")
        track = rounded(self.w, self.h, 9, FIELD, self.bg, border=LINE)
        self._imgs["t"] = track
        self.create_image(0, 0, anchor="nw", image=track)
        idx = [v for _, v in self.options].index(self.value)
        pill = rounded(self.seg_w, self.h - 8, 7, mix(ACCENT, "#000000", 0.05), FIELD)
        self._imgs["p"] = pill
        self.create_image(4 + idx * self.seg_w, 4, anchor="nw", image=pill)
        for i, (label, _) in enumerate(self.options):
            if i == idx:
                col = "#ffffff"
            elif self._hover == i:
                col = TXT
            else:
                col = TXT_DIM
            self.create_text(4 + i * self.seg_w + self.seg_w / 2, self.h / 2,
                             text=label, fill=col, font=UI(10, "bold"))

    def _motion(self, event):
        new = None if event is None else self._index(event.x)
        if new != self._hover:
            self._hover = new
            self.configure(cursor="hand2" if new is not None else "")
            self._draw()

    def _click(self, event):
        val = self.options[self._index(event.x)][1]
        if val != self.value:
            self.set(val)
            if self.command:
                self.command(val)

    def get(self):
        return self.value

    def set(self, value):
        self.value = value
        self._draw()


# ---------------------------------------------------------------- 슬라이더
class Slider(tk.Canvas):
    def __init__(self, master, lo=0.25, hi=4.0, value=1.0, step=0.05, width=260, height=32,
                 bg=CARD, fmt="%.2fx", command=None):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.lo, self.hi, self.step = lo, hi, step
        self.value = value
        self.fmt, self.command, self.bg = fmt, command, bg
        self.label_w = 58
        self.w, self.h = width, height
        self.track_w = width - self.label_w
        self._imgs = {}
        self._drag = False
        self._draw()
        self.bind("<Button-1>", self._click)
        self.bind("<B1-Motion>", self._click)
        self.bind("<ButtonRelease-1>", lambda e: setattr(self, "_drag", False))
        self.bind("<MouseWheel>", self._wheel)
        self.bind("<Enter>", lambda e: self.configure(cursor="hand2"))
        self.bind("<Leave>", lambda e: self.configure(cursor=""))

    def _ratio(self):
        return (self.value - self.lo) / (self.hi - self.lo)

    def _draw(self):
        self.delete("all")
        cy = self.h // 2
        tw = self.track_w - 16
        base = rounded(tw, 6, 3, FIELD, self.bg)
        self._imgs["b"] = base
        self.create_image(8, cy - 3, anchor="nw", image=base)
        fw = max(6, int(tw * self._ratio()))
        fill = rounded(fw, 6, 3, ACCENT, self.bg, grad=(ACCENT, ACCENT2))
        self._imgs["f"] = fill
        self.create_image(8, cy - 3, anchor="nw", image=fill)
        kx = 8 + fw
        knob = circle(16, "#ffffff", self.bg)
        self._imgs["k"] = knob
        self.create_image(kx - 8, cy - 8, anchor="nw", image=knob)
        self.create_text(self.w - 4, cy, text=self.fmt % self.value, anchor="e",
                         fill=TXT, font=MONO(10, "bold"))

    def _click(self, event):
        tw = self.track_w - 16
        r = max(0.0, min(1.0, (event.x - 8) / tw))
        val = self.lo + (self.hi - self.lo) * r
        self.set(round(val / self.step) * self.step)
        if self.command:
            self.command(self.value)

    def _wheel(self, event):
        self.set(self.value + self.step * (1 if event.delta > 0 else -1))
        return "break"

    def get(self):
        return self.value

    def set(self, value):
        self.value = max(self.lo, min(self.hi, round(value, 4)))
        self._draw()


# ---------------------------------------------------------------- 진행 막대
class Bar(tk.Canvas):
    def __init__(self, master, width=260, height=8, bg=CARD, radius=4):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h, self.bg, self.r = width, height, bg, radius
        self._imgs = {}
        self.ratio = 0.0
        self._draw()

    def _draw(self):
        self.delete("all")
        base = rounded(self.w, self.h, self.r, FIELD, self.bg)
        self._imgs["b"] = base
        self.create_image(0, 0, anchor="nw", image=base)
        if self.ratio > 0.001:
            fw = max(self.h, int(self.w * self.ratio))
            fill = rounded(fw, self.h, self.r, ACCENT, self.bg, grad=(ACCENT, ACCENT2))
            self._imgs["f"] = fill
            self.create_image(0, 0, anchor="nw", image=fill)

    def set(self, ratio):
        ratio = max(0.0, min(1.0, ratio))
        if abs(ratio - self.ratio) < 0.004 and ratio not in (0.0, 1.0):
            return
        self.ratio = ratio
        self._draw()


# ---------------------------------------------------------------- 텍스트 입력
class TextField(tk.Canvas):
    def __init__(self, master, value="", width=200, height=36, bg=CARD, font=None,
                 justify="left"):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h, self.bg = width, height, bg
        self.var = tk.StringVar(value=value)
        self._imgs = {}
        self._focus = False
        self._paint()
        self.entry = tk.Entry(self, textvariable=self.var, bg=FIELD, fg=TXT, bd=0,
                              highlightthickness=0, insertbackground=ACCENT,
                              justify=justify, font=font or MONO(10, "bold"))
        self.create_window(14 if justify == "left" else width / 2, height / 2,
                           window=self.entry, width=width - 28,
                           anchor="w" if justify == "left" else "center")
        self.entry.bind("<FocusIn>", lambda e: self._set_focus(True))
        self.entry.bind("<FocusOut>", lambda e: self._set_focus(False))

    def _set_focus(self, on):
        self._focus = on
        self._paint()

    def _paint(self):
        self.delete("bgim")
        im = rounded(self.w, self.h, 9, FIELD, self.bg,
                     border=ACCENT if self._focus else LINE)
        self._imgs["bg"] = im
        self.create_image(0, 0, anchor="nw", image=im, tags="bgim")
        self.tag_lower("bgim")

    def get(self):
        return self.var.get()

    def set(self, value):
        self.var.set(value)


# ---------------------------------------------------------------- 드롭다운
class Select(tk.Canvas):
    def __init__(self, master, options, value=None, bg=CARD, width=120, height=34,
                 command=None):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.options = list(options)
        self.value = value if value is not None else self.options[0]
        self.command, self.bg = command, bg
        self.w, self.h = width, height
        self._imgs = {}
        self._pop = None
        self._hover = False
        self._draw()
        self.bind("<Button-1>", lambda e: self.open())
        self.bind("<Enter>", lambda e: self._set_hover(True))
        self.bind("<Leave>", lambda e: self._set_hover(False))

    def _set_hover(self, on):
        self._hover = on
        self.configure(cursor="hand2" if on else "")
        self._draw()

    def _draw(self):
        self.delete("all")
        fill = FIELD_HI if self._hover else FIELD
        im = rounded(self.w, self.h, 9, fill, self.bg, border=LINE)
        self._imgs["bg"] = im
        self.create_image(0, 0, anchor="nw", image=im)
        self.create_text(14, self.h / 2, text=str(self.value), anchor="w",
                         fill=TXT, font=MONO(10, "bold"))
        ch = icon("chevron", 16, TXT_DIM, fill)
        self._imgs["c"] = ch
        self.create_image(self.w - 24, self.h / 2 - 8, anchor="nw", image=ch)

    def open(self):
        if self._pop is not None:
            return self.close()
        rowh = 30
        pad = 6
        ph = rowh * len(self.options) + pad * 2
        pw = self.w
        top = tk.Toplevel(self)
        top.overrideredirect(True)
        top.attributes("-topmost", True)
        top.configure(bg=BG)
        x = self.winfo_rootx()
        y = self.winfo_rooty() + self.h + 6
        top.geometry("%dx%d+%d+%d" % (pw, ph, x, y))
        cv = tk.Canvas(top, width=pw, height=ph, bg=BG, highlightthickness=0, bd=0)
        cv.pack()
        bgim = rounded(pw, ph, 10, FIELD, BG, border=LINE)
        cv.image = bgim
        cv.create_image(0, 0, anchor="nw", image=bgim)
        rows = {}
        for i, opt in enumerate(self.options):
            y0 = pad + i * rowh
            tag = "row%d" % i
            rows[tag] = opt
            hl = rounded(pw - 10, rowh - 2, 6, FIELD, FIELD)
            cv.rowimgs = getattr(cv, "rowimgs", {})
            cv.rowimgs[tag] = hl
            cv.create_image(5, y0, anchor="nw", image=hl, tags=(tag, tag + "bg"))
            cv.create_text(16, y0 + rowh / 2, text=str(opt), anchor="w",
                           fill=ACCENT if opt == self.value else TXT,
                           font=MONO(10, "bold"), tags=(tag,))

            def enter(_e, t=tag):
                im = rounded(pw - 10, rowh - 2, 6, mix(FIELD, "#ffffff", 0.08), FIELD)
                cv.rowimgs[t] = im
                cv.itemconfigure(t + "bg", image=im)
                cv.configure(cursor="hand2")

            def leave(_e, t=tag):
                im = rounded(pw - 10, rowh - 2, 6, FIELD, FIELD)
                cv.rowimgs[t] = im
                cv.itemconfigure(t + "bg", image=im)

            cv.tag_bind(tag, "<Enter>", enter)
            cv.tag_bind(tag, "<Leave>", leave)
            cv.tag_bind(tag, "<Button-1>", lambda _e, o=opt: self._choose(o))

        self._pop = top
        top.bind("<FocusOut>", lambda e: self.close())
        top.focus_set()
        self.winfo_toplevel().bind("<Button-1>", self._outside, add="+")

    def _outside(self, event):
        if self._pop is not None and event.widget is not self:
            self.close()

    def _choose(self, opt):
        self.close()
        if opt != self.value:
            self.value = opt
            self._draw()
            if self.command:
                self.command(opt)

    def close(self):
        if self._pop is not None:
            try:
                self._pop.destroy()
            except Exception:
                pass
            self._pop = None

    def get(self):
        return self.value

    def set(self, value):
        self.value = value
        self._draw()


# ---------------------------------------------------------------- 단축키 입력칸
class KeyField(tk.Canvas):
    """클릭하면 다음에 누른 키/마우스 버튼을 그대로 받아오는 칸. 오른쪽 x 로 해제."""

    def __init__(self, master, value="없음", bg=CARD, width=176, height=36,
                 command=None, on_clear=None):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h, self.bg = width, height, bg
        self.command, self.on_clear = command, on_clear
        self.text = value
        self.dim = value in ("", "없음")
        self.waiting = False
        self._imgs = {}
        self._hover = False
        self._hover_x = False
        self._draw()
        self.bind("<Motion>", self._motion)
        self.bind("<Leave>", lambda e: self._motion(None))
        self.bind("<Button-1>", self._click)

    def _motion(self, event):
        hover = event is not None
        hx = hover and event.x > self.w - 32
        if (hover, hx) != (self._hover, self._hover_x):
            self._hover, self._hover_x = hover, hx
            self.configure(cursor="hand2" if hover else "")
            self._draw()

    def _click(self, event):
        if event.x > self.w - 32 and self.on_clear:
            self.on_clear()
        elif self.command:
            self.command()

    def _draw(self):
        self.delete("all")
        if self.waiting:
            fill, border = mix(self.bg, ACCENT, 0.16), ACCENT
        elif self._hover:
            fill, border = FIELD_HI, mix(LINE, "#ffffff", 0.1)
        else:
            fill, border = FIELD, LINE
        im = rounded(self.w, self.h, 9, fill, self.bg, border=border)
        self._imgs["bg"] = im
        self.create_image(0, 0, anchor="nw", image=im)
        col = ACCENT if self.waiting else (TXT_MUTE if self.dim else TXT)
        self.create_text(14, self.h / 2, text=self.text, anchor="w", fill=col,
                         font=MONO(10, "bold"))
        if not self.waiting:
            self.create_text(self.w - 18, self.h / 2 - 1, text="×",
                             fill=TXT if self._hover_x else TXT_MUTE, font=UI(12))

    def set_text(self, text, dim=False, waiting=False):
        self.text, self.dim, self.waiting = text, dim, waiting
        self._draw()


# ---------------------------------------------------------------- 상태 뱃지
class StatusPill(tk.Canvas):
    def __init__(self, master, bg=BG, width=150, height=32):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.w, self.h, self.bg = width, height, bg
        self._imgs = {}
        self._text, self._color, self._pulse = "대기 중", TXT_DIM, False
        self._phase = 0
        self._draw()
        self._tick()

    def set(self, text, color, pulse=False):
        self._text, self._color, self._pulse = text, color, pulse
        self._draw()

    def _draw(self):
        self.delete("all")
        tint = mix(self.bg, self._color, 0.14)
        im = rounded(self.w, self.h, self.h // 2, tint, self.bg,
                     border=mix(self.bg, self._color, 0.3))
        self._imgs["bg"] = im
        self.create_image(0, 0, anchor="nw", image=im)
        amp = 0.45 + 0.55 * abs(1 - (self._phase % 20) / 10.0) if self._pulse else 1.0
        dot = circle(9, mix(tint, self._color, amp), tint)
        self._imgs["d"] = dot
        self.create_image(13, self.h / 2 - 4.5, anchor="nw", image=dot)
        self.create_text(30, self.h / 2, text=self._text, anchor="w",
                         fill=self._color, font=UI(9, "bold"))

    def _tick(self):
        try:
            if not self.winfo_exists():
                return
            if self._pulse:
                self._phase += 1
                self._draw()
            self.after(70, self._tick)
        except tk.TclError:            # 창이 닫히는 중이면 조용히 종료
            return


# ---------------------------------------------------------------- 사이드바 항목
class NavItem(tk.Canvas):
    def __init__(self, master, text, icon_name, command, bg=SIDE, width=186, height=42):
        super().__init__(master, width=width, height=height, bg=bg,
                         highlightthickness=0, bd=0)
        self.text, self.icon_name, self.command, self.bg = text, icon_name, command, bg
        self.w, self.h = width, height
        self.active = False
        self._hover = False
        self._imgs = {}
        self._draw()
        self.bind("<Button-1>", lambda e: command())
        self.bind("<Enter>", lambda e: self._set_hover(True))
        self.bind("<Leave>", lambda e: self._set_hover(False))

    def _set_hover(self, on):
        self._hover = on
        self.configure(cursor="hand2" if on else "")
        self._draw()

    def _draw(self):
        self.delete("all")
        if self.active:
            fill = mix(self.bg, ACCENT, 0.16)
        elif self._hover:
            fill = mix(self.bg, "#ffffff", 0.05)
        else:
            fill = self.bg
        im = rounded(self.w, self.h, 10, fill, self.bg)
        self._imgs["bg"] = im
        self.create_image(0, 0, anchor="nw", image=im)
        if self.active:
            bar = rounded(3, 18, 2, ACCENT, fill)
            self._imgs["bar"] = bar
            self.create_image(0, self.h / 2 - 9, anchor="nw", image=bar)
        col = ACCENT if self.active else (TXT if self._hover else TXT_DIM)
        ic = icon(self.icon_name, 18, col, fill)
        self._imgs["ic"] = ic
        self.create_image(16, self.h / 2 - 9, anchor="nw", image=ic)
        self.create_text(46, self.h / 2, text=self.text, anchor="w", fill=col,
                         font=UI(10, "bold" if self.active else "normal"))

    def set_active(self, on):
        self.active = on
        self._draw()
