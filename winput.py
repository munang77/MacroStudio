# -*- coding: utf-8 -*-
"""윈도우 SendInput 기반 입력 주입기.

pynput 의 기본 방식(SetCursorPos, 가상키 입력)은 프로그램에 따라 무시되거나
'사람이 만든 입력' 처럼 보이지 않는다. 여기서는 실제 마우스/키보드가 만드는 것과 같은
경로(SendInput + 절대좌표 + 스캔코드)로 보낸다. 윈도우가 아니면 pynput 으로 넘긴다.
"""

import ctypes
from ctypes import wintypes

try:
    _user32 = ctypes.windll.user32
    _user32.SendInput.argtypes = (wintypes.UINT, ctypes.c_void_p, ctypes.c_int)
    _user32.SendInput.restype = wintypes.UINT
    _user32.VkKeyScanW.argtypes = (ctypes.c_wchar,)
    _user32.VkKeyScanW.restype = ctypes.c_short
    AVAILABLE = True
except Exception:                       # 윈도우가 아닌 환경
    _user32 = None
    AVAILABLE = False

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

BUTTON_FLAGS = {                        # 이름: (누름, 뗌, mouseData)
    "left": (0x0002, 0x0004, 0),
    "right": (0x0008, 0x0010, 0),
    "middle": (0x0020, 0x0040, 0),
    "x1": (0x0080, 0x0100, 0x0001),
    "x2": (0x0080, 0x0100, 0x0002),
}

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
KEYEVENTF_SCANCODE = 0x0008

# 확장 키: 오른쪽 Ctrl(A3)/Alt(A5), PgUp~방향키(21~28), Ins/Del(2D,2E),
# PrintScreen(2C), NumLock(90), 넘패드 나누기(6F)
EXTENDED_VKS = {0xA3, 0xA5, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                0x2D, 0x2E, 0x2C, 0x90, 0x6F}

SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN = 76, 77
SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 78, 79

ULONG_PTR = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


def _send(*inputs):
    if not AVAILABLE or not inputs:
        return False
    arr = (INPUT * len(inputs))(*inputs)
    sent = _user32.SendInput(len(inputs), ctypes.byref(arr), ctypes.sizeof(INPUT))
    return sent == len(inputs)


def _virtual_screen():
    return (_user32.GetSystemMetrics(SM_XVIRTUALSCREEN),
            _user32.GetSystemMetrics(SM_YVIRTUALSCREEN),
            max(1, _user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)),
            max(1, _user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)))


def _norm(x, y):
    """화면 좌표를 SendInput 이 쓰는 0~65535 절대 좌표로."""
    vx, vy, vw, vh = _virtual_screen()
    nx = int(round((x - vx) * 65535.0 / max(1, vw - 1)))
    ny = int(round((y - vy) * 65535.0 / max(1, vh - 1)))
    return max(0, min(65535, nx)), max(0, min(65535, ny))


def move_to(x, y):
    nx, ny = _norm(x, y)
    return _send(INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
        dx=nx, dy=ny, mouseData=0,
        dwFlags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
        time=0, dwExtraInfo=0)))


def button(name, down, x=None, y=None):
    flags = BUTTON_FLAGS.get(name)
    if flags is None:
        return False
    press, release, data = flags
    flag = press if down else release
    mi = MOUSEINPUT(dx=0, dy=0, mouseData=data, dwFlags=flag, time=0, dwExtraInfo=0)
    if x is not None and y is not None:      # 좌표까지 한 번에 (이동 후 클릭이 끊기지 않게)
        nx, ny = _norm(x, y)
        mi.dx, mi.dy = nx, ny
        mi.dwFlags = flag | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
    return _send(INPUT(type=INPUT_MOUSE, mi=mi))


def wheel(dx, dy):
    events = []
    if dy:
        events.append(INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
            dx=0, dy=0, mouseData=int(dy * 120) & 0xFFFFFFFF,
            dwFlags=MOUSEEVENTF_WHEEL, time=0, dwExtraInfo=0)))
    if dx:
        events.append(INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
            dx=0, dy=0, mouseData=int(dx * 120) & 0xFFFFFFFF,
            dwFlags=MOUSEEVENTF_HWHEEL, time=0, dwExtraInfo=0)))
    return _send(*events)


def key_vk(vk, down):
    """가상키를 스캔코드로 바꿔서 보낸다 (게임은 대개 스캔코드만 읽는다)."""
    if not AVAILABLE or not vk:
        return False
    scan = _user32.MapVirtualKeyW(vk, 0)
    flags = KEYEVENTF_SCANCODE
    if vk in EXTENDED_VKS:
        flags |= KEYEVENTF_EXTENDEDKEY
    if not down:
        flags |= KEYEVENTF_KEYUP
    if not scan:                                  # 스캔코드가 없으면 가상키 그대로
        flags = KEYEVENTF_KEYUP if not down else 0
        scan = 0
    return _send(INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(
        wVk=0 if scan else vk, wScan=scan, dwFlags=flags, time=0, dwExtraInfo=0)))


def key_unicode(ch, down):
    """스캔코드가 없는 글자(한글 등)는 유니코드로 직접 보낸다."""
    if not AVAILABLE or not ch:
        return False
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if not down else 0)
    return _send(INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(
        wVk=0, wScan=ord(ch[0]), dwFlags=flags, time=0, dwExtraInfo=0)))


if AVAILABLE:
    _user32.WindowFromPoint.argtypes = (wintypes.POINT,)
    _user32.WindowFromPoint.restype = wintypes.HWND
    _user32.GetAncestor.argtypes = (wintypes.HWND, wintypes.UINT)
    _user32.GetAncestor.restype = wintypes.HWND


def window_root_at(x, y):
    """그 좌표에서 실제로 맨 위에 있는 창의 최상위 핸들. 좌표만 보는 것과 달리
    다른 프로그램이 위에 떠 있으면 그 창을 돌려준다."""
    if not AVAILABLE:
        return None
    hwnd = _user32.WindowFromPoint(wintypes.POINT(int(x), int(y)))
    if not hwnd:
        return None
    return _user32.GetAncestor(hwnd, 2)          # GA_ROOT


def vk_for_char(ch):
    """글자에 해당하는 자판 위치(가상키). 현재 자판으로 칠 수 없는 글자면 None."""
    if not AVAILABLE or not ch:
        return None
    res = _user32.VkKeyScanW(ch[0])
    return None if res == -1 else (res & 0xFF)


def cursor_pos():
    pt = wintypes.POINT()
    if AVAILABLE and _user32.GetCursorPos(ctypes.byref(pt)):
        return (pt.x, pt.y)
    return None
