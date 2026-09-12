@echo off
setlocal
cd /d "%~dp0"
"%~dp0python\python.exe" "%~dp0launch.py" --doctor
pause
