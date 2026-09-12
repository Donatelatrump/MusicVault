@echo off
setlocal
cd /d "%~dp0"
if not exist "python\pythonw.exe" (
 echo Falta el runtime portable. Ejecuta primero packaging\build_portable.py.
 pause
 exit /b 1
)
start "" "%~dp0python\pythonw.exe" "%~dp0launch.py" %*
