@echo off
setlocal EnableExtensions

rem ============================================================================
rem  Double-click this file to start everything. No command line needed.
rem
rem    start.cmd            up: app + PostgreSQL (stub model, works offline)
rem    start.cmd ai         add in-container Ollama (~6 GB first pull)
rem    start.cmd edge       add Caddy in front: https + gzip + security headers
rem    start.cmd down       stop containers, keep data volumes
rem    start.cmd status     show containers + health
rem    start.cmd logs       follow logs (Ctrl+C to quit)
rem    start.cmd psql       open psql inside the database container
rem    start.cmd reset      stop and delete volumes (asks for confirmation)
rem
rem  All logic lives in scripts\stack.ps1 (messages are Chinese there).
rem  This file only: picks the action, calls PowerShell, keeps the window open.
rem  Keep this file ASCII-only -- cmd.exe parses .cmd with the OEM codepage,
rem  a UTF-8 Chinese character here can swallow the next line and break parsing.
rem ============================================================================

cd /d "%~dp0"

set "ACTION=%~1"
if "%ACTION%"=="" goto :up
if /i "%ACTION%"=="up" goto :up
if /i "%ACTION%"=="ai" goto :up_ai
if /i "%ACTION%"=="edge" goto :up_edge
if /i "%ACTION%"=="down" goto :ps_down
if /i "%ACTION%"=="stop" goto :ps_down
if /i "%ACTION%"=="status" goto :ps_status
if /i "%ACTION%"=="logs" goto :ps_logs
if /i "%ACTION%"=="reset" goto :ps_reset
if /i "%ACTION%"=="psql" goto :ps_psql
if /i "%ACTION%"=="help" goto :help
if /i "%ACTION%"=="-h" goto :help

echo.
echo   Unknown argument: %ACTION%
goto :help

:help
echo.
echo   Usage: start.cmd [up ^| ai ^| edge ^| down ^| status ^| logs ^| psql ^| reset]
echo.
echo     up      start app + PostgreSQL (default)
echo     ai      also start Ollama in a container (big download)
echo     edge    add Caddy reverse proxy: https, gzip, security headers
echo     down    stop containers, keep data
echo     status  show container state
echo     logs    follow logs
echo     psql    open a database shell
echo     reset   stop and delete data volumes
echo.
pause
exit /b 2

:up
set "PSARGS=up"
set "OPEN_BROWSER=1"
goto :run

:up_ai
set "PSARGS=up -Ai"
set "OPEN_BROWSER=1"
goto :run

:up_edge
set "PSARGS=up -Edge"
set "OPEN_BROWSER=1"
set "EDGE=1"
goto :run

:ps_down
set "PSARGS=down"
goto :run

:ps_status
set "PSARGS=status"
goto :run

:ps_logs
set "PSARGS=logs"
goto :run

:ps_reset
set "PSARGS=reset"
goto :run

:ps_psql
set "PSARGS=psql"
goto :run

:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stack.ps1" %PSARGS%
if errorlevel 1 (
  echo.
  echo   [x] Failed. The reason is printed above by the script.
  echo       Logs:  start.cmd logs
  echo       Docker daemon running? Try opening Docker Desktop first.
  echo.
  pause
  exit /b 1
)

if not "%OPEN_BROWSER%"=="1" goto :end_ok

call :read_port
set "BASE=http://localhost:%APP_PORT%"
if defined EDGE set "BASE=https://localhost"
echo.
echo   Room        %BASE%/room/
echo   Knowledge   %BASE%/knowledge/
echo   Console     %BASE%/internal
if not "%OP_PASS%"=="" echo   Login       %OP_USER% / %OP_PASS%
echo.
start "" "%BASE%/room/"

rem Hold the window after a start: the internal token and ports printed above
rem are needed later. Other actions (status/down) exit straight away.
echo   Press any key to close this window (services keep running).
pause
exit /b 0

:end_ok
exit /b 0

rem Port comes from .env, which stack.ps1 creates on first run -- read it after.
rem A UTF-8 BOM would make the first key read as "\ufeffAPP_PORT"; the default
rem below covers that case.
:read_port
set "APP_PORT=8099"
set "OP_USER=admin"
set "OP_PASS="
if not exist ".env" goto :eof
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
  if /i "%%a"=="APP_PORT" if not "%%b"=="" set "APP_PORT=%%b"
  if /i "%%a"=="PSYCH_OPERATOR_USER" if not "%%b"=="" set "OP_USER=%%b"
  if /i "%%a"=="PSYCH_OPERATOR_PASSWORD" if not "%%b"=="" set "OP_PASS=%%b"
)
goto :eof
