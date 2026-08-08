# -*- coding: utf-8 -*-
"""창작 모드 화면 - 블록을 조립해 나만의 매크로를 만드는 페이지와 블록 편집창."""

import json
import os
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox

import builder
import ui_kit as ui
from ui_kit import (ACCENT, BG, CARD, DANGER, FIELD, LINE, MONO, OK, TXT, TXT_DIM,
                    TXT_MUTE, UI, WARN, Btn, Card, Segmented, Select, Stepper, TextField)

PICK_DELAY = 2.5             # 화면에서 좌표/색을 집을 때까지 기다리는 시간(초)


class StepDialog(tk.Toplevel):
    """블록 하나를 고치는 작은 창. 종류에 따라 필요한 칸만 보여 준다."""

    def __init__(self, app, step, on_save, macro_names=()):
        super().__init__(app.root)
        self.app = app
        self.step = dict(step)
        self.on_save = on_save
        self.macro_names = list(macro_names)
        self.widgets = {}
        self._pick_job = None

        self.title(builder.TYPE_LABEL.get(step["type"], "블록"))
        self.configure(bg=BG)
        self.resizable(False, False)
        self.transient(app.root)
        try:
            self.iconbitmap(app.icon_path)
        except Exception:
            pass

        card = Card(self, bg=CARD, outer=BG, pad=18)
        card.pack(fill="both", expand=True, padx=14, pady=14)
        body = card.body

        head = tk.Frame(body, bg=CARD)
        head.pack(fill="x", pady=(0, 14))
        im = ui.icon("blocks", 16, ACCENT, CARD)
        lb = tk.Label(head, image=im, bg=CARD, bd=0)
        lb.image = im
        lb.pack(side="left", padx=(0, 8))
        tk.Label(head, text=builder.TYPE_LABEL.get(step["type"], "블록"),
                 bg=CARD, fg=TXT, font=UI(12, "bold")).pack(side="left")

        self.rows = tk.Frame(body, bg=CARD)
        self.rows.pack(fill="x")
        self._build_fields()

        foot = tk.Frame(body, bg=CARD)
        foot.pack(fill="x", pady=(18, 0))
        Btn(foot, "저장", self._save, width=110, height=38, variant="primary",
            bg=CARD).pack(side="left")
        Btn(foot, "취소", self.destroy, width=90, height=38, variant="ghost",
            bg=CARD).pack(side="left", padx=8)
        self.lbl_hint = tk.Label(foot, text="", bg=CARD, fg=WARN, font=UI(9))
        self.lbl_hint.pack(side="left", padx=12)

        self.update_idletasks()
        x = app.root.winfo_rootx() + (app.root.winfo_width() - self.winfo_width()) // 2
        y = app.root.winfo_rooty() + 140
        self.geometry("+%d+%d" % (max(0, x), max(0, y)))
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.grab_set()

    # ------------------------------------------------------------ 칸 만들기
    def _row(self, label):
        box = tk.Frame(self.rows, bg=CARD)
        box.pack(fill="x", pady=(0, 12))
        tk.Label(box, text=label, bg=CARD, fg=TXT_MUTE, font=UI(9),
                 width=16, anchor="w").pack(side="left")
        return box

    def _build_fields(self):
        t = self.step["type"]
        s = self.step

        if t in ("click", "move", "wait_color", "if_color"):
            box = self._row("위치 (X, Y)")
            self.widgets["x"] = Stepper(box, s.get("x", 0), 0, 20000, 1, width=118, height=34)
            self.widgets["x"].pack(side="left")
            self.widgets["y"] = Stepper(box, s.get("y", 0), 0, 20000, 1, width=118, height=34)
            self.widgets["y"].pack(side="left", padx=8)
            self.btn_pick = Btn(box, "화면에서 집기", self._pick, width=126, height=34,
                                variant="soft", bg=CARD, font=UI(9, "bold"))
            self.btn_pick.pack(side="left")

        if t == "click":
            box = self._row("버튼")
            self.widgets["button"] = Segmented(
                box, [("왼쪽", "left"), ("오른쪽", "right"), ("휠", "middle"),
                      ("옆1", "x1"), ("옆2", "x2")], s.get("button", "left"),
                bg=CARD, height=32)
            self.widgets["button"].pack(side="left")
            box = self._row("몇 번 누를까")
            self.widgets["count"] = Stepper(box, s.get("count", 1), 1, 100, 1,
                                            width=118, height=34)
            self.widgets["count"].pack(side="left")

        if t == "key":
            box = self._row("키")
            self.widgets["key"] = TextField(box, s.get("key", "space"), width=150,
                                            height=34, bg=CARD)
            self.widgets["key"].pack(side="left")
            Btn(box, "캡처", self._capture_key, width=70, height=34, variant="soft",
                bg=CARD, font=UI(9, "bold")).pack(side="left", padx=8)
            tk.Label(box, text="예: a · space · f5 · ctrl+c", bg=CARD, fg=TXT_MUTE,
                     font=MONO(9)).pack(side="left")
            box = self._row("누르고 있기 (ms)")
            self.widgets["hold"] = Stepper(box, s.get("hold", 30), 0, 10000, 10,
                                           width=134, height=34)
            self.widgets["hold"].pack(side="left")

        if t == "wait":
            box = self._row("기다릴 시간 (ms)")
            self.widgets["ms"] = Stepper(box, s.get("ms", 500), 0, 600000, 100,
                                         width=134, height=34)
            self.widgets["ms"].pack(side="left")
            box = self._row("랜덤 추가 (ms)")
            self.widgets["rand"] = Stepper(box, s.get("rand", 0), 0, 600000, 100,
                                           width=134, height=34)
            self.widgets["rand"].pack(side="left")
            tk.Label(box, text="0보다 크면 매번 조금씩 다르게 쉽니다", bg=CARD,
                     fg=TXT_MUTE, font=UI(9)).pack(side="left", padx=10)

        if t == "scroll":
            box = self._row("휠 칸수 (+위 / -아래)")
            self.widgets["dy"] = Stepper(box, s.get("dy", -3), -50, 50, 1,
                                         width=118, height=34)
            self.widgets["dy"].pack(side="left")

        if t in ("wait_color", "if_color"):
            box = self._row("색")
            self.swatch = tk.Frame(box, bg=builder.hexcolor(s.get("color", [255, 255, 255])),
                                   width=34, height=34, highlightthickness=1,
                                   highlightbackground=LINE)
            self.swatch.pack(side="left")
            self.swatch.pack_propagate(False)
            self.lbl_color = tk.Label(box, text=builder.hexcolor(s.get("color", [255, 255, 255])),
                                      bg=CARD, fg=TXT, font=MONO(10, "bold"))
            self.lbl_color.pack(side="left", padx=10)
            tk.Label(box, text="위치를 집으면 그 지점 색이 함께 들어옵니다", bg=CARD,
                     fg=TXT_MUTE, font=UI(9)).pack(side="left")

            box = self._row("허용 오차")
            self.widgets["tol"] = Stepper(box, s.get("tol", 25), 0, 255, 5,
                                          width=118, height=34)
            self.widgets["tol"].pack(side="left")
            tk.Label(box, text="클수록 비슷한 색도 같다고 봅니다", bg=CARD, fg=TXT_MUTE,
                     font=UI(9)).pack(side="left", padx=10)

        if t == "wait_color":
            box = self._row("언제까지 기다릴까")
            self.widgets["mode"] = Segmented(
                box, [("그 색이 될 때까지", "appear"), ("그 색이 아닐 때까지", "vanish")],
                s.get("mode", "appear"), bg=CARD, height=32)
            self.widgets["mode"].pack(side="left")
            box = self._row("최대 대기 (ms)")
            self.widgets["timeout"] = Stepper(box, s.get("timeout", 10000), 100, 600000, 500,
                                              width=134, height=34)
            self.widgets["timeout"].pack(side="left")
            self.widgets["on_timeout"] = Segmented(
                box, [("정지", "stop"), ("그냥 진행", "continue")],
                s.get("on_timeout", "stop"), bg=CARD, height=32)
            self.widgets["on_timeout"].pack(side="left", padx=10)

        if t == "if_color":
            flow = [("계속", "continue"), ("건너뛰기", "skip"), ("처음으로", "restart"),
                    ("정지", "stop")]
            box = self._row("색이 맞으면")
            self.widgets["then"] = Segmented(box, flow, s.get("then", "continue"),
                                             bg=CARD, height=32)
            self.widgets["then"].pack(side="left")
            self.widgets["skip"] = Stepper(box, s.get("skip", 1), 1, 50, 1,
                                           width=96, height=34)
            self.widgets["skip"].pack(side="left", padx=8)
            box = self._row("아니면")
            self.widgets["else"] = Segmented(box, flow, s.get("else", "restart"),
                                             bg=CARD, height=32)
            self.widgets["else"].pack(side="left")
            self.widgets["else_skip"] = Stepper(box, s.get("else_skip", 1), 1, 50, 1,
                                                width=96, height=34)
            self.widgets["else_skip"].pack(side="left", padx=8)
            tk.Label(self.rows, text="건너뛰기를 고른 경우에만 옆의 숫자(몇 개 건너뛸지)를 씁니다",
                     bg=CARD, fg=TXT_MUTE, font=UI(9)).pack(anchor="w")

        if t == "macro":
            box = self._row("기록해 둔 매크로")
            names = self.macro_names or ["(저장된 매크로 없음)"]
            cur = s.get("name") or names[0]
            self.widgets["name"] = Select(box, names, cur if cur in names else names[0],
                                          bg=CARD, width=240)
            self.widgets["name"].pack(side="left")

    # ------------------------------------------------------------ 집기
    def _pick(self):
        if self._pick_job:
            return
        self.btn_pick.set_enabled(False)
        self._countdown(int(PICK_DELAY))

    def _countdown(self, left):
        if left <= 0:
            self.btn_pick.config_text("집는 중...")
            threading.Thread(target=self._grab, daemon=True).start()
            return
        self.btn_pick.config_text("%d초 뒤 집기" % left)
        self._pick_job = self.after(1000, lambda: self._countdown(left - 1))

    def _grab(self):
        pos = self.app.sender.position() or (0, 0)
        color = ui.rgb("#ffffff")
        got = builder.winput.pixel_color(pos[0], pos[1])
        if got:
            color = got
        self.after(0, lambda: self._apply_pick(pos, color))

    def _apply_pick(self, pos, color):
        self._pick_job = None
        self.widgets["x"].set(int(pos[0]))
        self.widgets["y"].set(int(pos[1]))
        if hasattr(self, "swatch"):
            self.step["color"] = list(color)
            self.swatch.configure(bg=builder.hexcolor(color))
            self.lbl_color.configure(text=builder.hexcolor(color))
        self.btn_pick.config_text("화면에서 집기")
        self.btn_pick.set_enabled(True)

    def _capture_key(self):
        self.app.capture_key({"key": self.widgets["key"],
                              "cap": _DummyBtn()})

    # ------------------------------------------------------------ 저장
    def _save(self):
        s = dict(self.step)
        for name, w in self.widgets.items():
            s[name] = w.get()
        if s["type"] == "key" and not str(s.get("key", "")).strip():
            self.lbl_hint.configure(text="키를 입력하세요")
            return
        if s["type"] == "macro" and s.get("name", "").startswith("("):
            self.lbl_hint.configure(text="먼저 기록 매크로를 저장하세요")
            return
        self.on_save(s)
        self.destroy()


