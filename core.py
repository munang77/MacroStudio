# -*- coding: utf-8 -*-
"""MacroStudio 코어 - 기록기 / 재생기 / 반복 실행기.

UI 와 완전히 분리되어 있어 단독으로 테스트할 수 있다.
"""

import ctypes
import math
import threading
import time

from pynput import mouse, keyboard

import winput

try:                                   # 윈도우에서 실제 보조키 상태를 직접 읽기 위한 준비
    _user32 = ctypes.windll.user32
    _user32.GetAsyncKeyState.restype = ctypes.c_short
except Exception:                      # 윈도우가 아니면 추적값으로 대체
    _user32 = None

_MOD_VK = {"ctrl": (0x11,), "shift": (0x10,), "alt": (0x12,), "win": (0x5B, 0x5C)}

MOD_KEYS = {
    "ctrl": keyboard.Key.ctrl,
    "shift": keyboard.Key.shift,
    "alt": keyboard.Key.alt,
    "win": keyboard.Key.cmd,
    "cmd": keyboard.Key.cmd,
}

BUTTONS = {
    "left": mouse.Button.left,
    "right": mouse.Button.right,
    "middle": mouse.Button.middle,
}
for _name in ("x1", "x2"):               # 옆 버튼 (없는 환경도 있어서 있을 때만)
    if hasattr(mouse.Button, _name):
        BUTTONS[_name] = getattr(mouse.Button, _name)

MOD_ORDER = ("ctrl", "shift", "alt", "win")

MOUSE_LABEL = {"left": "왼쪽 클릭", "right": "오른쪽 클릭", "middle": "휠 클릭",
               "x1": "옆 버튼 1", "x2": "옆 버튼 2"}
MOUSE_SHORT = {"left": "M-L", "right": "M-R", "middle": "M-W", "x1": "M4", "x2": "M5"}


# ---------------------------------------------------------------- 키 직렬화
def key_to_spec(key):
    """pynput 키 객체를 JSON 저장용 dict 로 변환."""
    if isinstance(key, keyboard.Key):
        return {"s": key.name}
    if isinstance(key, keyboard.KeyCode):
        if key.char is not None:
            return {"c": key.char}
        if key.vk is not None:
            return {"v": key.vk}
    return {"s": str(key)}


def spec_to_key(spec):
    """저장된 dict 를 pynput 키 객체로 복원."""
    if "s" in spec:
        try:
            return getattr(keyboard.Key, spec["s"])
        except AttributeError:
            return keyboard.KeyCode.from_char(spec["s"][:1])
    if "c" in spec:
        return keyboard.KeyCode.from_char(spec["c"])
    if "v" in spec:
        return keyboard.KeyCode.from_vk(spec["v"])
    return None


def canon_name(key):
    """단축키 비교용 정규화 이름 (F6, ESC, a ...)."""
    if isinstance(key, keyboard.Key):
        name = key.name
        if name.startswith("ctrl"):
            return "ctrl"
        if name.startswith("shift"):
            return "shift"
        if name.startswith("alt"):
            return "alt"
        if name.startswith("cmd"):
            return "win"
        return name.upper()          # f6 -> F6, esc -> ESC
    if isinstance(key, keyboard.KeyCode) and key.char is not None:
        return key.char.lower()
    return ""


# ---------------------------------------------------------------- 단축키 토큰
def hotkey_token(key):
    """키 객체를 단축키 비교/저장용 토큰으로. 한글 자판 상태에서도 같은 키는 같은 토큰."""
    if isinstance(key, keyboard.Key):
        return canon_name(key)
    if isinstance(key, keyboard.KeyCode):
        vk = getattr(key, "vk", None)
        if vk is not None and (48 <= vk <= 57 or 65 <= vk <= 90):
            return chr(vk).lower()          # 한/영 상태와 무관하게 자판 위치 기준
        if key.char and key.char.isascii():
            return key.char.lower()
        if vk is not None:
            return "vk%d" % vk
    return ""


def mouse_token(button):
    return "mouse:" + button.name


_MOUSE_VK = {"left": 0x01, "right": 0x02, "middle": 0x04, "x1": 0x05, "x2": 0x06}
_token_vk_cache = {}


