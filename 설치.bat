@echo off
chcp 949 >nul
title MacroStudio ¼³Ä¡
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
