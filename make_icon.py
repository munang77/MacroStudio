# -*- coding: utf-8 -*-
"""앱 아이콘(icon.ico) 생성. 사이드바 로고와 같은 모양을 여러 크기로 굽는다."""

import os

from PIL import Image, ImageDraw

ACCENT, ACCENT2 = (91, 140, 255), (139, 108, 255)
SIZES = (16, 24, 32, 48, 64, 128, 256)
SS = 8                                   # 크게 그린 뒤 줄여서 계단현상 제거


def render(size):
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    grad = Image.new("RGB", (S, S), ACCENT)
    gd = ImageDraw.Draw(grad)
    for x in range(S):
        t = x / max(1, S - 1)
        gd.line([(x, 0), (x, S)],
                fill=tuple(int(ACCENT[i] + (ACCENT2[i] - ACCENT[i]) * t) for i in range(3)))

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.24), fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    d.polygon([(S * 0.36, S * 0.22), (S * 0.36, S * 0.76), (S * 0.48, S * 0.63),
               (S * 0.57, S * 0.82), (S * 0.68, S * 0.76), (S * 0.59, S * 0.58),
               (S * 0.73, S * 0.55)], fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    icons = [render(s) for s in SIZES]
    out = os.path.join(here, "icon.ico")
    icons[-1].save(out, format="ICO", sizes=[(s, s) for s in SIZES])
    print("만들었습니다:", out)


if __name__ == "__main__":
    main()
