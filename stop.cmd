@echo off
setlocal EnableExtensions

rem ============================================================================
rem  Double-click to stop everything. Data volumes are kept.
rem
rem    stop.cmd            stop containers, release the Docker/WSL VM
rem    stop.cmd purge      also delete data volumes -- sessions, knowledge,
rem                        console config all gone, cannot be undone
rem
rem  Same as: start.cmd down / start.cmd reset. This file exists because the
rem  common case should not require remembering an argument.
rem  Keep this file ASCII-only: cmd.exe parses .cmd with the OEM codepage.
rem ============================================================================

cd /d "%~dp0"

set "ACTION=%~1"
if "%ACTION%"=="" goto :down
if /i "%ACTION%"=="down" goto :down
if /i "%ACTION%"=="stop" goto :down
if /i "%ACTION%"=="purge" goto :purge
if /i "%ACTION%"=="reset" goto :purge
if /i "%ACTION%"=="help" goto :help
if /i "%ACTION%"=="-h" goto :help

echo.
echo   Unknown argument: %ACTION%
goto :help

:help
echo.
echo   Usage: stop.cmd [down ^| purge]
echo.
echo     down    stop containers + WSL VM, keep data (default)
echo     purge   stop and delete data volumes (asks first)
echo.
pause
exit /b 2

:down
set "PSARGS=down"
goto :run

:purge
set "PSARGS=reset"
goto :run

:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stack.ps1" %PSARGS%
if errorlevel 1 (
  echo.
  echo   [x] Failed. The reason is printed above by the script.
  echo       Docker daemon running? Try opening Docker Desktop first.
  echo.
  pause
  exit /b 1
)

rem Hold the window so "stopped" stays visible after a double-click.
echo.
echo   Press any key to close this window.
pause
exit /b 0
