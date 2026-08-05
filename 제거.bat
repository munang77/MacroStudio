@echo off
chcp 949 >nul
title MacroStudio Á¦°Å
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