class _DummyBtn:
    """키 캡처가 버튼 상태를 바꾸려 할 때 받아 주는 껍데기."""

    def config_text(self, *a, **k):
        pass

    def set_enabled(self, *a, **k):
        pass


class CreatorPage:
    """창작 페이지 전체 (블록 목록 + 실행 + 저장/불러오기)."""

    def __init__(self, app, parent, save_dir):
        self.app = app
        self.save_dir = save_dir
        self.steps = []
        self.name = "새 창작"
        os.makedirs(save_dir, exist_ok=True)

        self.frame = tk.Frame(parent, bg=BG)
        self._build()

    # ------------------------------------------------------------ 화면
    def _build(self):
        app = self.app
        card, head, body = app._card(self.frame, "블록", "위에서부터 차례로 실행합니다",
                                     "blocks", expand=True)
        self.lbl_name = tk.Label(head, text="", bg=CARD, fg=TXT_DIM, font=MONO(9))
        self.lbl_name.pack(side="right")

        top = tk.Frame(body, bg=CARD)
        top.pack(fill="x", pady=(0, 10))
        self.sel_type = Select(top, [builder.TYPE_LABEL[t] for t in builder.TYPES],
                               builder.TYPE_LABEL["click"], bg=CARD, width=170)
        self.sel_type.pack(side="left")
        Btn(top, "+ 블록 추가", self.add_step, width=118, height=34, variant="soft",
            bg=CARD, font=UI(9, "bold")).pack(side="left", padx=8)
        Btn(top, "낚시 예제 넣기", self.load_sample, width=124, height=34, variant="ghost",
            bg=CARD, font=UI(9, "bold")).pack(side="left")
        tk.Label(top, text="색이 변하는 걸 기다리려면 '색 기다리기'", bg=CARD, fg=TXT_MUTE,
                 font=UI(9)).pack(side="left", padx=8)

        wrap = tk.Frame(body, bg=CARD)
        wrap.pack(fill="both", expand=True)
        panel = Card(wrap, bg=FIELD, outer=CARD, radius=10, pad=8)
        panel.pack(side="left", fill="both", expand=True)
        self.lst = tk.Listbox(panel.body, bg=FIELD, fg=TXT, bd=0, highlightthickness=0,
                              selectbackground=ACCENT, selectforeground="#ffffff",
                              font=MONO(10), activestyle="none")
        self.lst.pack(fill="both", expand=True)
        self.lst.bind("<Double-Button-1>", lambda _e: self.edit_step())
        self.lbl_empty = tk.Label(panel.body, bg=FIELD, fg=TXT_MUTE, font=UI(9),
                                  justify="center",
                                  text="아직 블록이 없습니다\n위에서 종류를 고르고 '블록 추가' 를 누르세요")

        side = tk.Frame(wrap, bg=CARD)
        side.pack(side="left", padx=(12, 0), anchor="n")
        for text, cmd in (("편집", self.edit_step), ("복제", self.duplicate_step),
                          ("위로", lambda: self.move_step(-1)),
                          ("아래로", lambda: self.move_step(1)),
                          ("삭제", self.delete_step)):
            Btn(side, text, cmd, width=92, height=32, variant="ghost", bg=CARD,
                font=UI(9, "bold")).pack(pady=2)

        _c, _h, body2 = app._card(self.frame, "실행", None, "play")
        row = tk.Frame(body2, bg=CARD)
        row.pack(fill="x")
        self.btn_run = Btn(row, "창작 시작", self.toggle_run, width=180, height=44,
                           variant="primary", bg=CARD,
                           hint=app.hotkey_hint("build"))
        self.btn_run.pack(side="left")

        f = app._field(row, "반복 바퀴 수  (0 = 무한)")
        self.st_loops = Stepper(f, 0, 0, 999999, 1, width=132)
        self.st_loops.pack()
        f.pack(side="left", padx=18)

        self.lbl_state = tk.Label(row, text="정지 상태", bg=CARD, fg=TXT_MUTE, font=MONO(10))
        self.lbl_state.pack(side="left", padx=6, pady=(18, 0))

        files = tk.Frame(row, bg=CARD)
        files.pack(side="right", pady=(14, 0))
        for text, cmd in (("저장", self.save), ("불러오기", self.load), ("새로", self.clear)):
            Btn(files, text, cmd, width=84, height=32, variant="ghost", bg=CARD,
                font=UI(9, "bold")).pack(side="left", padx=3)

        app._tip(self.frame, "낚시 예제를 넣고 '색 기다리기' 블록만 자기 화면에 맞게 고치면 바로 "
                             "돌아갑니다. 편집창의 '화면에서 집기' 를 누르고 찌 위에 마우스를 올려 두면 "
                             "좌표와 색을 한 번에 가져옵니다.")
        tk.Frame(self.frame, bg=BG).pack(fill="both", expand=True)
        self.refresh()

    # ------------------------------------------------------------ 목록
    def refresh(self, active=None):
        keep = self.lst.curselection()
        self.lst.delete(0, "end")
        for i, step in enumerate(self.steps):
            self.lst.insert("end", " %2d.  %s" % (i + 1, builder.describe(step)))
        if self.steps:
            self.lbl_empty.place_forget()
        else:
            self.lbl_empty.place(relx=0.5, rely=0.5, anchor="center")
        if active is not None and 0 <= active < len(self.steps):
            self.lst.selection_clear(0, "end")
            self.lst.selection_set(active)
            self.lst.see(active)
        elif keep:
            idx = min(keep[0], max(0, len(self.steps) - 1))
            if self.steps:
                self.lst.selection_set(idx)
        self.lbl_name.configure(text="%s   블록 %d개" % (self.name, len(self.steps)))

    def _selected(self):
        sel = self.lst.curselection()
        return sel[0] if sel else None

    def _macro_names(self):
        try:
            return sorted(f[:-5] for f in os.listdir(self.app.macro_dir)
                          if f.lower().endswith(".json"))
        except Exception:
            return []

    def add_step(self):
        label = self.sel_type.get()
        kind = next((t for t in builder.TYPES if builder.TYPE_LABEL[t] == label), "click")
        pos = self.app.sender.position() or (0, 0)
        color = builder.winput.pixel_color(pos[0], pos[1]) or (255, 255, 255)
        step = builder.default_step(kind, int(pos[0]), int(pos[1]), color)

        def done(new_step):
            at = self._selected()
            if at is None:
                self.steps.append(new_step)
                self.refresh(len(self.steps) - 1)
            else:
                self.steps.insert(at + 1, new_step)
                self.refresh(at + 1)
            self.app.log("블록 추가: %s" % builder.describe(new_step))

        StepDialog(self.app, step, done, self._macro_names())

    def edit_step(self):
        i = self._selected()
        if i is None:
            self.app.log("고칠 블록을 먼저 고르세요.", "warn")
            return

        def done(new_step):
            self.steps[i] = new_step
            self.refresh(i)

        StepDialog(self.app, self.steps[i], done, self._macro_names())

    def duplicate_step(self):
        i = self._selected()
        if i is None:
            return
        self.steps.insert(i + 1, dict(self.steps[i]))
        self.refresh(i + 1)

    def delete_step(self):
        i = self._selected()
        if i is None:
            return
        self.steps.pop(i)
        self.refresh(min(i, len(self.steps) - 1) if self.steps else None)

    def move_step(self, delta):
        i = self._selected()
        if i is None:
            return
        j = i + delta
        if not (0 <= j < len(self.steps)):
            return
        self.steps[i], self.steps[j] = self.steps[j], self.steps[i]
        self.refresh(j)

    def load_sample(self):
        """낚시 매크로 뼈대. 위치와 색만 자기 화면에 맞게 고치면 바로 쓸 수 있다."""
        if self.steps and not messagebox.askyesno(
                "예제 넣기", "지금 블록을 예제로 바꿀까요?"):
            return
        pos = self.app.sender.position() or (960, 540)
        color = builder.winput.pixel_color(pos[0], pos[1]) or (200, 60, 50)
        x, y = int(pos[0]), int(pos[1])
        self.steps = [
            {"type": "key", "key": "space", "hold": 40},
            {"type": "wait", "ms": 800, "rand": 400},
            {"type": "wait_color", "x": x, "y": y, "color": list(color), "tol": 30,
             "mode": "appear", "timeout": 20000, "on_timeout": "continue"},
            {"type": "key", "key": "space", "hold": 40},
            {"type": "wait", "ms": 1500, "rand": 600},
        ]
        self.name = "낚시 예제"
        self.refresh(0)
        self.app.log("낚시 예제를 넣었습니다. '색 기다리기' 블록의 위치와 색을 "
                     "자기 화면에 맞게 고치세요.", "ok")

    def clear(self):
        if self.steps and not messagebox.askyesno("새로 만들기", "지금 블록을 모두 지울까요?"):
            return
        self.steps = []
        self.name = "새 창작"
        self.refresh()

    # ------------------------------------------------------------ 실행
    def toggle_run(self):
        app = self.app
        if app.creator_runner.running:
            app.creator_runner.stop()
            return
        if not self.steps:
            app.log("실행할 블록이 없습니다. 먼저 블록을 추가하세요.", "warn")
            return
        if app.creator_runner.start(self.steps, self.st_loops.get()):
            self.btn_run.config_text("창작 중지", "danger", app.hotkey_hint("build"))
            self.lbl_state.configure(text="실행 중", fg=OK)
            app.pill.set("창작 실행 중", OK, pulse=True)
            app.log("창작 시작: %s (블록 %d개)" % (self.name, len(self.steps)))

    def on_done(self):
        self.btn_run.config_text("창작 시작", "primary", self.app.hotkey_hint("build"))
        self.lbl_state.configure(text="%d바퀴 실행 후 정지" % self.app.creator_runner.cycles,
                                 fg=TXT_MUTE)

    def on_step(self, index, cycles):
        self.refresh(index)
        self.lbl_state.configure(text="실행 중  %d번 블록  %d바퀴" % (index + 1, cycles), fg=OK)

    # ------------------------------------------------------------ 파일
    def save(self):
        if not self.steps:
            messagebox.showinfo("저장", "저장할 블록이 없습니다.")
            return
        path = filedialog.asksaveasfilename(
            initialdir=self.save_dir, defaultextension=".json",
            filetypes=[("창작 파일", "*.json")], title="창작 저장")
        if not path:
            return
        data = {"app": "MacroStudio", "kind": "creation",
                "created": time.strftime("%Y-%m-%d %H:%M:%S"), "steps": self.steps}
        try:
            with open(path, "w", encoding="utf-8") as fp:
                json.dump(data, fp, ensure_ascii=False, indent=1)
        except Exception as exc:
            messagebox.showerror("저장 실패", str(exc))
            return
        self.name = os.path.splitext(os.path.basename(path))[0]
        self.refresh()
        self.app.log("창작 저장: %s" % self.name, "ok")

    def load(self):
        path = filedialog.askopenfilename(
            initialdir=self.save_dir, filetypes=[("창작 파일", "*.json")], title="창작 불러오기")
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8") as fp:
                data = json.load(fp)
            steps = data["steps"] if isinstance(data, dict) else data
            if not isinstance(steps, list):
                raise ValueError("블록 목록을 찾을 수 없습니다.")
            for s in steps:
                if not isinstance(s, dict) or s.get("type") not in builder.TYPES:
                    raise ValueError("알 수 없는 블록이 들어 있습니다.")
        except Exception as exc:
            messagebox.showerror("불러오기 실패", str(exc))
            return
        self.steps = steps
        self.name = os.path.splitext(os.path.basename(path))[0]
        self.refresh()
        self.app.log("창작 불러오기: %s (블록 %d개)" % (self.name, len(steps)), "ok")
