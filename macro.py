# -*- coding: utf-8 -*-
"""
MacroStudio - 윈도우용 매크로 프로그램

  기록/재생 : 마우스 이동/클릭/휠 + 키보드 입력을 시간까지 그대로 기록하고 반복 재생
  자동 클릭 : 지정한 간격으로 마우스 자동 클릭
  키 연타   : 지정한 키(조합 포함)를 지정 간격으로 자동 입력
  전역 단축키로 어느 창에서든 시작/정지

필요 패키지: pynput, pillow
"""

import ctypes
import json
import os
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox

from pynput import keyboard, mouse

import builder
import ui_kit as ui
import updater
from creator_ui import CreatorPage
from core import (BUTTONS, MOD_KEYS, Player, Recorder, RepeatWorker, Sender,
                  SequenceWorker, canon_name, current_mods, hotkey_label, hotkey_token,
                  is_pressed, make_spec, mouse_token, parse_combo, spec_main, spec_mods)
from ui_kit import (ACCENT, BG, CARD, DANGER, FIELD, LINE, MONO, OK, SIDE, TXT,
                    TXT_DIM, TXT_MUTE, UI, WARN, Bar, Btn, Card, KeyField, NavItem,
                    Segmented, Slider, StatusPill, Stepper, TextField, Toggle)

APP_NAME = "MacroStudio"
APP_VER = "2.3"
FROZEN = getattr(sys, "frozen", False)         # exe 로 묶인 상태인지

if FROZEN:
    # 설치 폴더는 쓰기 권한이 없을 수 있으므로 사용자 폴더에 저장한다
    APP_DIR = os.path.dirname(sys.executable)
    DATA_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or APP_DIR, APP_NAME)
else:
    APP_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_DIR = APP_DIR

MACRO_DIR = os.path.join(DATA_DIR, "macros")
CREATION_DIR = os.path.join(DATA_DIR, "creations")
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
WINDOW_TITLE = "%s - 매크로 프로그램" % APP_NAME

HOTKEY_ACTIONS = [("record", "기록 시작 / 중지"), ("play", "재생 시작 / 중지"),
                  ("click", "자동 클릭 켜기 / 끄기"), ("keyrep", "키 연타 켜기 / 끄기"),
                  ("build", "창작 시작 / 정지"), ("stop", "전체 정지")]
DEFAULT_HOTKEYS = {"record": "F6", "play": "F7", "click": "F8",
                   "keyrep": "F9", "build": "F10", "stop": "ESC"}
CAPTURE_TIMEOUT = 6.0        # 단축키 입력 대기 시간(초)
MAX_KEY_ROWS = 6             # 키 연타에 넣을 수 있는 키 개수

PAGES = [
    ("record", "기록 / 재생", "record", "마우스와 키보드 동작을 그대로 담아 반복합니다"),
    ("click", "자동 클릭", "mouse", "원하는 간격으로 마우스를 자동으로 클릭합니다"),
    ("key", "키 연타", "keyboard", "키 하나 또는 여러 키를 순서대로 반복 입력합니다"),
    ("build", "창작", "blocks", "블록을 조립해 나만의 매크로를 만듭니다"),
    ("set", "설정", "gear", "전역 단축키를 바꾸고 사용법을 확인합니다"),
]


_mutex = None                                  # 중복 실행 방지용 (프로세스 동안 살아 있어야 한다)


def claim_single_instance():
    """이미 떠 있으면 그 창을 앞으로 올리고 False. 두 개가 돌면 단축키가 두 번씩 먹는다."""
    global _mutex
    try:
        k32 = ctypes.windll.kernel32
        _mutex = k32.CreateMutexW(None, False, "Local\\MacroStudio_single_instance")
        if k32.GetLastError() != 183:          # ERROR_ALREADY_EXISTS
            return True
    except Exception:
        return True
    try:
        u32 = ctypes.windll.user32
        hwnd = u32.FindWindowW(None, WINDOW_TITLE)
        if hwnd:
            u32.ShowWindow(hwnd, 9)            # SW_RESTORE
            u32.SetForegroundWindow(hwnd)
    except Exception:
        pass
    return False