def token_vk(token):
    """단축키 토큰 -> 윈도우 가상키 코드. 모르면 None."""
    if token in _token_vk_cache:
        return _token_vk_cache[token]
    vk = None
    if token.startswith("mouse:"):
        vk = _MOUSE_VK.get(token[6:])
    elif token.startswith("vk") and token[2:].isdigit():
        vk = int(token[2:])
    elif len(token) == 1 and token.isalnum():
        vk = ord(token.upper())
    else:
        for key in keyboard.Key:
            if canon_name(key) == token:
                vk = getattr(key.value, "vk", None)
                break
    _token_vk_cache[token] = vk
    return vk


def is_pressed(token):
    """그 키가 지금 물리적으로 눌려 있는지. 우리가 만든 가짜 입력과 진짜를 가르는 데 쓴다."""
    if _user32 is None:
        return False
    vk = token_vk(token)
    if vk is None:
        return False
    return bool(_user32.GetAsyncKeyState(vk) & 0x8000)


def current_mods(fallback=None):
    """지금 실제로 눌려 있는 보조키. 눌렸다 뗀 신호를 놓쳐도(Win+L 등) 어긋나지 않는다."""
    if _user32 is None:
        return set(fallback or ())
    mods = set()
    for name, vks in _MOD_VK.items():
        if any(_user32.GetAsyncKeyState(vk) & 0x8000 for vk in vks):
            mods.add(name)
    return mods


def make_spec(mods, token):
    """누른 보조키 집합 + 주 토큰 -> 'ctrl+shift+q' 같은 단축키 문자열."""
    return "+".join([m for m in MOD_ORDER if m in mods] + [token])


def spec_main(spec):
    return (spec or "").split("+")[-1]


def spec_mods(spec):
    return set((spec or "").split("+")[:-1])


def hotkey_label(spec, short=False):
    """화면 표시용 이름. short=True 면 버튼 위 작은 칩에 들어갈 짧은 형태."""
    if not spec or spec == "없음":
        return "없음"
    main = spec_main(spec)
    if main.startswith("mouse:"):
        btn = main[6:]
        if short:
            text = MOUSE_SHORT.get(btn, btn)
        else:
            text = "마우스 " + MOUSE_LABEL.get(btn, btn)
    elif main.startswith("vk"):
        text = main.upper()
    else:
        text = main.upper()
    mods = [m for m in MOD_ORDER if m in spec_mods(spec)]
    if not mods:
        return text
    join = "+" if short else " + "
    return join.join([m.capitalize() for m in mods] + [text])


def parse_combo(text):
    """'ctrl+shift+a' -> (['ctrl','shift'], 키객체). 실패 시 (None, None)."""
    text = (text or "").strip().lower()
    if not text:
        return None, None
    parts = [p.strip() for p in text.split("+") if p.strip()]
    if not parts:
        return None, None
    mods, main = [], parts[-1]
    for p in parts[:-1]:
        if p not in MOD_KEYS:
            return None, None
        mods.append(p)
    if len(main) == 1:
        return mods, keyboard.KeyCode.from_char(main)
    if hasattr(keyboard.Key, main):
        return mods, getattr(keyboard.Key, main)
    return None, None


