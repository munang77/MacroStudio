@echo off
chcp 949 >nul
title MacroStudio - 매크로 프로그램
cd /d "%~dp0"

python --version >nul 2>&1
if errorlevel 1 (
    echo [오류] 파이썬을 찾을 수 없습니다. https://python.org 에서 설치하세요.
    pause
    exit /b 1
)

python -c "import pynput, PIL" >nul 2>&1
if errorlevel 1 (
    echo 필요한 패키지를 설치합니다. 잠시만 기다려 주세요...
    python -m pip install -r requirements.txt
)

start "" pythonw macro.py