def resource(name):
    """exe 안에 같이 묶인 파일 경로 (개발 중에는 소스 폴더)."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def is_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_as_admin():
    """같은 프로그램을 관리자 권한으로 다시 띄운다. 성공하면 True."""
    if FROZEN:
        exe, params = sys.executable, None
    else:
        exe = sys.executable
        if exe.lower().endswith("python.exe"):
            pyw = exe[:-len("python.exe")] + "pythonw.exe"
            if os.path.exists(pyw):
                exe = pyw
        params = '"%s"' % os.path.join(APP_DIR, "macro.py")
    try:
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, APP_DIR, 1)
        return rc > 32
    except Exception:
        return False


class MacroApp:
    def __init__(self, root):
        self.root = root
        self.msgq = queue.Queue()
        self.cfg = self._load_config()

        self.events = []
        self.macro_name = "없음"
        self.sender = Sender()           # 실제 마우스/키보드와 같은 경로로 입력을 보낸다
        self.mouse_ctl = mouse.Controller()      # 좌표 읽기용
        self._last_tick = 0.0

        self.hotkeys = dict(DEFAULT_HOTKEYS)      # 설정이 일부만 있어도 나머지는 기본값
        saved = self.cfg.get("hotkeys")
        if isinstance(saved, dict):
            self.hotkeys.update({k: v for k, v in saved.items()
                                 if k in DEFAULT_HOTKEYS and isinstance(v, str)})

        self._own_hwnd = None            # 매크로 창 핸들 (그 창을 누른 클릭만 기록 제외)
        self._update_busy = False        # 업데이트 확인/설치 중인지
        self._update_info = None         # 찾아낸 새 버전 정보
        self._lead_active = False        # 재생 시작 전 대기 중인지
        self.recorder = Recorder(self._blocked_names(), skip_hwnd=lambda: self._own_hwnd)
        self.player = Player(self.log, lambda: self.msgq.put(("play_done", None)),
                             lambda loop, r: self.msgq.put(("prog", (loop, r))))
        self.clicker = RepeatWorker(self.log, lambda: self.msgq.put(("click_done", None)),
                                    lambda n: self._tick("click", n))
        self.repeater = SequenceWorker(self.log, lambda: self.msgq.put(("keyrep_done", None)),
                                       lambda n, c: self._tick("keyrep", n, c))
        self.macro_dir = MACRO_DIR
        self.icon_path = resource("icon.ico")
        self.creator_runner = builder.Runner(
            self.sender, self.log,
            lambda: self.msgq.put(("creator_done", None)),
            on_step=lambda i, c: self.msgq.put(("creator_step", (i, c))),
            play_macro=self._creator_play_macro)

        self._capture_listener = None
        self._cap_target = None          # 키 캡처 중인 행
        self._hk_mods = set()            # 현재 눌려 있는 보조키
        self._hk_capture = None          # 단축키 입력 대기 중인 동작 이름
        self._cap_listeners = []
        self._cap_id = 0                 # 캡처 회차 (묵은 타임아웃 무시용)
        self._entry_focus = False        # 앱 입력칸에 커서가 있는지
        self._keyrep_tokens = set()      # 키 연타가 지금 누르고 있는 키들
        self._click_button = None        # 자동 클릭이 지금 누르고 있는 버튼

        os.makedirs(MACRO_DIR, exist_ok=True)
        self._build_ui()
        self._start_hotkey_listener()
        self.refresh_macro_list()
        self._pump()
        self.log("%s v%s 시작. 어느 창에서든 단축키로 조작할 수 있습니다." % (APP_NAME, APP_VER), "ok")

    # ------------------------------------------------------------ 설정
    def _load_config(self):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fp:
                return json.load(fp)
        except Exception:
            return {}

    def save_config(self):
        data = {
            "hotkeys": self.hotkeys,
            "record_move": self.tg_move.get(),
            "play_repeat": self.st_repeat.get(),
            "play_speed": self.sl_speed.get(),
            "play_gap": self.st_gap.get(),
            "play_lead": self.st_lead.get(),
            "play_smooth": self.tg_smooth.get(),
            "auto_update": self.tg_autoupdate.get(),
            "update_repo": self.cfg.get("update_repo") or updater.DEFAULT_REPO,
            "click_interval": self.st_ci.get(),
            "click_count": self.st_cc.get(),
            "click_button": self.sg_btn.get(),
            "click_double": self.tg_double.get(),
            "click_fixed": self.tg_fixed.get(),
            "click_x": self.st_cx.get(),
            "click_y": self.st_cy.get(),
            "key_mode": self.sg_mode.get(),
            "key_count": self.st_kc.get(),
            "key_steps": [{"key": i["key"].get(), "interval": i["interval"].get(),
                           "hold": i["hold"].get()} for i in self.key_rows],
        }
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as fp:
                json.dump(data, fp, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def _blocked_names(self):
        """기록에서 제외할 토큰. 조합키(ctrl+q 등)는 평소 입력을 막지 않도록 제외하지 않는다."""
        out = set()
        for spec in self.hotkeys.values():
            if spec and spec != "없음" and not spec_mods(spec):
                out.add(spec_main(spec))
        return out

    # ------------------------------------------------------------ UI 뼈대
    def _build_ui(self):
        r = self.root
        r.title(WINDOW_TITLE)
        r.geometry("1080x950")
        r.minsize(1040, 900)
        r.configure(bg=BG)
        r.protocol("WM_DELETE_WINDOW", self.on_close)
        try:
            r.iconbitmap(resource("icon.ico"))
        except Exception:
            pass

        self._build_sidebar()

        main = tk.Frame(r, bg=BG)
        main.pack(side="left", fill="both", expand=True)

        head = tk.Frame(main, bg=BG)
        head.pack(fill="x", padx=26, pady=(20, 0))
        titles = tk.Frame(head, bg=BG)
        titles.pack(side="left")
        self.lbl_title = tk.Label(titles, text="", bg=BG, fg=TXT, font=UI(17, "bold"))
        self.lbl_title.pack(anchor="w")
        self.lbl_sub = tk.Label(titles, text="", bg=BG, fg=TXT_MUTE, font=UI(9))
        self.lbl_sub.pack(anchor="w", pady=(2, 0))
        self.pill = StatusPill(head, bg=BG, width=136, height=32)
        self.pill.pack(side="right", pady=6)

        self._build_log(main)

        self.pagebox = tk.Frame(main, bg=BG)
        self.pagebox.pack(fill="both", expand=True, padx=26, pady=(18, 0))

        self.creator = CreatorPage(self, self.pagebox, CREATION_DIR)
        self.pages = {
            "record": self._page_record(),
            "click": self._page_click(),
            "key": self._page_key(),
            "build": self.creator.frame,
            "set": self._page_settings(),
        }
        self.current = None
        self.show_page("record")

        r.bind_all("<FocusIn>", self._on_focus, add="+")
        r.bind_all("<FocusOut>", lambda e: setattr(self, "_entry_focus", False), add="+")

    def _on_focus(self, event):
        self._entry_focus = isinstance(event.widget, tk.Entry)

    def _build_sidebar(self):
        bar = tk.Frame(self.root, bg=SIDE, width=216)
        bar.pack(side="left", fill="y")
        bar.pack_propagate(False)

        top = tk.Frame(bar, bg=SIDE)
        top.pack(fill="x", padx=18, pady=(22, 26))
        mark = ui.logo_image(38, SIDE)
        lb = tk.Label(top, image=mark, bg=SIDE, bd=0)
        lb.image = mark
        lb.pack(side="left")
        names = tk.Frame(top, bg=SIDE)
        names.pack(side="left", padx=10)
        tk.Label(names, text=APP_NAME, bg=SIDE, fg=TXT, font=UI(12, "bold")).pack(anchor="w")
        tk.Label(names, text="v%s" % APP_VER, bg=SIDE, fg=TXT_MUTE,
                 font=MONO(8)).pack(anchor="w")

        self.nav = {}
        for key, label, icon_name, _sub in PAGES:
            item = NavItem(bar, label, icon_name, lambda k=key: self.show_page(k), bg=SIDE)
            item.pack(padx=15, pady=3)
            self.nav[key] = item

        bottom = tk.Frame(bar, bg=SIDE)
        bottom.pack(side="bottom", fill="x", padx=15, pady=18)
        self.btn_stop = Btn(bottom, "전체 정지", self.stop_all, width=186, height=42,
                            variant="danger", bg=SIDE, hint=hotkey_label(self.hotkeys["stop"], short=True))
        self.btn_stop.pack()
        tk.Label(bottom, text="언제든 눌러 모든 동작을 멈춥니다", bg=SIDE, fg=TXT_MUTE,
                 font=UI(8)).pack(pady=(9, 0))

    def _build_log(self, parent):
        wrap = tk.Frame(parent, bg=BG)
        wrap.pack(side="bottom", fill="x", padx=26, pady=(14, 20))
        card = Card(wrap, bg=CARD, outer=BG, pad=14)
        card.pack(fill="x")
        head = tk.Frame(card.body, bg=CARD)
        head.pack(fill="x", pady=(0, 8))
        tk.Label(head, text="로그", bg=CARD, fg=TXT_DIM, font=UI(10, "bold")).pack(side="left")
        Btn(head, "지우기", self.clear_log, width=74, height=26, variant="ghost",
            bg=CARD, font=UI(9)).pack(side="right")

        self.txt_log = tk.Text(card.body, height=3, bg=CARD, fg=TXT_DIM, bd=0,
                               highlightthickness=0, font=MONO(9), wrap="word",
                               state="disabled", spacing1=3, cursor="arrow")
        self.txt_log.pack(fill="x")
        self.txt_log.tag_configure("t", foreground=TXT_MUTE)
        self.txt_log.tag_configure("msg", foreground=TXT_DIM)
        self.txt_log.tag_configure("ok", foreground=OK)
        self.txt_log.tag_configure("warn", foreground=WARN)
        self.txt_log.tag_configure("err", foreground=DANGER)

    # ------------------------------------------------------------ 작은 도우미
    def hotkey_hint(self, action):
        """버튼 위 작은 칩에 넣을 단축키 이름."""
        return hotkey_label(self.hotkeys.get(action, "없음"), short=True)

    def _creator_play_macro(self, name):
        """창작 블록에서 기록해 둔 매크로를 불러 재생한다 (끝날 때까지 기다림)."""
        path = os.path.join(MACRO_DIR, (name or "").strip() + ".json")
        try:
            with open(path, "r", encoding="utf-8") as fp:
                data = json.load(fp)
            events = data["events"] if isinstance(data, dict) else data
        except Exception as exc:
            self.log("매크로를 열 수 없습니다: %s (%s)" % (name, exc), "err")
            return False
        sub = Player(lambda m, kind="msg": None, lambda: None)
        sub.sender = self.sender
        sub.smooth = self.tg_smooth.get()
        sub.start(events, 1, self.sl_speed.get(), 0)
        while sub.running:
            if self.creator_runner._stop.is_set():
                sub.stop()
                break
            time.sleep(0.02)
        return True

    def _card(self, parent, title, sub=None, icon_name=None, expand=False):
        card = Card(parent, bg=CARD, outer=BG, pad=18)
        card.pack(fill="both" if expand else "x", expand=expand, pady=(0, 12))
        head = tk.Frame(card.body, bg=CARD)
        head.pack(fill="x")
        if icon_name:
            im = ui.icon(icon_name, 16, ACCENT, CARD)
            lb = tk.Label(head, image=im, bg=CARD, bd=0)
            lb.image = im
            lb.pack(side="left", padx=(0, 8))
        tk.Label(head, text=title, bg=CARD, fg=TXT, font=UI(11, "bold")).pack(side="left")
        if sub:
            tk.Label(head, text=sub, bg=CARD, fg=TXT_MUTE, font=UI(9)).pack(side="left",
                                                                            padx=(10, 0))
        body = tk.Frame(card.body, bg=CARD)
        body.pack(fill="both", expand=True, pady=(14, 0))
        return card, head, body

    def _field(self, parent, label):
        box = tk.Frame(parent, bg=CARD)
        tk.Label(box, text=label, bg=CARD, fg=TXT_MUTE,
                 font=UI(9)).pack(anchor="w", pady=(0, 7))
        return box

    # ------------------------------------------------------------ 페이지: 기록/재생
    def _page_record(self):
        page = tk.Frame(self.pagebox, bg=BG)

        # --- 기록 ---
        _c, head, body = self._card(page, "기록", "동작을 그대로 담습니다", "record")
        self.lbl_stat = tk.Label(head, text="", bg=CARD, fg=TXT_DIM, font=MONO(9))
        self.lbl_stat.pack(side="right")

        row = tk.Frame(body, bg=CARD)
        row.pack(fill="x")
        self.btn_rec = Btn(row, "기록 시작", self.toggle_record, width=190, height=44,
                           variant="primary", bg=CARD, hint=hotkey_label(self.hotkeys["record"], short=True))
        self.btn_rec.pack(side="left")
        self.tg_move = Toggle(row, "마우스 이동 경로까지 기록", self.cfg.get("record_move", True),
                              bg=CARD)
        self.tg_move.pack(side="left", padx=(20, 0), pady=8)
        tk.Label(body, text="끄면 클릭한 지점만 기록해서 파일이 가볍고 재생이 정확해집니다",
                 bg=CARD, fg=TXT_MUTE, font=UI(9)).pack(anchor="w", pady=(12, 0))

        # --- 재생 ---
        _c, _h, body = self._card(page, "재생", "기록한 동작을 반복합니다", "play")
        grid = tk.Frame(body, bg=CARD)
        grid.pack(fill="x")

        f = self._field(grid, "반복 횟수  (0 = 무한)")
        self.st_repeat = Stepper(f, self.cfg.get("play_repeat", 1), 0, 99999, 1, width=150)
        self.st_repeat.pack()
        f.pack(side="left")

        f = self._field(grid, "반복 사이 대기 (ms)")
        self.st_gap = Stepper(f, self.cfg.get("play_gap", 0), 0, 600000, 100, width=150)
        self.st_gap.pack()
        f.pack(side="left", padx=22)

        f = self._field(grid, "시작 전 대기 (초)")
        self.st_lead = Stepper(f, self.cfg.get("play_lead", 0), 0, 60, 1, width=132)
        self.st_lead.pack()
        f.pack(side="left", padx=(0, 22))

        f = self._field(grid, "재생 속도")
        self.sl_speed = Slider(f, 0.25, 4.0, self.cfg.get("play_speed", 1.0), 0.05,
                               width=216, bg=CARD)
        self.sl_speed.pack()
        f.pack(side="left")

        row = tk.Frame(body, bg=CARD)
        row.pack(fill="x", pady=(18, 0))
        self.btn_play = Btn(row, "재생 시작", self.toggle_play, width=190, height=44,
                            variant="primary", bg=CARD, hint=hotkey_label(self.hotkeys["play"], short=True))
        self.btn_play.pack(side="left")
        self.tg_smooth = Toggle(row, "부드럽게 이동", self.cfg.get("play_smooth", True),
                                bg=CARD, command=lambda v: self._sync_smooth())
        self.tg_smooth.pack(side="left", padx=(18, 0), pady=9)
        prog = tk.Frame(row, bg=CARD)
        prog.pack(side="left", padx=18, fill="x", expand=True)
        self.lbl_prog = tk.Label(prog, text="대기 중", bg=CARD, fg=TXT_MUTE, font=MONO(9))
        self.lbl_prog.pack(anchor="w", pady=(0, 6))
        self.bar = Bar(prog, width=300, height=8, bg=CARD)
        self.bar.pack(anchor="w")
        self._sync_smooth()

        # --- 저장된 매크로 ---
        _c, _h, body = self._card(page, "저장된 매크로", None, "folder", expand=True)
        wrap = tk.Frame(body, bg=CARD)
        wrap.pack(fill="both", expand=True)

        panel = Card(wrap, bg=FIELD, outer=CARD, radius=10, pad=8)
        panel.pack(side="left", fill="both", expand=True)
        self.lst = tk.Listbox(panel.body, bg=FIELD, fg=TXT, bd=0, highlightthickness=0,
                              selectbackground=ACCENT, selectforeground="#ffffff",
                              font=UI(10), activestyle="none")
        self.lst.pack(fill="both", expand=True)
        self.lst.bind("<Double-Button-1>", lambda _e: self.load_macro())
        self.lbl_empty = tk.Label(panel.body, bg=FIELD, fg=TXT_MUTE, font=UI(9),
                                  text="저장된 매크로가 없습니다\n기록한 뒤 저장 버튼을 누르세요",
                                  justify="center")

        side = tk.Frame(wrap, bg=CARD)
        side.pack(side="left", padx=(14, 0), anchor="n")
        specs = (("저장", self.save_macro, "soft"), ("불러오기", self.load_macro, "soft"),
                 ("삭제", self.delete_macro, "ghost"), ("폴더 열기", self.open_folder, "ghost"))
        for i, (text, cmd, variant) in enumerate(specs):
            Btn(side, text, cmd, width=116, height=36, variant=variant, bg=CARD,
                font=UI(9, "bold")).grid(row=i // 2, column=i % 2,
                                         padx=(0, 8) if i % 2 == 0 else 0, pady=(0, 8))
        return page

    # ------------------------------------------------------------ 페이지: 자동 클릭
    def _page_click(self):
        page = tk.Frame(self.pagebox, bg=BG)
        _c, _h, body = self._card(page, "클릭 설정", None, "mouse")

        row = tk.Frame(body, bg=CARD)
        row.pack(fill="x")
        f = self._field(row, "클릭 간격 (ms)")
        self.st_ci = Stepper(f, self.cfg.get("click_interval", 100), 1, 600000, 10, width=150)
        self.st_ci.pack()
        f.pack(side="left")

        f = self._field(row, "반복 횟수  (0 = 무한)")
        self.st_cc = Stepper(f, self.cfg.get("click_count", 0), 0, 9999999, 1, width=150)
        self.st_cc.pack()
        f.pack(side="left", padx=22)

        f = self._field(row, "마우스 버튼")
        self.sg_btn = Segmented(f, [("왼쪽", "left"), ("오른쪽", "right"), ("가운데", "middle")],
                                self.cfg.get("click_button", "left"), bg=CARD)
        self.sg_btn.pack()
        f.pack(side="left")

        f = self._field(row, "클릭 방식")
        self.tg_double = Toggle(f, "더블 클릭", self.cfg.get("click_double", False), bg=CARD)
        self.tg_double.pack(anchor="w", pady=5)
        f.pack(side="left", padx=22)

        line = tk.Frame(body, bg=LINE, height=1)
        line.pack(fill="x", pady=18)

        row2 = tk.Frame(body, bg=CARD)
        row2.pack(fill="x")
        self.tg_fixed = Toggle(row2, "고정 좌표에서 클릭", self.cfg.get("click_fixed", False),
                               bg=CARD, command=lambda v: self._sync_pos())
        self.tg_fixed.pack(side="left", pady=18)

        f = self._field(row2, "X")
        self.st_cx = Stepper(f, self.cfg.get("click_x", 0), 0, 20000, 1, width=118)
        self.st_cx.pack()
        f.pack(side="left", padx=(22, 10))

        f = self._field(row2, "Y")
        self.st_cy = Stepper(f, self.cfg.get("click_y", 0), 0, 20000, 1, width=118)
        self.st_cy.pack()
        f.pack(side="left", padx=(0, 16))

        self.btn_pick = Btn(row2, "3초 뒤 좌표 캡처", self.pick_pos, width=160, height=36,
                            variant="soft", bg=CARD, font=UI(9, "bold"))
        self.btn_pick.pack(side="left", pady=(20, 0))
        self._sync_pos()

        _c, _h, body = self._card(page, "실행", None, "play")
        row = tk.Frame(body, bg=CARD)
        row.pack(fill="x")
        self.btn_click = Btn(row, "자동 클릭 시작", self.toggle_click, width=200, height=44,
                             variant="primary", bg=CARD, hint=hotkey_label(self.hotkeys["click"], short=True))
        self.btn_click.pack(side="left")
        self.lbl_click = tk.Label(row, text="정지 상태", bg=CARD, fg=TXT_MUTE, font=MONO(10))
        self.lbl_click.pack(side="left", padx=18)

        self._tip(page, "간격이 10ms 보다 짧으면 대상 프로그램이 클릭을 놓칠 수 있습니다. "
                        "고정 좌표를 쓰면 클릭하는 동안 마우스를 자유롭게 쓸 수 없으니 주의하세요.")
        tk.Frame(page, bg=BG).pack(fill="both", expand=True)
        return page

    # ------------------------------------------------------------ 페이지: 키 연타
    def _page_key(self):
        page = tk.Frame(self.pagebox, bg=BG)
        _c, _h, body = self._card(page, "반복할 키", "위에서부터 순서대로 돌아갑니다", "keyboard")

        top = tk.Frame(body, bg=CARD)
        top.pack(fill="x", pady=(0, 14))
        f = self._field(top, "실행 방식")
        self.sg_mode = Segmented(f, [("단축키로 켜고 끄기", "toggle"), ("누르고 있는 동안만", "hold")],
                                 self.cfg.get("key_mode", "toggle"), bg=CARD,
                                 command=lambda v: self._on_mode())
        self.sg_mode.pack()
        f.pack(side="left")

        f = self._field(top, "반복 바퀴 수  (0 = 무한)")
        self.st_kc = Stepper(f, self.cfg.get("key_count", 0), 0, 9999999, 1, width=150)
        self.st_kc.pack()
        f.pack(side="left", padx=22)

        self.lbl_mode = tk.Label(top, text="", bg=CARD, fg=TXT_MUTE, font=UI(9),
                                 justify="left", anchor="w", wraplength=260)
        self.lbl_mode.pack(side="left", fill="x", expand=True, pady=(20, 0))
        # 남는 폭에 맞춰 줄바꿈 (고정값이면 카드 밖으로 삐져나간다)
        self.lbl_mode.bind("<Configure>", self._fit_mode_label)

        head = tk.Frame(body, bg=CARD)
        head.pack(fill="x")
        for text, width in (("키", 210), ("다음 키까지 (ms)", 158), ("누르고 있기 (ms)", 158)):
            box = tk.Frame(head, bg=CARD, width=width, height=18)
            box.pack(side="left")
            box.pack_propagate(False)
            tk.Label(box, text=text, bg=CARD, fg=TXT_MUTE, font=UI(9),
                     anchor="w").pack(fill="both", expand=True)

        self.rows_box = tk.Frame(body, bg=CARD)
        self.rows_box.pack(fill="x", pady=(6, 0))
        self.key_rows = []

        foot = tk.Frame(body, bg=CARD)
        foot.pack(fill="x", pady=(10, 0))
        self.btn_addkey = Btn(foot, "+ 키 추가", self.add_key_row, width=110, height=32,
                              variant="soft", bg=CARD, font=UI(9, "bold"))
        self.btn_addkey.pack(side="left")
        tk.Label(foot, text="예:  a  ·  space  ·  enter  ·  f5  ·  ctrl+c        최대 %d개"
                            % MAX_KEY_ROWS,
                 bg=CARD, fg=TXT_MUTE, font=MONO(9)).pack(side="left", padx=14)

        _c, _h, body = self._card(page, "실행", None, "play")
        row = tk.Frame(body, bg=CARD)
        row.pack(fill="x")
        self.btn_keyrep = Btn(row, "키 연타 시작", self.toggle_keyrep, width=200, height=44,
                              variant="primary", bg=CARD, hint=hotkey_label(self.hotkeys["keyrep"], short=True))
        self.btn_keyrep.pack(side="left")
        self.lbl_keyrep = tk.Label(row, text="정지 상태", bg=CARD, fg=TXT_MUTE, font=MONO(10))
        self.lbl_keyrep.pack(side="left", padx=18)

        self._tip(page, "조합키는 ctrl+c 처럼 + 로 이어 씁니다. 눌러도 반응이 없는 프로그램이면 "
                        "누르고 있는 시간을 30~50ms 로 늘려 보세요.")
        tk.Frame(page, bg=BG).pack(fill="both", expand=True)

        for step in self._saved_steps():
            self.add_key_row(step.get("key", "space"), step.get("interval", 100),
                             step.get("hold", 20))
        self._on_mode()
        return page

    def _saved_steps(self):
        """설정에서 키 목록을 읽는다. 예전 단일 키 설정도 그대로 살린다."""
        steps = self.cfg.get("key_steps")
        if isinstance(steps, list) and steps:
            return steps[:MAX_KEY_ROWS]
        return [{"key": self.cfg.get("key_combo", "space"),
                 "interval": self.cfg.get("key_interval", 100),
                 "hold": self.cfg.get("key_hold", 20)}]

    def add_key_row(self, key="space", interval=100, hold=20):
        if len(self.key_rows) >= MAX_KEY_ROWS:
            self.log("키는 최대 %d개까지 넣을 수 있습니다." % MAX_KEY_ROWS, "warn")
            return
        row = tk.Frame(self.rows_box, bg=CARD)
        row.pack(fill="x", pady=3)

        field = TextField(row, key, width=132, height=34, bg=CARD)
        field.pack(side="left")
        item = {"frame": row, "key": field}

        btn_cap = Btn(row, "캡처", lambda i=item: self.capture_key(i),
                      width=58, height=34, variant="ghost", bg=CARD, font=UI(9, "bold"))
        btn_cap.pack(side="left", padx=(8, 12))
        item["cap"] = btn_cap

        item["interval"] = Stepper(row, interval, 1, 600000, 10, width=148, height=34)
        item["interval"].pack(side="left", padx=(0, 10))
        item["hold"] = Stepper(row, hold, 0, 10000, 10, width=148, height=34)
        item["hold"].pack(side="left", padx=(0, 12))

        Btn(row, "×", lambda i=item: self.remove_key_row(i), width=34, height=34,
            variant="ghost", bg=CARD, font=UI(11, "bold")).pack(side="left")

        self.key_rows.append(item)
        self.btn_addkey.set_enabled(len(self.key_rows) < MAX_KEY_ROWS)

    def remove_key_row(self, item):
        if len(self.key_rows) <= 1:
            self.log("키는 최소 하나는 있어야 합니다.", "warn")
            return
        item["frame"].destroy()
        self.key_rows.remove(item)
        self.btn_addkey.set_enabled(True)

    def _fit_mode_label(self, event):
        want = max(160, event.width - 10)
        if abs(int(self.lbl_mode.cget("wraplength")) - want) > 2:
            self.lbl_mode.configure(wraplength=want)

    def _on_mode(self):
        hold = self.sg_mode.get() == "hold"
        trigger = self.hotkeys.get("keyrep", "없음")
        if hold:
            text = ("%s 를 누르고 있는 동안만 반복합니다" % hotkey_label(trigger)
                    if trigger != "없음" else
                    "설정에서 '키 연타' 단축키를 먼저 등록하세요")
        else:
            text = "단축키를 한 번 누르면 시작, 다시 누르면 멈춥니다"
        self.lbl_mode.configure(text=text)
        if hasattr(self, "btn_keyrep"):
            self.btn_keyrep.config_text("홀드 모드" if hold else "키 연타 시작")
            self.btn_keyrep.set_enabled(not hold)
        if hold and self.repeater.running:
            self.repeater.stop()

    # ------------------------------------------------------------ 페이지: 설정
    def _page_settings(self):
        page = tk.Frame(self.pagebox, bg=BG)

        admin = is_admin()
        strip = Card(page, bg=CARD, outer=BG, pad=12)
        strip.pack(fill="x", pady=(0, 12))
        tk.Label(strip.body, text="실행 권한", bg=CARD, fg=TXT,
                 font=UI(10, "bold")).pack(side="left", padx=(2, 10))
        tk.Label(strip.body, text="관리자" if admin else "일반",
                 bg=CARD, fg=OK if admin else WARN,
                 font=UI(10, "bold")).pack(side="left", padx=(0, 14))
        # 버튼을 먼저 배치해야 창을 줄여도 버튼이 밀려나지 않는다
        if not admin:
            Btn(strip.body, "관리자 권한으로 다시 실행", self.restart_as_admin,
                width=190, height=34, variant="soft", bg=CARD,
                font=UI(9, "bold")).pack(side="right")
        msg = ("관리자 권한 프로그램(일부 게임)은 일반 권한에서 기록·재생이 안 됩니다"
               if not admin else "모든 프로그램을 다룰 수 있습니다")
        tk.Label(strip.body, text=msg, bg=CARD, fg=TXT_MUTE, anchor="w",
                 font=UI(9)).pack(side="left", fill="x", expand=True)

        self._build_update_card(page)

        _c, _h, body = self._card(page, "전역 단축키", "다른 창이 떠 있어도 동작합니다", "gear")

        grid = tk.Frame(body, bg=CARD)
        grid.pack(fill="x")
        self.hk_fields = {}
        for i, (key, label) in enumerate(HOTKEY_ACTIONS):
            box = tk.Frame(grid, bg=CARD)
            box.grid(row=i // 3, column=i % 3, sticky="w", padx=(0, 26), pady=(0, 14))
            tk.Label(box, text=label, bg=CARD, fg=TXT_MUTE,
                     font=UI(9)).pack(anchor="w", pady=(0, 7))
            field = KeyField(box, hotkey_label(self.hotkeys.get(key, "없음")), bg=CARD,
                             command=lambda k=key: self.begin_capture(k),
                             on_clear=lambda k=key: self.clear_hotkey(k))
            field.pack(anchor="w")
            self.hk_fields[key] = field

        tk.Label(body, text="칸을 누른 뒤 원하는 키나 마우스 버튼을 누르면 그대로 등록됩니다. "
                            "Ctrl·Shift·Alt 조합도 되고, 오른쪽 × 로 해제합니다. "
                            "왼쪽 클릭은 화면 조작과 겹쳐서 등록할 수 없습니다.",
                 bg=CARD, fg=TXT_MUTE, font=UI(9), justify="left",
                 wraplength=700).pack(anchor="w", pady=(2, 0))

        _c, _h, body = self._card(page, "사용법", None, "gear", expand=True)
        steps = [
            ("1", "기록", "기록 시작을 누르고 원하는 동작을 한 뒤 같은 키를 다시 눌러 멈춥니다."),
            ("2", "재생", "반복 횟수와 속도를 정하고 재생 시작을 누르면 그대로 반복합니다."),
            ("3", "저장", "자주 쓰는 동작은 저장해 두면 macros 폴더에 남아 언제든 불러올 수 있습니다."),
            ("4", "정지", "무슨 일이 있어도 전체 정지(기본 ESC)로 즉시 멈출 수 있습니다."),
        ]
        grid2 = tk.Frame(body, bg=CARD)
        grid2.pack(fill="x")
        for i, (num, title, desc) in enumerate(steps):
            cell = tk.Frame(grid2, bg=CARD)
            cell.grid(row=i // 2, column=i % 2, sticky="nw", padx=(0, 26), pady=(0, 8))
            tk.Label(cell, text=num, bg=CARD, fg=ACCENT, font=MONO(10, "bold"),
                     width=3).pack(side="left", anchor="n")
            col = tk.Frame(cell, bg=CARD)
            col.pack(side="left")
            tk.Label(col, text=title, bg=CARD, fg=TXT, font=UI(10, "bold")).pack(anchor="w")
            tk.Label(col, text=desc, bg=CARD, fg=TXT_MUTE, font=UI(9), justify="left",
                     wraplength=330).pack(anchor="w")

        note = ("재생은 기록 당시의 절대 좌표를 재현하므로 해상도나 창 위치가 바뀌면 결과도 달라집니다. "
                "관리자 권한으로 도는 프로그램을 제어하려면 이 앱도 관리자 권한으로 실행하세요. "
                "게임 등 일부 서비스는 약관으로 자동 입력을 금지합니다.")
        lbl = tk.Label(body, text=note, bg=CARD, fg=TXT_MUTE, font=UI(9), justify="left",
                       wraplength=700)
        lbl.pack(anchor="w", fill="x", pady=(8, 0))
        # 카드 폭에 맞춰 줄바꿈 폭을 따라가게 (고정값이면 넓은 쪽이 잘린다)
        body.bind("<Configure>",
                  lambda e, l=lbl: l.configure(wraplength=max(240, e.width - 8)))
        return page

    def _tip(self, page, text):
        card = Card(page, bg=CARD, outer=BG, pad=14)
        card.pack(fill="x")
        im = ui.icon("gear", 14, ui.mix(CARD, ACCENT, 0.9), CARD)
        lb = tk.Label(card.body, image=im, bg=CARD, bd=0)
        lb.image = im
        lb.pack(side="left", padx=(2, 10))
        tk.Label(card.body, text=text, bg=CARD, fg=TXT_MUTE, font=UI(9),
                 justify="left", wraplength=700).pack(side="left")

    # ------------------------------------------------------------ 자동 업데이트
    def _build_update_card(self, page):
        strip = Card(page, bg=CARD, outer=BG, pad=12)
        strip.pack(fill="x", pady=(0, 12))
        body = strip.body

        self.btn_update = Btn(body, "업데이트 확인", self.check_update, width=130, height=34,
                              variant="soft", bg=CARD, font=UI(9, "bold"))
        self.btn_update.pack(side="right")
        self.tg_autoupdate = Toggle(body, "시작할 때 확인",
                                    self.cfg.get("auto_update", True), bg=CARD)
        self.tg_autoupdate.pack(side="right", padx=(0, 14), pady=4)

        tk.Label(body, text="업데이트", bg=CARD, fg=TXT,
                 font=UI(10, "bold")).pack(side="left", padx=(2, 10))
        tk.Label(body, text="v%s" % APP_VER, bg=CARD, fg=TXT_DIM,
                 font=MONO(9, "bold")).pack(side="left", padx=(0, 14))
        self.lbl_update = tk.Label(body, text="", bg=CARD, fg=TXT_MUTE, anchor="w",
                                   font=UI(9))
        self.lbl_update.pack(side="left", fill="x", expand=True)
        self._set_update_text("최신 여부를 확인해 보세요")

        if self.tg_autoupdate.get():
            self.root.after(2500, lambda: self.check_update(quiet=True))

    def _set_update_text(self, text, color=TXT_MUTE):
        self.lbl_update.configure(text=text, fg=color)

    def check_update(self, quiet=False):
        """새 버전이 있는지 본다. quiet 면 결과가 있을 때만 알린다."""
        if self._update_busy:
            return
        self._update_busy = True
        self.btn_update.set_enabled(False)
        if not quiet:
            self._set_update_text("확인 중...")

        repo = self.cfg.get("update_repo") or updater.DEFAULT_REPO

        def worker():
            info = updater.check(repo)
            self.msgq.put(("update_check", (info, quiet)))

        threading.Thread(target=worker, daemon=True).start()

    def _on_update_check(self, info, quiet):
        self._update_busy = False
        self.btn_update.set_enabled(True)
        if info.get("error"):
            if not quiet:
                self._set_update_text("확인 실패: %s" % info["error"], WARN)
            return
        if not updater.is_newer(info["version"], APP_VER):
            # 자동 확인이었어도 결과는 남겨 둔다 (확인이 됐는지 알 수 있게)
            self._set_update_text("최신 버전입니다  (%s 확인)" % time.strftime("%H:%M"),
                                  TXT_MUTE if quiet else OK)
            return

        self._update_info = info
        self._set_update_text("새 버전 v%s 이 있습니다" % info["version"], ACCENT)
        self.log("새 버전 v%s 이 나왔습니다." % info["version"], "ok")
        if not FROZEN:
            self._set_update_text("새 버전 v%s (소스 실행 중이라 자동 설치는 안 됩니다)"
                                  % info["version"], WARN)
            return
        self.btn_update.config_text("v%s 설치" % info["version"], "primary")
        self.btn_update.command = self.install_update

    def install_update(self):
        info = self._update_info
        if not info or self._update_busy:
            return
        if not messagebox.askyesno("업데이트",
                                   "새 버전 v%s 을 받아서 설치합니다.\n"
                                   "프로그램이 잠깐 닫혔다가 다시 열립니다. 계속할까요?"
                                   % info["version"]):
            return
        self._update_busy = True
        self.btn_update.set_enabled(False)
        self.stop_all()

        def worker():
            try:
                path = updater.download(info["url"], on_progress=lambda got, total:
                                        self.msgq.put(("update_prog", (got, total))))
                self.msgq.put(("update_ready", path))
            except Exception as exc:
                self.msgq.put(("update_fail", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _on_update_ready(self, path):
        try:
            updater.apply_update(path, sys.executable, relaunch=True)
        except Exception as exc:
            self._on_update_fail(str(exc))
            return
        self.log("업데이트를 적용합니다. 잠시 뒤 다시 열립니다.", "ok")
        self.root.after(400, self.on_close)

    def _on_update_fail(self, reason):
        self._update_busy = False
        self.btn_update.set_enabled(True)
        self._set_update_text("업데이트 실패: %s" % reason, DANGER)
        self.log("업데이트 실패: %s" % reason, "err")

    # ------------------------------------------------------------ 페이지 전환
    def show_page(self, key):
        if self.current == key:
            return
        for k, page in self.pages.items():
            page.pack_forget()
            self.nav[k].set_active(k == key)
        self.pages[key].pack(fill="both", expand=True)
        self.current = key
        for k, label, _icon, sub in PAGES:
            if k == key:
                self.lbl_title.configure(text=label)
                self.lbl_sub.configure(text=sub)

    # ------------------------------------------------------------ 로그 / 상태
    def log(self, msg, kind="msg"):
        self.msgq.put(("log", (msg, kind)))

    def _write_log(self, msg, kind):
        self.txt_log.configure(state="normal")
        self.txt_log.insert("end", time.strftime("%H:%M:%S "), "t")
        self.txt_log.insert("end", msg + "\n", kind)
        lines = int(self.txt_log.index("end-1c").split(".")[0])
        if lines > 600:                       # 오래 켜 둬도 계속 쌓이지 않게
            self.txt_log.delete("1.0", "200.0")
        self.txt_log.see("end")
        self.txt_log.configure(state="disabled")

    def clear_log(self):
        self.txt_log.configure(state="normal")
        self.txt_log.delete("1.0", "end")
        self.txt_log.configure(state="disabled")

    def _tick(self, which, count, cycles=None):
        now = time.time()
        if now - self._last_tick < 0.12:
            return
        self._last_tick = now
        self.msgq.put(("tick", (which, count, cycles)))

    def _idle_state(self):
        if self.recorder.active:
            self.pill.set("기록 중", DANGER, pulse=True)
        elif self.player.running:
            self.pill.set("재생 중", OK, pulse=True)
        elif self.clicker.running:
            self.pill.set("자동 클릭 중", OK, pulse=True)
        elif self.repeater.running:
            self.pill.set("키 연타 중", OK, pulse=True)
        elif getattr(self, "creator_runner", None) and self.creator_runner.running:
            self.pill.set("창작 실행 중", OK, pulse=True)
        else:
            self.pill.set("대기 중", TXT_DIM, pulse=False)

    def _update_own_hwnd(self):
        """창 핸들을 기록해 둔다 (리스너 스레드에서 tkinter 를 직접 부르면 안 되므로)."""
        if self._own_hwnd is not None:
            return
        try:
            self._own_hwnd = ctypes.windll.user32.GetAncestor(self.root.winfo_id(), 2)
        except Exception:
            self._own_hwnd = None

    def _pump(self):
        """작업 스레드에서 온 메시지를 GUI 스레드에서 처리."""
        self._update_own_hwnd()
        try:
            while True:
                kind, payload = self.msgq.get_nowait()
                if kind == "log":
                    self._write_log(*payload)
                elif kind == "hotkey":
                    self._on_hotkey(payload)
                elif kind == "prog":
                    loop, ratio = payload
                    self.bar.set(ratio)
                    self.lbl_prog.configure(text="%d회차  %d%%" % (loop, ratio * 100))
                elif kind == "tick":
                    which, count, cycles = payload
                    lbl = self.lbl_click if which == "click" else self.lbl_keyrep
                    text = "%d회 실행" % count
                    if cycles is not None and len(self.key_rows) > 1:
                        text += "   %d바퀴" % cycles
                    lbl.configure(text=text, fg=OK)
                elif kind == "play_done":
                    self.btn_play.config_text("재생 시작", "primary", hotkey_label(self.hotkeys["play"], short=True))
                    self.bar.set(0)
                    self.lbl_prog.configure(text="대기 중")
                    self._idle_state()
                elif kind == "click_done":
                    self._click_button = None
                    self.btn_click.config_text("자동 클릭 시작", "primary", hotkey_label(self.hotkeys["click"], short=True))
                    self.lbl_click.configure(text="%d회 실행 후 정지" % self.clicker.count,
                                             fg=TXT_MUTE)
                    self._idle_state()
                elif kind == "keyrep_done":
                    self._keyrep_tokens = set()
                    self.btn_keyrep.config_text("키 연타 시작", "primary", hotkey_label(self.hotkeys["keyrep"], short=True))
                    done_text = "%d회 실행 후 정지" % self.repeater.count
                    if len(self.key_rows) > 1:
                        done_text = "%d바퀴 / " % self.repeater.cycles + done_text
                    self.lbl_keyrep.configure(text=done_text, fg=TXT_MUTE)
                    self._idle_state()
                elif kind == "picked":
                    self.st_cx.set(payload[0])
                    self.st_cy.set(payload[1])
                    self.btn_pick.config_text("3초 뒤 좌표 캡처", "soft")
                    self.btn_pick.set_enabled(True)
                    self._idle_state()
                elif kind == "update_check":
                    self._on_update_check(*payload)
                elif kind == "update_prog":
                    got, total = payload
                    if total:
                        self._set_update_text("받는 중 %d%% (%.1f/%.1f MB)"
                                              % (got * 100 // total, got / 1048576,
                                                 total / 1048576), ACCENT)
                    else:
                        self._set_update_text("받는 중 %.1f MB" % (got / 1048576), ACCENT)
                elif kind == "update_ready":
                    self._on_update_ready(payload)
                elif kind == "update_fail":
                    self._on_update_fail(payload)
                elif kind == "creator_step":
                    self.creator.on_step(*payload)
                elif kind == "creator_done":
                    self.creator.on_done()
                    self._idle_state()
                elif kind == "keyrep_hold":
                    if payload and not self.repeater.running:
                        self.toggle_keyrep()
                    elif not payload and self.repeater.running:
                        self.repeater.stop()
                elif kind == "hkcap":
                    self._set_hotkey(*payload)
                elif kind == "keycap":
                    if self._cap_target is not None:
                        self._cap_target["key"].set(payload)
                        self._cap_target["cap"].config_text("캡처", "ghost")
                        self._cap_target["cap"].set_enabled(True)
                        self._cap_target = None
                    self._idle_state()
        except queue.Empty:
            pass
        self.root.after(40, self._pump)

    # ------------------------------------------------------------ 전역 단축키
    def _start_hotkey_listener(self):
        """키보드 + 마우스 버튼 모두 전역으로 듣는다."""
        self._hk_listener = keyboard.Listener(on_press=self._hk_press,
                                              on_release=self._hk_release)
        self._hk_listener.daemon = True
        self._hk_listener.start()
        self._hk_mouse = mouse.Listener(on_click=self._hk_click)
        self._hk_mouse.daemon = True
        self._hk_mouse.start()

    def _hk_press(self, key):
        token = hotkey_token(key)
        if not token:
            return
        if token in MOD_KEYS:
            self._hk_mods.add(token)
            return
        if self._hk_capture is not None:
            return
        mods = current_mods(self._hk_mods)
        # 앱 안의 입력칸에 글자를 치는 중이면 한 글자짜리 단축키는 무시
        if self._entry_focus and len(token) == 1 and not mods:
            return
        # 키 연타가 스스로 만든 입력에 반응해 꺼지는 것을 막는다
        if self.repeater.running and token in self._keyrep_tokens:
            return
        self._fire(make_spec(mods, token))

    def _hk_release(self, key):
        token = hotkey_token(key)
        self._hk_mods.discard(token)
        self._hold_release(token)

    def _hk_click(self, x, y, button, pressed):
        if self._hk_capture is not None:
            return
        token = mouse_token(button)
        if not pressed:
            self._hold_release(token)
            return
        # 자동 클릭이 스스로 만든 클릭에 반응해 꺼지는 것을 막는다 (시작할 때 고른 버튼 기준)
        if self.clicker.running and self._click_button == button:
            return
        self._fire(make_spec(current_mods(self._hk_mods), token))

    def _hold_release(self, token):
        """홀드 모드에서 트리거를 뗐을 때 멈춘다. 우리가 만든 가짜 입력은 걸러낸다."""
        if not self._hold_mode() or not self.repeater.running:
            return
        if token != spec_main(self.hotkeys.get("keyrep", "없음")):
            return
        if is_pressed(token):          # 아직 물리적으로 눌려 있으면 우리가 만든 입력
            return
        self.msgq.put(("keyrep_hold", False))

    def _hold_mode(self):
        return hasattr(self, "sg_mode") and self.sg_mode.get() == "hold"

    def _match(self, spec):
        for action, hk in self.hotkeys.items():
            if hk and hk != "없음" and hk == spec:
                return action
        return None

    def _fire(self, spec):
        action = self._match(spec)
        if action is None and "+" in spec:
            # 게임에서 Ctrl 같은 걸 누른 채여도 보조키 없는 단축키(ESC 등)는 먹어야 한다
            action = self._match(spec_main(spec))
        if action is None:
            return
        # 재생 중에는 매크로가 되풀이하는 입력이 다른 기능을 건드리지 않게 한다
        if self.player.running and action not in ("stop", "play"):
            return
        if action == "keyrep" and self._hold_mode():
            self.msgq.put(("keyrep_hold", True))     # 누르고 있는 동안만
            return
        self.msgq.put(("hotkey", action))

    def _on_hotkey(self, action):
        {"record": self.toggle_record, "play": self.toggle_play,
         "click": self.toggle_click, "keyrep": self.toggle_keyrep,
         "build": self.creator.toggle_run,
         "stop": self.stop_all}[action]()

    # --- 단축키 등록 ------------------------------------------------
    def begin_capture(self, action):
        if self._hk_capture is not None:
            return
        self._hk_capture = action
        self._cap_id += 1
        cap_id = self._cap_id
        self._cap_mods = set()
        self._cap_from = time.time() + 0.35      # 시작 클릭이 그대로 잡히는 것 방지
        self.hk_fields[action].set_text("입력 대기...", waiting=True)
        self.pill.set("단축키 입력 대기", WARN, pulse=True)

        def on_press(key):
            token = hotkey_token(key)
            if not token:
                return None
            if token in MOD_KEYS:
                self._cap_mods.add(token)
                return None
            if time.time() < self._cap_from:
                return None
            self.msgq.put(("hkcap", (action, make_spec(current_mods(self._cap_mods), token))))
            return False

        def on_release(key):
            self._cap_mods.discard(hotkey_token(key))

        def on_click(x, y, button, pressed):
            if not pressed or time.time() < self._cap_from:
                return None
            if button.name == "left":
                self.msgq.put(("log", ("왼쪽 클릭은 단축키로 쓸 수 없습니다. 다른 키나 버튼을 누르세요.",
                                       "warn")))
                return None
            self.msgq.put(("hkcap", (action,
                                     make_spec(current_mods(self._cap_mods),
                                               mouse_token(button)))))
            return False

        kl = keyboard.Listener(on_press=on_press, on_release=on_release)
        ml = mouse.Listener(on_click=on_click)
        for lis in (kl, ml):
            lis.daemon = True
            lis.start()
        self._cap_listeners = [kl, ml]
        self.root.after(int(CAPTURE_TIMEOUT * 1000),
                        lambda: self._capture_timeout(action, cap_id))

    def _capture_timeout(self, action, cap_id=None):
        # 이미 끝난 회차의 예약이 새로 시작한 캡처를 취소하면 안 된다
        if self._hk_capture != action or (cap_id is not None and cap_id != self._cap_id):
            return
        self._end_capture()
        self.log("입력이 없어 단축키 등록을 취소했습니다.", "warn")

    def _end_capture(self):
        for lis in self._cap_listeners:
            try:
                lis.stop()
            except Exception:
                pass
        self._cap_listeners = []
        action, self._hk_capture = self._hk_capture, None
        if action:
            self.hk_fields[action].set_text(hotkey_label(self.hotkeys.get(action, "없음")),
                                            dim=self.hotkeys.get(action, "없음") == "없음")
        self._idle_state()

    def _set_hotkey(self, action, spec):
        for other, hk in self.hotkeys.items():
            if other != action and hk == spec:
                self._end_capture()
                self.log("%s 은(는) 이미 '%s' 에 쓰고 있습니다."
                         % (hotkey_label(spec), dict(HOTKEY_ACTIONS)[other]), "warn")
                return
        self.hotkeys[action] = spec
        self._end_capture()
        self._refresh_hotkey_ui()
        self.log("단축키 등록: %s -> %s" % (dict(HOTKEY_ACTIONS)[action], hotkey_label(spec)), "ok")

    def clear_hotkey(self, action):
        if self._hk_capture is not None:
            return
        self.hotkeys[action] = "없음"
        self._refresh_hotkey_ui()
        self.log("단축키 해제: %s" % dict(HOTKEY_ACTIONS)[action], "warn")

    def _refresh_hotkey_ui(self):
        self.recorder.blocked = self._blocked_names()
        for key, field in self.hk_fields.items():
            spec = self.hotkeys.get(key, "없음")
            field.set_text(hotkey_label(spec), dim=(spec == "없음"))
        self.btn_rec.config_text(hint=hotkey_label(self.hotkeys["record"], short=True))
        self.btn_play.config_text(hint=hotkey_label(self.hotkeys["play"], short=True))
        self.btn_click.config_text(hint=hotkey_label(self.hotkeys["click"], short=True))
        self.btn_keyrep.config_text(hint=hotkey_label(self.hotkeys["keyrep"], short=True))
        self.btn_stop.config_text(hint=hotkey_label(self.hotkeys["stop"], short=True))
        if hasattr(self, "creator"):
            self.creator.btn_run.config_text(hint=self.hotkey_hint("build"))
        if hasattr(self, "sg_mode"):
            self._on_mode()
        self.save_config()

    # ------------------------------------------------------------ 기록
    def toggle_record(self):
        if self.recorder.active:
            self.events = self.recorder.stop()
            self.btn_rec.config_text("기록 시작", "primary", hotkey_label(self.hotkeys["record"], short=True))
            self._update_stat()
            if self.events:
                self.log("기록 완료: %d개 이벤트 / %.1f초  (%s)"
                         % (len(self.events), self.recorder.duration(),
                            self._breakdown(self.events)), "ok")
                if not any(e["e"] == "click" for e in self.events):
                    self.log("클릭이 하나도 안 잡혔습니다. 대상이 관리자 권한 프로그램인지 "
                             "확인하세요.", "warn")
            else:
                self.log("입력이 하나도 잡히지 않았습니다. 대상 프로그램이 관리자 권한으로 "
                         "실행 중이라면 이 앱도 관리자 권한으로 실행해야 합니다. "
                         "(설정 페이지에서 다시 실행할 수 있습니다)", "err")
            self._idle_state()
            return
        if self.player.running:
            self.log("재생 중에는 기록할 수 없습니다.", "warn")
            return
        self.recorder.start(record_move=self.tg_move.get())
        self.btn_rec.config_text("기록 중지", "danger", hotkey_label(self.hotkeys["record"], short=True))
        self.pill.set("기록 중", DANGER, pulse=True)
        self.log("기록 시작. 멈추려면 %s 를 누르세요." % hotkey_label(self.hotkeys["record"]))

    @staticmethod
    def _breakdown(events):
        """이동/클릭/휠/키가 각각 몇 개인지 (뭐가 안 잡혔는지 바로 보이게)."""
        names = {"move": "이동", "click": "클릭", "scroll": "휠", "key": "키"}
        counts = {}
        for ev in events:
            counts[ev["e"]] = counts.get(ev["e"], 0) + 1
        return ", ".join("%s %d" % (names.get(k, k), counts[k])
                         for k in ("move", "click", "scroll", "key") if k in counts)

    def _update_stat(self):
        dur = self.events[-1]["t"] if self.events else 0.0
        self.lbl_stat.configure(text="이벤트 %d개   %.1f초   매크로: %s"
                                     % (len(self.events), dur, self.macro_name))

    # ------------------------------------------------------------ 재생
    def toggle_play(self):
        if self._lead_active:                        # 시작 대기 중이면 취소
            self._cancel_lead()
            return
        if self.player.running:
            self.player.stop()
            self.log("재생 중지 요청")
            return
        if self.recorder.active:
            self.log("기록 중에는 재생할 수 없습니다.", "warn")
            return
        if not self.events:
            self.log("재생할 기록이 없습니다. 먼저 기록하거나 매크로를 불러오세요.", "warn")
            return
        lead = self.st_lead.get()
        if lead > 0:
            self._lead_active = True
            self.btn_play.config_text("대기 취소", "danger", hotkey_label(self.hotkeys["play"], short=True))
            self.pill.set("시작 대기", WARN, pulse=True)
            self.log("%d초 뒤 재생을 시작합니다. 대상 창을 띄워 두세요." % lead)
            self._lead_countdown(lead)
            return
        self._start_play()

    def _lead_countdown(self, left):
        """대상 창으로 전환할 시간을 준다."""
        if not self._lead_active:
            return
        if left <= 0:
            self._lead_active = False
            self._start_play()
            return
        self.lbl_prog.configure(text="%d초 뒤 시작..." % left)
        self.root.after(1000, lambda: self._lead_countdown(left - 1))

    def _cancel_lead(self):
        self._lead_active = False
        self.btn_play.config_text("재생 시작", "primary",
                                  hotkey_label(self.hotkeys["play"], short=True))
        self.lbl_prog.configure(text="대기 중")
        self.log("재생 대기를 취소했습니다.", "warn")
        self._idle_state()

    def _sync_smooth(self):
        self.player.smooth = self.tg_smooth.get()

    def _start_play(self):
        self._sync_smooth()
        self.log("재생 시작: %d개 (%s)" % (len(self.events), self._breakdown(self.events)))
        if self.player.start(self.events, self.st_repeat.get(), self.sl_speed.get(),
                             self.st_gap.get()):
            self.btn_play.config_text("재생 중지", "danger", hotkey_label(self.hotkeys["play"], short=True))
            self.pill.set("재생 중", OK, pulse=True)

    # ------------------------------------------------------------ 자동 클릭
    def toggle_click(self):
        if self.clicker.running:
            self.clicker.stop()
            return
        btn = BUTTONS.get(self.sg_btn.get(), mouse.Button.left)
        clicks = 2 if self.tg_double.get() else 1
        fixed = self.tg_fixed.get()
        pos = (self.st_cx.get(), self.st_cy.get())

        name = self.sg_btn.get()
        px, py = (pos if fixed else (None, None))

        def action():
            for _ in range(clicks):
                self.sender.click(name, True, px, py)
                self.sender.click(name, False, px, py)

        self._click_button = btn
        if self.clicker.start(action, self.st_ci.get(), self.st_cc.get(), "자동 클릭"):
            self.btn_click.config_text("자동 클릭 중지", "danger", hotkey_label(self.hotkeys["click"], short=True))
            self.lbl_click.configure(text="0회 실행", fg=OK)
            self.pill.set("자동 클릭 중", OK, pulse=True)
            self.log("자동 클릭 시작: %dms 간격, %s 버튼%s"
                     % (self.st_ci.get(), self.sg_btn.get(),
                        ", 고정좌표 %s" % (pos,) if fixed else ""))

    def _sync_pos(self):
        on = self.tg_fixed.get()
        self.st_cx.set_enabled(on)
        self.st_cy.set_enabled(on)

    def pick_pos(self):
        self.btn_pick.config_text("3초 후 캡처...", "soft")
        self.btn_pick.set_enabled(False)
        self.pill.set("좌표 캡처 대기", WARN, pulse=True)

        def worker():
            time.sleep(3)
            x, y = self.mouse_ctl.position
            self.msgq.put(("log", ("좌표 캡처: (%d, %d)" % (x, y), "ok")))
            self.msgq.put(("picked", (int(x), int(y))))

        threading.Thread(target=worker, daemon=True).start()

    # ------------------------------------------------------------ 키 연타
    def _build_steps(self):
        """키 목록을 (실행함수, 다음까지 대기초) 단계로 바꾼다. 형식이 틀리면 None."""
        steps, tokens = [], set()
        for item in self.key_rows:
            text = item["key"].get()
            mods, key = parse_combo(text)
            if key is None:
                self.log("키 형식을 인식할 수 없습니다: %s" % text, "err")
                return None, None
            hold = item["hold"].get() / 1000.0
            gap = item["interval"].get() / 1000.0
            mod_keys = [MOD_KEYS[m] for m in mods]
            tokens.add(hotkey_token(key))

            def action(key=key, mod_keys=mod_keys, hold=hold):
                for mk in mod_keys:
                    self.sender.key(mk, True)
                self.sender.key(key, True)
                if hold:
                    time.sleep(hold)
                self.sender.key(key, False)
                for mk in reversed(mod_keys):
                    self.sender.key(mk, False)

            steps.append((action, gap))
        return steps, tokens

    def toggle_keyrep(self):
        if self.repeater.running:
            self.repeater.stop()
            return
        steps, tokens = self._build_steps()
        if steps is None:
            return
        self._keyrep_tokens = tokens
        if self.repeater.start(steps, self.st_kc.get(), "키 연타"):
            self.btn_keyrep.config_text("키 연타 중지", "danger", hotkey_label(self.hotkeys["keyrep"], short=True))
            self.lbl_keyrep.configure(text="0회 실행", fg=OK)
            self.pill.set("키 연타 중", OK, pulse=True)
            self.log("키 연타 시작: %s" % " → ".join(i["key"].get() for i in self.key_rows))

    def capture_key(self, item):
        if self._capture_listener is not None:
            return
        self._cap_target = item
        item["cap"].config_text("...", "soft")
        item["cap"].set_enabled(False)
        self.pill.set("키 캡처 대기", WARN, pulse=True)
        pressed_mods = []

        def on_press(k):
            name = canon_name(k)
            if name in MOD_KEYS:
                if name not in pressed_mods:
                    pressed_mods.append(name)
                return None
            if isinstance(k, keyboard.Key):
                main = k.name
            elif k.char:
                main = k.char.lower()
            else:
                main = None
            if main is None:
                return None
            combo = "+".join(pressed_mods + [main])
            self.msgq.put(("keycap", combo))
            self.msgq.put(("log", ("키 캡처: %s" % combo, "ok")))
            self._capture_listener = None
            return False  # 리스너 종료

        self._capture_listener = keyboard.Listener(on_press=on_press)
        self._capture_listener.daemon = True
        self._capture_listener.start()

    # ------------------------------------------------------------ 정지
    def stop_all(self):
        acted = False
        if self._lead_active:
            self._cancel_lead()
            acted = True
        if self.recorder.active:
            self.toggle_record()
            acted = True
        for worker in (self.player, self.clicker, self.repeater, self.creator_runner):
            if worker.running:
                worker.stop()
                acted = True
        if acted:
            self.log("전체 정지", "warn")
        self._idle_state()

    # ------------------------------------------------------------ 매크로 파일
    def refresh_macro_list(self):
        self.lst.delete(0, "end")
        try:
            names = sorted(f for f in os.listdir(MACRO_DIR) if f.lower().endswith(".json"))
        except Exception:
            names = []
        for n in names:
            self.lst.insert("end", "  " + n[:-5])
        if names:
            self.lbl_empty.place_forget()
        else:
            self.lbl_empty.place(relx=0.5, rely=0.5, anchor="center")

    def save_macro(self):
        if not self.events:
            messagebox.showinfo("저장", "저장할 기록이 없습니다.")
            return
        path = filedialog.asksaveasfilename(
            initialdir=MACRO_DIR, defaultextension=".json",
            filetypes=[("매크로 파일", "*.json")], title="매크로 저장")
        if not path:
            return
        data = {
            "app": APP_NAME, "version": APP_VER,
            "created": time.strftime("%Y-%m-%d %H:%M:%S"),
            "duration": self.events[-1]["t"] if self.events else 0,
            "events": self.events,
        }
        try:
            with open(path, "w", encoding="utf-8") as fp:
                json.dump(data, fp, ensure_ascii=False)
        except Exception as exc:
            messagebox.showerror("저장 실패", str(exc))
            return
        self.macro_name = os.path.splitext(os.path.basename(path))[0]
        self._update_stat()
        self.refresh_macro_list()
        self.log("저장 완료: %s" % os.path.basename(path), "ok")

    def load_macro(self):
        sel = self.lst.curselection()
        if sel:
            path = os.path.join(MACRO_DIR, self.lst.get(sel[0]).strip() + ".json")
        else:
            path = filedialog.askopenfilename(
                initialdir=MACRO_DIR, filetypes=[("매크로 파일", "*.json")],
                title="매크로 불러오기")
            if not path:
                return
        try:
            with open(path, "r", encoding="utf-8") as fp:
                data = json.load(fp)
            events = data["events"] if isinstance(data, dict) else data
            if not isinstance(events, list):
                raise ValueError("이벤트 목록을 찾을 수 없습니다.")
        except Exception as exc:
            messagebox.showerror("불러오기 실패", str(exc))
            return
        self.events = events
        self.macro_name = os.path.splitext(os.path.basename(path))[0]
        self._update_stat()
        self.log("불러오기 완료: %s (%d 이벤트)" % (self.macro_name, len(events)), "ok")

    def delete_macro(self):
        sel = self.lst.curselection()
        if not sel:
            messagebox.showinfo("삭제", "목록에서 삭제할 매크로를 선택하세요.")
            return
        name = self.lst.get(sel[0]).strip()
        if not messagebox.askyesno("삭제 확인", "'%s' 매크로를 삭제할까요?" % name):
            return
        try:
            os.remove(os.path.join(MACRO_DIR, name + ".json"))
        except Exception as exc:
            messagebox.showerror("삭제 실패", str(exc))
            return
        self.refresh_macro_list()
        self.log("삭제 완료: %s" % name, "warn")

    def restart_as_admin(self):
        if not messagebox.askyesno("관리자 권한으로 다시 실행",
                                   "지금 창을 닫고 관리자 권한으로 다시 실행합니다.\n"
                                   "설정은 그대로 유지됩니다. 계속할까요?"):
            return
        self.stop_all()
        self.save_config()
        if relaunch_as_admin():
            self.on_close()
        else:
            self.log("관리자 권한 실행이 취소되었거나 실패했습니다.", "warn")

    def open_folder(self):
        try:
            os.startfile(MACRO_DIR)
        except Exception as exc:
            self.log("폴더를 열 수 없습니다: %s" % exc, "err")

    # ------------------------------------------------------------ 종료
    def on_close(self):
        self.stop_all()
        self._end_capture()
        self.save_config()
        for lis in (getattr(self, "_hk_listener", None), getattr(self, "_hk_mouse", None),
                    self._capture_listener):
            try:
                if lis is not None:
                    lis.stop()
            except Exception:
                pass
        self.root.destroy()


def main():
    if not claim_single_instance():
        return                                 # 이미 떠 있는 창을 앞으로 올리고 끝
    root = tk.Tk()
    app = MacroApp(root)
    app._update_stat()
    root.mainloop()


if __name__ == "__main__":
    main()