# ---------------------------------------------------------------- 기록기
class Recorder:
    MOVE_SAMPLE = 0.016  # 마우스 이동 최소 기록 간격(초)

    def __init__(self, blocked_names, skip_hwnd=None):
        self.events = []
        self.active = False
        self.record_move = True
        self.blocked = blocked_names          # 단축키는 기록에서 제외
        self.skip_hwnd = skip_hwnd            # 이 창(매크로 창)을 실제로 누른 클릭만 제외
        self._m_listener = None
        self._k_listener = None
        self._t0 = 0.0
        self._last_move = 0.0
        self._lock = threading.Lock()

    def _stamp(self):
        return time.perf_counter() - self._t0

    def _add(self, ev):
        with self._lock:
            self.events.append(ev)

    def start(self, record_move=True):
        if self.active:
            return
        self.events = []
        self.record_move = record_move
        self._t0 = time.perf_counter()
        self._last_move = 0.0
        self.active = True

        self._m_listener = mouse.Listener(
            on_move=self._on_move, on_click=self._on_click, on_scroll=self._on_scroll
        )
        self._k_listener = keyboard.Listener(
            on_press=self._on_press, on_release=self._on_release
        )
        self._m_listener.start()
        self._k_listener.start()

    def stop(self):
        self.active = False
        for lis in (self._m_listener, self._k_listener):
            if lis is not None:
                try:
                    lis.stop()
                except Exception:
                    pass
        self._m_listener = self._k_listener = None
        self._normalize()
        return self.events

    def duration(self):
        return self.events[-1]["t"] if self.events else 0.0

    def _normalize(self):
        """첫 이벤트 기준으로 시간축을 0 으로 맞춘다."""
        with self._lock:
            if not self.events:
                return
            base = self.events[0]["t"]
            for ev in self.events:
                ev["t"] = round(max(0.0, ev["t"] - base), 4)

    # --- 콜백 -----------------------------------------------------
    def _on_move(self, x, y):
        if not self.active or not self.record_move:
            return
        now = self._stamp()
        if now - self._last_move < self.MOVE_SAMPLE:
            return
        self._last_move = now
        self._add({"t": round(now, 4), "e": "move", "x": x, "y": y})

    def _hit_own_window(self, x, y):
        """매크로 창을 직접 누른 클릭인지 (기록하면 재생 때 자기 버튼을 눌러버린다).

        좌표만 보면 매크로 창이 뒤에 깔려 있을 때 대상 프로그램 클릭까지 버려지므로,
        그 지점에서 실제로 맨 위에 있는 창을 확인한다.
        """
        own = self.skip_hwnd() if callable(self.skip_hwnd) else self.skip_hwnd
        if not own:
            return False
        top = winput.window_root_at(x, y)
        return bool(top) and top == own

    def _on_click(self, x, y, button, pressed):
        if not self.active or mouse_token(button) in self.blocked:
            return
        if self._hit_own_window(x, y):
            return
        self._add({
            "t": round(self._stamp(), 4), "e": "click",
            "x": x, "y": y, "b": button.name, "p": bool(pressed),
        })

    def _on_scroll(self, x, y, dx, dy):
        if not self.active:
            return
        self._add({
            "t": round(self._stamp(), 4), "e": "scroll",
            "x": x, "y": y, "dx": dx, "dy": dy,
        })

    def _on_press(self, key):
        if not self.active or hotkey_token(key) in self.blocked:
            return
        self._add({"t": round(self._stamp(), 4), "e": "key", "a": "d", "k": key_to_spec(key)})

    def _on_release(self, key):
        if not self.active or hotkey_token(key) in self.blocked:
            return
        self._add({"t": round(self._stamp(), 4), "e": "key", "a": "u", "k": key_to_spec(key)})


# ---------------------------------------------------------------- 입력 주입
class Sender:
    """재생·자동클릭·연타가 쓰는 입력 주입기.

    가능하면 SendInput(실제 마우스/키보드와 같은 경로)으로 보내고, 안 되면 pynput 으로.
    """

    def __init__(self, use_winput=True):
        self.use = bool(use_winput and winput.AVAILABLE)
        self.mouse = mouse.Controller()
        self.kb = keyboard.Controller()

    # --- 마우스 ---------------------------------------------------
    def position(self):
        return winput.cursor_pos() if self.use else self.mouse.position

    def move(self, x, y):
        if self.use and winput.move_to(x, y):
            return
        self.mouse.position = (int(x), int(y))

    def click(self, name, down, x=None, y=None):
        """이동과 누름을 한 번에 보낸다 (따로 보내면 눌린 위치가 어긋날 수 있다)."""
        if self.use and winput.button(name, down, x, y):
            return
        if x is not None:
            self.mouse.position = (int(x), int(y))
        btn = BUTTONS.get(name, mouse.Button.left)
        (self.mouse.press if down else self.mouse.release)(btn)

    def scroll(self, dx, dy):
        if self.use and winput.wheel(dx, dy):
            return
        self.mouse.scroll(dx, dy)

    # --- 키보드 ---------------------------------------------------
    @staticmethod
    def _vk_of(key):
        if isinstance(key, keyboard.Key):
            return getattr(key.value, "vk", None)
        if isinstance(key, keyboard.KeyCode):
            if key.vk:
                return key.vk
            if key.char:
                # 시프트 상태는 기록된 키 이벤트가 따로 담고 있으므로 자판 위치만 쓴다
                return winput.vk_for_char(key.char)
        return None

    def key(self, key_obj, down):
        if self.use:
            vk = self._vk_of(key_obj)
            if vk and winput.key_vk(vk, down):
                return
            char = getattr(key_obj, "char", None)
            if char and winput.key_unicode(char, down):
                return
        (self.kb.press if down else self.kb.release)(key_obj)


