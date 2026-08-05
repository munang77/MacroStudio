@echo off
chcp 949 >nul
title MacroStudio ºôµå
cd /d "%~dp0"
python build.py
pause
