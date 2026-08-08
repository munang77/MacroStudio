# -*- coding: utf-8 -*-
"""창작 모드 - 블록을 순서대로 늘어놓아 만드는 나만의 매크로.

코드를 쓰지 않고 '클릭 / 키 / 대기 / 색 기다리기 / 색이면' 같은 블록을 조립한다.
낚시 매크로처럼 "찌 색이 변할 때까지 기다렸다가 당기기" 같은 걸 만들 수 있다.

블록 형식(JSON):
  {"type": "click",      "x":, "y":, "button": "left", "count": 1}
  {"type": "key",        "key": "space", "hold": 30}
  {"type": "wait",       "ms": 500, "rand": 0}
  {"type": "move",       "x":, "y":}
  {"type": "scroll",     "dy": -3}
  {"type": "wait_color", "x":, "y":, "color": [r,g,b], "tol": 25,
                         "mode": "appear"|"vanish", "timeout": 10000,
                         "on_timeout": "stop"|"continue"}
  {"type": "if_color",   "x":, "y":, "color": [r,g,b], "tol": 25,
                         "then": "continue"|"skip"|"restart"|"stop", "skip": 1,
                         "else": "continue"|"skip"|"restart"|"stop", "else_skip": 1}
  {"type": "macro",      "name": "저장한 매크로 이름"}
"""

import random
import threading
import time

import winput

POLL = 0.05                      # 색을 살피는 간격(초)

TYPES = ["click", "key", "wait", "move", "scroll", "wait_color", "if_color", "macro"]

TYPE_LABEL = {
    "click": "클릭",
    "key": "키 입력",
    "wait": "대기",
    "move": "마우스 이동",
    "scroll": "휠",
    "wait_color": "색 기다리기",
    "if_color": "색이면",
    "macro": "기록 매크로 재생",
}

BUTTON_LABEL = {"left": "왼쪽", "right": "오른쪽", "middle": "휠", "x1": "옆1", "x2": "옆2"}
FLOW_LABEL = {"continue": "계속", "skip": "건너뛰기", "restart": "처음으로", "stop": "정지"}


def default_step(kind, x=0, y=0, color=(255, 255, 255)):
    """블록을 새로 만들 때의 기본값."""
    color = list(color)
    return {
        "click": {"type": "click", "x": x, "y": y, "button": "left", "count": 1},
        "key": {"type": "key", "key": "space", "hold": 30},
        "wait": {"type": "wait", "ms": 500, "rand": 0},
        "move": {"type": "move", "x": x, "y": y},
        "scroll": {"type": "scroll", "dy": -3},
        "wait_color": {"type": "wait_color", "x": x, "y": y, "color": color, "tol": 25,
                       "mode": "appear", "timeout": 10000, "on_timeout": "stop"},
        "if_color": {"type": "if_color", "x": x, "y": y, "color": color, "tol": 25,
                     "then": "continue", "skip": 1, "else": "restart", "else_skip": 1},
        "macro": {"type": "macro", "name": ""},
    }[kind]


def hexcolor(rgb):
    return "#%02x%02x%02x" % tuple(int(c) for c in rgb)


def describe(step):
    """목록에 보여 줄 한 줄 설명."""
    t = step.get("type")
    if t == "click":
        n = step.get("count", 1)
        return "클릭  (%d, %d)  %s%s" % (step["x"], step["y"],
                                         BUTTON_LABEL.get(step.get("button"), "왼쪽"),
                                         "  %d번" % n if n > 1 else "")
    if t == "key":
        return "키 입력  [%s]  %dms 누름" % (step.get("key"), step.get("hold", 0))
    if t == "wait":
        r = step.get("rand", 0)
        return "대기  %dms%s" % (step.get("ms", 0), " (+최대 %dms 랜덤)" % r if r else "")
    if t == "move":
        return "마우스 이동  (%d, %d)" % (step["x"], step["y"])
    if t == "scroll":
        dy = step.get("dy", 0)
        return "휠  %s %d칸" % ("위로" if dy > 0 else "아래로", abs(dy))
    if t == "wait_color":
        mode = "될 때까지" if step.get("mode", "appear") == "appear" else "아닐 때까지"
        return "색 기다리기  (%d, %d)  %s %s  최대 %.1f초" % (
            step["x"], step["y"], hexcolor(step["color"]), mode,
            step.get("timeout", 0) / 1000.0)
    if t == "if_color":
        then = FLOW_LABEL.get(step.get("then"), "계속")
        other = FLOW_LABEL.get(step.get("else"), "계속")
        return "색이면  (%d, %d) %s → %s / 아니면 → %s" % (
            step["x"], step["y"], hexcolor(step["color"]), then, other)
    if t == "macro":
        return "기록 매크로 재생  [%s]" % (step.get("name") or "선택 안 됨")
    return str(step)


def color_matches(step):
    """그 지점 색이 지정한 색과 (허용 오차 안에서) 같은지."""
    got = winput.pixel_color(step["x"], step["y"])
    if got is None:
        return False
    want = step.get("color", [0, 0, 0])
    tol = step.get("tol", 25)
    return all(abs(int(got[i]) - int(want[i])) <= tol for i in range(3))