# ---------------------------------------------------------------- 재생기
class Player:
    GLIDE_DIST = 24                       # 이만큼 떨어져 있으면 미끄러지듯 이동
    GLIDE_MAX = 0.12                      # 미끄러짐에 쓰는 최대 시간(초)
    GLIDE_STEP = 0.008                    # 미끄러짐 한 칸(초)

    def __init__(self, log, done, progress=None):
        self.log = log
        self.done = done
        self.progress = progress          # (현재회차, 진행률 0~1) 콜백
        self._thread = None
        self._stop = threading.Event()
        self.running = False
        self.smooth = True                # 점프하지 않고 자연스럽게 이동
        self.sender = Sender()
        self._cursor = None               # 마지막으로 옮긴 커서 위치

    def start(self, events, repeat, speed, gap_ms):
        if self.running or not events:
            return False
        self._stop.clear()
        self.running = True
        self._thread = threading.Thread(
            target=self._run, args=(list(events), repeat, max(0.05, speed), gap_ms / 1000.0),
            daemon=True,
        )
        self._thread.start()
        return True

    def stop(self):
        self._stop.set()

    def _sleep_until(self, when):
        while True:
            remain = when - time.perf_counter()
            if remain <= 0 or self._stop.is_set():
                return
            time.sleep(min(remain, 0.005))

    def _approach(self, target_time, pos):
        """다음 지점까지 남은 시간 동안 커서를 미끄러지듯 옮긴다 (순간이동 방지)."""
        now = time.perf_counter()
        total = target_time - now
        if not (self.smooth and pos and self._cursor) or total <= 0.012:
            self._sleep_until(target_time)
            return
        x0, y0 = self._cursor
        dist = math.hypot(pos[0] - x0, pos[1] - y0)
        if dist < self.GLIDE_DIST:
            self._sleep_until(target_time)
            return
        glide = min(total - 0.004, self.GLIDE_MAX)
        start_at = target_time - glide
        self._sleep_until(start_at)
        steps = max(2, int(glide / self.GLIDE_STEP))
        for i in range(1, steps):
            if self._stop.is_set():
                return
            t = i / steps
            ease = t * t * (3 - 2 * t)                 # 처음과 끝이 부드럽게
            self.sender.move(round(x0 + (pos[0] - x0) * ease),
                             round(y0 + (pos[1] - y0) * ease))
            self._sleep_until(start_at + glide * t)
        self._sleep_until(target_time)

    def _run(self, events, repeat, speed, gap):
        held_keys, held_btns = set(), set()
        loop = 0
        total = len(events)
        self._cursor = self.sender.position()
        first_pos = next(((e["x"], e["y"]) for e in events if "x" in e), None)
        try:
            while not self._stop.is_set():
                loop += 1
                self.log("재생 %d회차 시작 (%d 이벤트)" % (loop, total))
                # 첫 지점이 멀면 곧장 순간이동하지 않도록 진입 시간을 조금 준다
                lead = 0.0
                if self.smooth and first_pos and self._cursor:
                    if math.hypot(first_pos[0] - self._cursor[0],
                                  first_pos[1] - self._cursor[1]) >= self.GLIDE_DIST:
                        lead = self.GLIDE_MAX + 0.02
                t_start = time.perf_counter() + lead
                for i, ev in enumerate(events):
                    if self._stop.is_set():
                        break
                    target = t_start + ev["t"] / speed
                    pos = (ev["x"], ev["y"]) if "x" in ev else None
                    self._approach(target, pos)
                    if self._stop.is_set():
                        break
                    self._play_one(ev, held_keys, held_btns)
                    if self.progress is not None and i % 5 == 0:
                        self.progress(loop, (i + 1) / total)
                if self._stop.is_set():
                    break
                if self.progress is not None:
                    self.progress(loop, 1.0)
                if repeat and loop >= repeat:
                    break
                if gap > 0:
                    end = time.perf_counter() + gap
                    while time.perf_counter() < end and not self._stop.is_set():
                        time.sleep(0.01)
        except Exception as exc:
            self.log("재생 오류: %s" % exc)
        finally:
            self._release_all(held_keys, held_btns)
            self.running = False
            self.log("재생 종료 (%d회 실행)" % loop)
            self.done()

    def _play_one(self, ev, held_keys, held_btns):
        kind = ev["e"]
        if kind == "move":
            self.sender.move(ev["x"], ev["y"])
            self._cursor = (ev["x"], ev["y"])
        elif kind == "click":
            self.sender.click(ev["b"], ev["p"], ev["x"], ev["y"])
            self._cursor = (ev["x"], ev["y"])
            if ev["p"]:
                held_btns.add(ev["b"])
            else:
                held_btns.discard(ev["b"])
        elif kind == "scroll":
            self.sender.move(ev["x"], ev["y"])
            self._cursor = (ev["x"], ev["y"])
            self.sender.scroll(ev["dx"], ev["dy"])
        elif kind == "key":
            key = spec_to_key(ev["k"])
            if key is None:
                return
            self.sender.key(key, ev["a"] == "d")
            if ev["a"] == "d":
                held_keys.add(key)
            else:
                held_keys.discard(key)

    def _release_all(self, held_keys, held_btns):
        """중단 시 눌린 채로 남은 키/버튼을 모두 떼어준다."""
        for key in list(held_keys):
            try:
                self.sender.key(key, False)
            except Exception:
                pass
        for name in list(held_btns):
            try:
                self.sender.click(name, False)
            except Exception:
                pass
        held_keys.clear()
        held_btns.clear()


