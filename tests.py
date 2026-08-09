# -*- coding: utf-8 -*-
"""MacroStudio 자체 검사.  python tests.py

화면에 창을 띄우지 않고, 실제 마우스·키보드 입력도 내보내지 않는다.
(주입기는 가짜로 갈아 끼우고, 업데이트 확인은 네트워크를 타지 않게 막는다)
"""

import ctypes
import json
import os
import sys
import time
import tkinter as tk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import core
import macro
import ui_kit
import updater
from pynput import keyboard as kbd, mouse as ms

macro.updater.check = lambda repo=None: {"error": "검사 모드"}   # 네트워크 차단

FAILS = []


def check(name, ok, extra=""):
    print(("  OK   " if ok else "  실패 ") + name + ("   " + str(extra) if extra != "" else ""))
    if not ok:
        FAILS.append(name)


def section(title):
    print("\n[%s]" % title)


class FakeSender:
    """진짜 입력 대신 기록만 남기는 주입기."""

    def __init__(self):
        self.clicks, self.keys, self.moves = [], [], []

    def position(self):
        return (100, 100)

    def move(self, x, y):
        self.moves.append((x, y))

    def click(self, name, down, x=None, y=None):
        if down:
            self.clicks.append((name, x, y))

    def scroll(self, dx, dy):
        pass

    def key(self, key_obj, down):
        if down:
            self.keys.append(getattr(key_obj, "name", getattr(key_obj, "char", "?")))


def wait_stop(worker, limit=6):
    t0 = time.perf_counter()
    while worker.running and time.perf_counter() - t0 < limit:
        time.sleep(0.01)
    return time.perf_counter() - t0


# ---------------------------------------------------------------- 1. 키/단축키
def test_keys():
    section("키와 단축키")
    for key in (kbd.Key.f6, kbd.Key.space, kbd.KeyCode.from_char("a")):
        check("직렬화 왕복 %s" % key, core.spec_to_key(core.key_to_spec(key)) == key)
    check("F6 토큰", core.hotkey_token(kbd.Key.f6) == "F6")
    check("한글 자판도 자판 위치 기준",
          core.hotkey_token(kbd.KeyCode(char="ㄱ", vk=82)) == "r")
    check("마우스 토큰", core.mouse_token(ms.Button.x2) == "mouse:x2")
    check("조합 순서 고정", core.make_spec({"shift", "ctrl"}, "q") == "ctrl+shift+q")
    check("조합 라벨", core.hotkey_label("ctrl+q") == "Ctrl + Q")
    check("마우스 라벨(짧게)", core.hotkey_label("mouse:x2", short=True) == "M5")
    mods, key = core.parse_combo("ctrl+shift+a")
    check("조합 파싱", mods == ["ctrl", "shift"] and key == kbd.KeyCode.from_char("a"))
    check("이상한 키 거부", core.parse_combo("없는키")[1] is None)


# ---------------------------------------------------------------- 2. 기록기
def test_recorder():
    section("기록기")
    rec = core.Recorder({"F6", "mouse:x2"})
    rec.events = [{"t": 5.0}, {"t": 6.5}]
    rec._normalize()
    check("시간축을 0 기준으로", rec.events[0]["t"] == 0 and rec.events[1]["t"] == 1.5)

    rec.active, rec._t0 = True, time.perf_counter()
    rec.events = []
    rec._on_press(kbd.Key.f6)                       # 단축키 -> 제외
    rec._on_press(kbd.KeyCode.from_char("q"))
    rec._on_click(1, 2, ms.Button.x2, True)         # 단축키 버튼 -> 제외
    rec._on_click(3, 4, ms.Button.left, True)
    rec.active = False
    kinds = [(e["e"], e.get("k", {}).get("c") or e.get("b")) for e in rec.events]
    check("단축키로 쓰는 키·버튼은 기록 제외", kinds == [("key", "q"), ("click", "left")], kinds)

    real = core.winput.window_root_at
    core.winput.window_root_at = lambda x, y: 42 if (x, y) == (9, 9) else 7
    rec.skip_hwnd = lambda: 42
    rec.active, rec._t0 = True, time.perf_counter()
    rec.events = []
    rec._on_click(9, 9, ms.Button.left, True)       # 매크로 창 직접 클릭 -> 제외
    rec._on_click(8, 8, ms.Button.left, True)       # 다른 창이 위에 -> 기록
    rec.active = False
    core.winput.window_root_at = real
    check("매크로 창 직접 클릭만 제외", [(e["x"], e["y"]) for e in rec.events] == [(8, 8)])