class Runner:
    """블록을 순서대로 실행한다. 흐름 블록(색이면)은 다음에 갈 위치를 바꾼다."""

    def __init__(self, sender, log, done, on_step=None, play_macro=None):
        self.sender = sender
        self.log = log
        self.done = done
        self.on_step = on_step            # (인덱스, 바퀴수) 콜백
        self.play_macro = play_macro      # 이름 -> 기록 매크로 재생 (없으면 건너뜀)
        self._stop = threading.Event()
        self.running = False
        self.cycles = 0

    def start(self, steps, loops):
        if self.running or not steps:
            return False
        self._stop.clear()
        self.running = True
        self.cycles = 0
        threading.Thread(target=self._run, args=(list(steps), loops), daemon=True).start()
        return True

    def stop(self):
        self._stop.set()

    def _sleep(self, seconds):
        end = time.perf_counter() + seconds
        while time.perf_counter() < end:
            if self._stop.is_set():
                return False
            time.sleep(min(0.01, max(0.001, seconds)))
        return not self._stop.is_set()

    MIN_STEP = 0.005                 # 블록 한 칸의 최소 시간 (쉼 없는 조합이 CPU 를 태우지 않게)

    def _run(self, steps, loops):
        i = 0
        try:
            while not self._stop.is_set():
                if self.on_step:
                    self.on_step(i, self.cycles)
                began = time.perf_counter()
                nxt = self._exec(steps[i], i)
                # 대기가 없는 블록(색이면 → 처음으로 같은 조합)만 돌면 쉬지 않고 도니 바닥을 깐다
                spent = time.perf_counter() - began
                if nxt is not None and spent < self.MIN_STEP:
                    self._sleep(self.MIN_STEP - spent)
                if nxt is None:                    # 정지 요청
                    break
                i = nxt
                if i >= len(steps):
                    self.cycles += 1
                    if loops and self.cycles >= loops:
                        break
                    i = 0
        except Exception as exc:
            self.log("창작 실행 오류: %s" % exc, "err")
        finally:
            self.running = False
            self.log("창작 정지 (%d바퀴)" % self.cycles)
            self.done()

    def _flow(self, action, index, skip):
        if action == "stop":
            return None
        if action == "restart":
            return 0
        if action == "skip":
            return index + 1 + max(0, int(skip))
        return index + 1

    def _exec(self, step, index):
        t = step.get("type")

        if t == "click":
            name = step.get("button", "left")
            for _ in range(max(1, int(step.get("count", 1)))):
                if self._stop.is_set():
                    return None
                self.sender.click(name, True, step["x"], step["y"])
                time.sleep(0.02)
                self.sender.click(name, False, step["x"], step["y"])
                time.sleep(0.03)

        elif t == "key":
            from core import MOD_KEYS, parse_combo
            mods, key = parse_combo(step.get("key", ""))
            if key is None:
                self.log("키를 알 수 없습니다: %s" % step.get("key"), "err")
                return None
            mod_keys = [MOD_KEYS[m] for m in mods]
            for mk in mod_keys:
                self.sender.key(mk, True)
            self.sender.key(key, True)
            hold = max(0, int(step.get("hold", 0))) / 1000.0
            if hold and not self._sleep(hold):
                self.sender.key(key, False)
                for mk in reversed(mod_keys):
                    self.sender.key(mk, False)
                return None
            self.sender.key(key, False)
            for mk in reversed(mod_keys):
                self.sender.key(mk, False)

        elif t == "wait":
            ms = max(0, int(step.get("ms", 0)))
            rand = max(0, int(step.get("rand", 0)))
            if not self._sleep((ms + random.randint(0, rand)) / 1000.0):
                return None

        elif t == "move":
            self.sender.move(step["x"], step["y"])

        elif t == "scroll":
            self.sender.scroll(0, int(step.get("dy", 0)))

        elif t == "wait_color":
            want = step.get("mode", "appear") == "appear"
            end = time.perf_counter() + max(0, int(step.get("timeout", 0))) / 1000.0
            while True:
                if self._stop.is_set():
                    return None
                if color_matches(step) == want:
                    break
                if time.perf_counter() >= end:
                    if step.get("on_timeout", "stop") == "stop":
                        self.log("색을 기다리다 시간이 지났습니다 (%d, %d)"
                                 % (step["x"], step["y"]), "warn")
                        return None
                    break
                time.sleep(POLL)

        elif t == "if_color":
            if color_matches(step):
                return self._flow(step.get("then", "continue"), index, step.get("skip", 1))
            return self._flow(step.get("else", "continue"), index, step.get("else_skip", 1))

        elif t == "macro":
            if self.play_macro:
                if not self.play_macro(step.get("name", "")):
                    return None
            else:
                self.log("기록 매크로를 재생할 수 없습니다.", "warn")

        return index + 1