# ---------------------------------------------------------------- 키 시퀀스
class SequenceWorker:
    """여러 단계를 순서대로 실행하고 한 바퀴 끝나면 처음으로 돌아간다.

    steps: [(do, gap_sec), ...]  do() 는 키를 누르고 떼는 동작, gap 은 다음 단계까지 대기.
    """

    def __init__(self, log, done, tick=None):
        self.log = log
        self.done = done
        self.tick = tick                  # (총 입력 수, 바퀴 수) 콜백
        self._stop = threading.Event()
        self.running = False
        self.count = 0                    # 지금까지 보낸 입력 수
        self.cycles = 0                   # 완주한 바퀴 수

    def start(self, steps, loops, label):
        if self.running or not steps:
            return False
        self._stop.clear()
        self.running = True
        self.count = 0
        self.cycles = 0
        threading.Thread(target=self._run, args=(list(steps), loops, label),
                         daemon=True).start()
        return True

    def stop(self):
        self._stop.set()

    def _wait(self, seconds):
        end = time.perf_counter() + seconds
        while time.perf_counter() < end:
            if self._stop.is_set():
                return False
            time.sleep(min(0.005, max(0.001, seconds)))
        return True

    def _run(self, steps, loops, label):
        try:
            while not self._stop.is_set():
                for do, gap in steps:
                    if self._stop.is_set():
                        break
                    do()
                    self.count += 1
                    if self.tick is not None:
                        self.tick(self.count, self.cycles)
                    if gap > 0 and not self._wait(gap):
                        break
                else:
                    self.cycles += 1
                    if loops and self.cycles >= loops:
                        break
                    continue
                break
        except Exception as exc:
            self.log("%s 오류: %s" % (label, exc))
        finally:
            self.running = False
            self.log("%s 정지 (%d바퀴 / 입력 %d회)" % (label, self.cycles, self.count))
            self.done()


# ---------------------------------------------------------------- 반복 작업
class RepeatWorker:
    """자동 클릭 / 키 연타 공용 반복 실행기."""

    def __init__(self, log, done, tick=None):
        self.log = log
        self.done = done
        self.tick = tick                  # 실행 횟수 콜백
        self._stop = threading.Event()
        self.running = False
        self.count = 0

    def start(self, action, interval_ms, count, label):
        if self.running:
            return False
        self._stop.clear()
        self.running = True
        self.count = 0
        threading.Thread(
            target=self._run, args=(action, max(1, interval_ms) / 1000.0, count, label),
            daemon=True,
        ).start()
        return True

    def stop(self):
        self._stop.set()

    def _run(self, action, interval, count, label):
        try:
            while not self._stop.is_set():
                action()
                self.count += 1
                if self.tick is not None:
                    self.tick(self.count)
                if count and self.count >= count:
                    break
                end = time.perf_counter() + interval
                while time.perf_counter() < end and not self._stop.is_set():
                    time.sleep(min(0.005, interval))
        except Exception as exc:
            self.log("%s 오류: %s" % (label, exc))
        finally:
            self.running = False
            self.log("%s 정지 (%d회 실행)" % (label, self.count))
            self.done()
