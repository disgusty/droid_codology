@echo off
rem Starts the droid_codology server on Windows: double-click this file, or run start-server.bat in a terminal.
rem Settings come from environment variables, see README.md (for example: set DC_PORT=8080).
cd /d "%~dp0"
set DC_NO_PAUSE=1
rem The Python launcher picks the newest Python 3; "python" is used only if it is 3.9 or newer.
where py >nul 2>nul && py -3 -c "import sys; sys.exit(sys.version_info < (3, 9))" >nul 2>nul && (py -3 droidcodology_server.py %* & goto end)
where python >nul 2>nul && python -c "import sys; sys.exit(sys.version_info < (3, 9))" >nul 2>nul && (python droidcodology_server.py %* & goto end)
echo Python 3.9 or newer is not installed. Install it from https://www.python.org/downloads/
echo (tick "Add python.exe to PATH" in the installer), then start this file again.
:end
echo.
echo The server has stopped. Press any key to close this window.
pause >nul