# ---------------------------------------------------------------- 3. 재생기
def test_player():
    section("재생기")
    fake = FakeSender()
    p = core.Player(lambda m: None, lambda: None)
    p.sender, p.smooth = fake, False
    events = [{"t": 0.0, "e": "click", "x": 10, "y": 10, "b": "left", "p": True},
              {"t": 0.05, "e": "click", "x": 10, "y": 10, "b": "left", "p": False},
              {"t": 0.1, "e": "key", "a": "d", "k": {"s": "f13"}},
              {"t": 0.15, "e": "key", "a": "u", "k": {"s": "f13"}}]
    p.start(events, 2, 1.0, 0)
    wait_stop(p)
    check("두 바퀴 재생", len(fake.clicks) == 2 and len(fake.keys) == 2,
          (len(fake.clicks), len(fake.keys)))

    fake2 = FakeSender()
    p2 = core.Player(lambda m: None, lambda: None)
    p2.sender, p2.smooth = fake2, False
    t0 = time.perf_counter()
    p2.start([{"t": 0.0, "e": "key", "a": "d", "k": {"s": "f13"}},
              {"t": 1.0, "e": "key", "a": "u", "k": {"s": "f13"}}], 1, 4.0, 0)
    wait_stop(p2)
    took = time.perf_counter() - t0
    check("4배속이면 4분의 1 시간", 0.2 < took < 0.45, "%.2f초" % took)

    fake3 = FakeSender()
    p3 = core.Player(lambda m: None, lambda: None)
    p3.sender, p3.smooth = fake3, True
    p3.start([{"t": 0.0, "e": "click", "x": 100, "y": 100, "b": "left", "p": True},
              {"t": 0.5, "e": "click", "x": 700, "y": 600, "b": "left", "p": True}], 1, 1.0, 0)
    wait_stop(p3)
    check("부드럽게 이동이 중간 지점을 만든다", len(fake3.moves) >= 8, len(fake3.moves))
    check("마지막 클릭은 정확한 좌표", fake3.clicks[-1][1:] == (700, 600), fake3.clicks[-1])


# ---------------------------------------------------------------- 4. 반복 작업
def test_workers():
    section("반복 작업")
    hits = []
    w = core.RepeatWorker(lambda m: None, lambda: None)
    w.start(lambda: hits.append(1), 5, 4, "검사")
    wait_stop(w)
    check("정해진 횟수만 실행", len(hits) == 4, len(hits))

    w2 = core.RepeatWorker(lambda m: None, lambda: None)
    w2.start(lambda: None, 1000, 0, "검사")          # 1초 간격
    time.sleep(0.2)
    t0 = time.perf_counter()
    w2.stop()
    wait_stop(w2)
    check("긴 간격 중에도 곧바로 정지", (time.perf_counter() - t0) < 0.1,
          "%.0f ms" % ((time.perf_counter() - t0) * 1000))

    order = []
    sq = core.SequenceWorker(lambda m: None, lambda: None)
    steps = [(lambda: order.append("a"), 0.01), (lambda: order.append("b"), 0.01),
             (lambda: order.append("c"), 0.01)]
    sq.start(steps, 2, "검사")
    wait_stop(sq)
    check("시퀀스 순서·바퀴", order == ["a", "b", "c"] * 2 and sq.cycles == 2, order)


# ---------------------------------------------------------------- 5. 업데이트
def test_updater():
    section("자동 업데이트")
    check("버전 비교", updater.is_newer("2.10", "2.9") and
          not updater.is_newer("2.4", "2.4") and updater.is_newer("2.4.1", "2.4"))
    check("버전 파싱", updater.parse_version("v2.3.1") == (2, 3, 1))
    tmp = os.path.join(os.environ.get("TEMP", "."), "_ms_verify_test.exe")

    def verified(data):
        with open(tmp, "wb") as fp:
            fp.write(data)
        try:
            return updater.verify(tmp)
        except ValueError:
            return False

    check("실행 파일 검증 통과", verified(b"MZ" + b"\0" * (2 * 1024 * 1024)))
    check("실행 파일이 아니면 거부", not verified(b"<html>" + b"\0" * (2 * 1024 * 1024)))
    check("너무 작으면 거부", not verified(b"MZ" + b"\0" * 100))
    os.remove(tmp)


