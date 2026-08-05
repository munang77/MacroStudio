# -*- coding: utf-8 -*-
"""자동 업데이트: GitHub 릴리스에서 새 버전을 확인하고 exe 를 갈아끼운다.

설치본(exe)에서만 의미가 있다. 소스로 실행 중일 때는 확인만 하고 교체는 하지 않는다.
"""

import json
import os
import re
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.request

DEFAULT_REPO = "munang77/MacroStudio"        # 설정(config.json)의 update_repo 로 바꿀 수 있다
API_URL = "https://api.github.com/repos/%s/releases/latest"
ASSET_NAME = "MacroStudio.exe"
TIMEOUT = 8
UA = "MacroStudio-Updater"


def parse_version(text):
    """'v2.1', '2.1.3' -> (2, 1, 3). 숫자가 없으면 (0,)."""
    nums = re.findall(r"\d+", text or "")
    return tuple(int(n) for n in nums) if nums else (0,)


def is_newer(remote, current):
    a, b = parse_version(remote), parse_version(current)
    size = max(len(a), len(b))
    a += (0,) * (size - len(a))
    b += (0,) * (size - len(b))
    return a > b


def _open(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA,
                                               "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=TIMEOUT, context=ssl.create_default_context())


def check(repo=DEFAULT_REPO):
    """최신 릴리스 정보. 못 가져오면 ('error', 사유) 형태의 dict."""
    try:
        with _open(API_URL % repo) as res:
            data = json.loads(res.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        reason = "릴리스가 아직 없습니다" if exc.code == 404 else "서버 응답 %s" % exc.code
        return {"error": reason}
    except Exception as exc:
        return {"error": "연결 실패 (%s)" % type(exc).__name__}

    version = data.get("tag_name") or data.get("name") or ""
    url = None
    for asset in data.get("assets") or []:
        if asset.get("name", "").lower() == ASSET_NAME.lower():
            url = asset.get("browser_download_url")
            break
    if not version:
        return {"error": "버전 정보를 찾을 수 없습니다"}
    if not url:
        return {"error": "릴리스에 %s 파일이 없습니다" % ASSET_NAME}
    return {"version": version.lstrip("vV"), "url": url,
            "notes": (data.get("body") or "").strip()}


def download(url, on_progress=None):
    """새 exe 를 임시 폴더에 받는다. 받은 파일 경로를 돌려준다."""
    fd, path = tempfile.mkstemp(prefix="MacroStudio_new_", suffix=".exe")
    os.close(fd)
    with _open(url) as res, open(path, "wb") as out:
        total = int(res.headers.get("Content-Length") or 0)
        got = 0
        while True:
            chunk = res.read(64 * 1024)
            if not chunk:
                break
            out.write(chunk)
            got += len(chunk)
            if on_progress:
                on_progress(got, total)
    verify(path)
    return path


def verify(path):
    """받은 파일이 진짜 실행 파일인지 최소한만 확인한다."""
    if os.path.getsize(path) < 1024 * 1024:
        raise ValueError("받은 파일이 너무 작습니다")
    with open(path, "rb") as fp:
        if fp.read(2) != b"MZ":
            raise ValueError("실행 파일이 아닙니다")
    return True


def swap_script(new_file, target, relaunch=True):
    """앱이 꺼진 뒤 파일을 갈아끼우고 다시 실행하는 배치를 만든다."""
    lines = [
        "@echo off",
        "chcp 949 >nul",
        "set N=0",
        ":retry",
        'move /y "%s" "%s" >nul 2>&1' % (new_file, target),
        "if not errorlevel 1 goto ok",
        "ping 127.0.0.1 -n 2 >nul",
        "set /a N+=1",
        "if %N% LSS 30 goto retry",
        "echo 업데이트를 적용하지 못했습니다. 프로그램이 아직 실행 중일 수 있습니다.",
        "pause",
        "exit /b 1",
        ":ok",
    ]
    if relaunch:
        lines.append('start "" "%s"' % target)
    lines += ['del "%~f0"', "exit /b 0"]

    fd, path = tempfile.mkstemp(prefix="MacroStudio_update_", suffix=".bat")
    os.close(fd)
    with open(path, "w", encoding="cp949", newline="\r\n") as fp:
        fp.write("\r\n".join(lines) + "\r\n")
    return path


def apply_update(new_file, target, relaunch=True):
    """교체 배치를 띄운다. 이 함수가 돌아오면 앱은 바로 종료해야 한다."""
    script = swap_script(new_file, target, relaunch)
    subprocess.Popen(["cmd", "/c", script],
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    return script
