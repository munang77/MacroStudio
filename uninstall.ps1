# MacroStudio 제거 스크립트
param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Programs'),
    [string]$StartMenuDir = (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),
    [string]$DesktopDir = [Environment]::GetFolderPath('Desktop'),
    [string]$RegPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\MacroStudio',
    [string]$DataDir = (Join-Path $env:LOCALAPPDATA 'MacroStudio'),
    [switch]$KeepData,
    [switch]$Quiet
)

$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$AppName = 'MacroStudio'
$target = Join-Path $InstallRoot $AppName

Get-Process $AppName -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output "실행 중인 $AppName 을 닫습니다..."
    $_ | Stop-Process -Force
    Start-Sleep -Milliseconds 800
}

foreach ($lnk in @((Join-Path $StartMenuDir "$AppName.lnk"),
                   (Join-Path $DesktopDir "$AppName.lnk"))) {
    if (Test-Path $lnk) { Remove-Item $lnk -Force; Write-Output "바로가기 삭제: $lnk" }
}

if (Test-Path $RegPath) { Remove-Item $RegPath -Recurse -Force; Write-Output "앱 목록에서 제거" }

# 저장한 매크로를 지울지 물어본다 (기본은 남김)
$removeData = $false
if (-not $KeepData -and (Test-Path $DataDir)) {
    if ($Quiet) {
        $removeData = $false
    } else {
        $ans = Read-Host "저장한 매크로와 설정도 지울까요? (y/N)"
        $removeData = ($ans -eq 'y' -or $ans -eq 'Y')
    }
}
if ($removeData) {
    Remove-Item $DataDir -Recurse -Force
    Write-Output "사용자 데이터 삭제: $DataDir"
} elseif (Test-Path $DataDir) {
    Write-Output "사용자 데이터는 남겨 둡니다: $DataDir"
}

# 자기 자신이 들어 있는 폴더는 마지막에 지운다
if (Test-Path $target) {
    $bat = Join-Path $env:TEMP "remove_macrostudio.bat"
    @"
@echo off
ping 127.0.0.1 -n 3 >nul
rmdir /s /q "$target"
del "%~f0"
"@ | Set-Content -Path $bat -Encoding oem
    Start-Process -FilePath $bat -WindowStyle Hidden
    Write-Output "프로그램 폴더 삭제: $target"
}

Write-Output ""
Write-Output "$AppName 을 제거했습니다."
if (-not $Quiet) { Read-Host "엔터를 누르면 닫힙니다" }