# ---------------------------------------------------------------- 6. 앱 화면
def test_app():
    section("앱 화면")
    if os.path.exists(macro.CONFIG_PATH):
        os.remove(macro.CONFIG_PATH)
    root = tk.Tk()
    root.withdraw()
    app = macro.MacroApp(root)
    app._update_stat()
    fake = FakeSender()
    app.sender = fake
    app.player.sender = fake
    root.update()

    for key in ("record", "click", "key", "set", "help"):
        app.show_page(key)
        root.update()
    check("페이지 5개 전환", app.current == "help" and len(app.pages) == 5, list(app.pages))

    app.st_repeat.set(-5)
    check("스테퍼 하한", app.st_repeat.get() == 0)
    app.st_repeat.var.set("숫자아님")
    check("스테퍼에 글자를 넣어도 직전 값", app.st_repeat.get() == 0)
    app.sl_speed.set(99)
    check("슬라이더 상한", app.sl_speed.get() == 4.0)
    app.sl_speed.set(1.0)

    app.st_ci.set(20)
    app.st_cc.set(3)
    app.toggle_click()
    for _ in range(200):
        root.update()
        if not app.clicker.running:
            break
        time.sleep(0.01)
    check("자동 클릭 3회", len(fake.clicks) == 3, len(fake.clicks))
    for _ in range(20):                       # 끝났다는 알림이 화면에 반영될 틈을 준다
        root.update()
        time.sleep(0.02)
    check("버튼 라벨 복구", app.btn_click.txt == "자동 클릭 시작", app.btn_click.txt)

    app.hotkeys["click"] = "F8"
    app._hk_capture = "click"
    app._set_hotkey("click", app.hotkeys["record"])       # 이미 쓰는 키
    check("중복 단축키 거부", app.hotkeys["click"] == "F8")
    fired = []
    app.msgq.put = lambda item: fired.append(item)
    app._fire("ctrl+ESC")
    check("보조키를 쥐고 있어도 정지키 동작", fired == [("hotkey", "stop")], fired)

    # 숨긴 창은 실제 크기가 잡히지 않으므로, 어떤 크기를 요청했는지로 확인한다
    asked = []
    real_geo = root.geometry
    root.geometry = lambda spec=None: asked.append(spec) if spec else real_geo()
    app.apply_window_size("large")
    root.geometry = real_geo
    check("창 크기 프리셋 요청", asked and asked[0].startswith("%dx%d" % macro.win_size("large")),
          asked)
    app.apply_window_size("normal")
    app.apply_on_top(True)
    check("항상 위에 표시", bool(root.attributes("-topmost")))
    app.apply_on_top(False)

    app.tg_sound.set(True)
    app.sg_font.set(1)
    app.save_config()
    saved = json.load(open(macro.CONFIG_PATH, encoding="utf-8"))
    check("설정 저장", saved.get("sound") is True and saved.get("font_delta") == 1)
    real_state = root.state
    root.state = lambda *a: "normal"          # 보이는 창인 척 (숨긴 창은 위치를 안 남긴다)
    geo_now = app._window_geometry()
    root.state = real_state
    check("보이는 창이면 위치를 저장", "x" in geo_now, geo_now)

    ui_kit.set_theme(font_delta=2)
    fitted = macro.MacroApp._fit_geometry("800x600+10+10", "normal")
    need = macro.win_size("normal")
    check("작게 저장된 창은 글자에 맞춰 넓힘",
          int(fitted.split("x")[0]) >= need[0], fitted)
    ui_kit.set_theme(font_delta=0)

    macro.messagebox.askyesno = lambda *a, **k: True
    app.reset_settings()
    check("설정 초기화", not os.path.exists(macro.CONFIG_PATH))

    app._skip_save = True
    app.on_close()
    if os.path.exists(macro.CONFIG_PATH):
        os.remove(macro.CONFIG_PATH)


# ---------------------------------------------------------------- 7. 좌표·주입
def test_coords():
    section("좌표와 입력 주입")
    mode = macro.make_dpi_aware()
    check("화면 배율 인식 설정", mode is not None, mode)
    aware = ctypes.c_int()
    try:
        ctypes.windll.shcore.GetProcessDpiAwareness(None, ctypes.byref(aware))
        check("가상화되지 않음 (좌표 어긋남 방지)", aware.value >= 1, aware.value)
    except Exception as exc:
        check("가상화되지 않음 (좌표 어긋남 방지)", False, exc)

    w, h = macro.screen_size()
    check("화면 크기를 읽음", w > 100 and h > 100, (w, h))

    import winput
    here = winput.cursor_pos()
    check("커서 위치를 읽음", here is not None, here)

    # 주입기는 SendInput 이 막혀 있어도 예전 방식으로 반드시 움직여야 한다
    s = core.Sender()
    target = (here[0] + 60, here[1])
    s.move(*target)
    time.sleep(0.08)
    now = winput.cursor_pos()
    s.move(*here)
    check("주입기가 실제로 커서를 옮김", abs(now[0] - target[0]) <= 2, (target, now))


def main():
    t0 = time.perf_counter()
    for fn in (test_keys, test_recorder, test_player, test_workers, test_updater,
               test_coords, test_app):
        fn()
    print("\n%d초 걸림." % round(time.perf_counter() - t0),
          "실패: " + (", ".join(FAILS) if FAILS else "없음"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
