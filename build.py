# -*- coding: utf-8 -*-
"""배포용 빌드: 아이콘 생성 -> exe 묶기 -> 배포 폴더 구성.

결과: 배포\\ 폴더 (MacroStudio.exe + 설치.bat + 제거 스크립트 + 설명서)
이 폴더째 압축해서 넘기면 받는 사람은 설치.bat 만 누르면 된다.
"""

import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
APP = "MacroStudio"
RELEASE = os.path.join(HERE, "배포")
SHIP = ["설치.bat", "install.ps1", "제거.bat", "uninstall.ps1", "README.md"]


def run(cmd):
    print(">", " ".join(cmd))
    if subprocess.call(cmd, cwd=HERE) != 0:
        sys.exit("빌드 실패: " + " ".join(cmd))


def main():
    run([sys.executable, "make_icon.py"])

    # 안 쓰는데 딸려 들어오는 것들 (numpy 26MB, AVIF 코덱 7.5MB 등)
    excludes = ["numpy", "PIL._avif", "PIL.AvifImagePlugin", "PIL._imagingft",
                "PIL.ImageQt", "PIL.ImageShow", "PIL.ImageGrab", "PIL.ImageCms",
                "PIL._imagingcms", "PIL.ImageTk.tkinter", "scipy", "pandas",
                "matplotlib", "pytest", "setuptools", "pip", "unittest",
                "pydoc", "doctest", "xmlrpc", "sqlite3", "curses"]
    cmd = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
           "--onefile", "--windowed", "--name", APP, "--icon", "icon.ico",
           "--add-data", "icon.ico;."]
    for mod in excludes:
        cmd += ["--exclude-module", mod]
    run(cmd + ["macro.py"])

    exe = os.path.join(HERE, "dist", APP + ".exe")
    if not os.path.exists(exe):
        sys.exit("exe 가 만들어지지 않았습니다.")

    if os.path.exists(RELEASE):
        shutil.rmtree(RELEASE)
    os.makedirs(RELEASE)
    shutil.copy2(exe, RELEASE)
    for name in SHIP:
        src = os.path.join(HERE, name)
        if os.path.exists(src):
            shutil.copy2(src, RELEASE)

    size = os.path.getsize(exe) / 1024 / 1024

    # 중간 산출물 정리 (배포 폴더에 이미 복사했다)
    # 갓 만든 exe 는 백신이 잠깐 잡고 있을 수 있어 몇 번 다시 시도한다
    for junk in ("build", "dist", APP + ".spec"):
        path = os.path.join(HERE, junk)
        for attempt in range(5):
            try:
                if os.path.isdir(path):
                    shutil.rmtree(path)
                elif os.path.exists(path):
                    os.remove(path)
                break
            except OSError:
                time.sleep(0.8)

    print()
    print("완료: %s (%.1f MB)" % (RELEASE, size))
    print("이 폴더를 통째로 압축해서 넘기면 됩니다. 받는 쪽은 설치.bat 실행.")


if __name__ == "__main__":
    main()
