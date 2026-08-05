# MacroStudio 설치 스크립트 (관리자 권한 필요 없음, 현재 사용자에게만 설치)
param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Programs'),
    [string]$StartMenuDir = (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),
    [string]$DesktopDir = [Environment]::GetFolderPath('Desktop'),
    [string]$RegPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\MacroStudio',
    [switch]$NoDesktopShortcut,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$AppName = 'MacroStudio'
$Version = '2.2'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $here "$AppName.exe"
$target = Join-Path $InstallRoot $AppName

if (-not (Test-Path $source)) {
    Write-Output "[오류] $AppName.exe 를 찾을 수 없습니다: $source"
    if (-not $Quiet) { Read-Host "엔터를 누르면 닫힙니다" }
    exit 1
}

# 실행 중이면 먼저 종료
Get-Process $AppName -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output "실행 중인 $AppName 을 닫습니다..."
    $_ | Stop-Process -Force
    Start-Sleep -Milliseconds 800
}

New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item $source (Join-Path $target "$AppName.exe") -Force
foreach ($extra in @('uninstall.ps1', '제거.bat', 'README.md')) {
    $p = Join-Path $here $extra
    if (Test-Path $p) { Copy-Item $p (Join-Path $target $extra) -Force }
}
Write-Output "설치 위치: $target"

$exePath = Join-Path $target "$AppName.exe"
$shell = New-Object -ComObject WScript.Shell

function New-Shortcut($path) {
    $sc = $shell.CreateShortcut($path)
    $sc.TargetPath = $exePath
    $sc.WorkingDirectory = $target
    $sc.Description = '마우스/키보드 매크로'
    $sc.IconLocation = "$exePath,0"
    $sc.Save()
    Write-Output "바로가기: $path"
}

New-Item -ItemType Directory -Force -Path $StartMenuDir | Out-Null
New-Shortcut (Join-Path $StartMenuDir "$AppName.lnk")
if (-not $NoDesktopShortcut) {
    New-Item -ItemType Directory -Force -Path $DesktopDir | Out-Null
    New-Shortcut (Join-Path $DesktopDir "$AppName.lnk")
}

# 설정 -> 앱 목록에 등록 (제거도 여기서 가능)
$size = [int]((Get-Item $exePath).Length / 1KB)
New-Item -Path $RegPath -Force | Out-Null
$uninstallCmd = 'powershell -NoProfile -ExecutionPolicy Bypass -File "' +
                (Join-Path $target 'uninstall.ps1') + '"'
$props = @{
    DisplayName     = $AppName
    DisplayVersion  = $Version
    Publisher       = $AppName
    DisplayIcon     = $exePath
    InstallLocation = $target
    UninstallString = $uninstallCmd
    NoModify        = 1
    NoRepair        = 1
    EstimatedSize   = $size
}
foreach ($k in $props.Keys) {
    New-ItemProperty -Path $RegPath -Name $k -Value $props[$k] -Force | Out-Null
}
Write-Output "앱 목록에 등록했습니다 (설정 > 앱에서 제거 가능)"

Write-Output ""
Write-Output "설치 완료. 시작 메뉴나 바탕화면에서 $AppName 을 실행하세요."
Write-Output "설정과 매크로는 $env:LOCALAPPDATA\$AppName 에 저장됩니다."
if (-not $Quiet) { Read-Host "엔터를 누르면 닫힙니다" }
