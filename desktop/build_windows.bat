@echo off
setlocal
set PYTHONUTF8=1
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  py -3 -m venv .venv
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -m pip install -e . "pyinstaller>=6.11,<7"
if errorlevel 1 goto failed
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed --name NamuFarm-PC --collect-data namufarm_desktop run_desktop.py
if errorlevel 1 goto failed
echo Built: %cd%\dist\NamuFarm-PC.exe
pause
exit /b 0
:failed
echo Build failed. See the message above.
pause
exit /b 1

