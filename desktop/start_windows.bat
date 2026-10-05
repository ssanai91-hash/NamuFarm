@echo off
setlocal
set PYTHONUTF8=1
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)"
  if errorlevel 1 goto python_missing
  py -3 -m venv .venv
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -c "import namufarm_desktop.app" >nul 2>&1
if errorlevel 1 (
  ".venv\Scripts\python.exe" -m pip install -e .
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -m namufarm_desktop
if errorlevel 1 goto failed
exit /b 0
:python_missing
echo Python 3.11 or newer with the Windows Python Launcher is required.
pause
exit /b 1
:failed
echo Setup or startup failed. See the message above and desktop/README.md.
pause
exit /b 1
