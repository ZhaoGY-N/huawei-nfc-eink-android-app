@echo off
setlocal EnableExtensions EnableDelayedExpansion

if not defined JAVA_HOME (
  echo [ERROR] Please set JAVA_HOME to a full JDK 17+ directory.
  exit /b 2
)
if not defined ANDROID_HOME (
  echo [ERROR] Please set ANDROID_HOME to the Android SDK directory to be bootstrapped.
  exit /b 2
)

pushd "%~dp0" || exit /b 2
set "QPOLLLAB_ROOT=%~dp0"
set "PY_RUN="

rem Prefer an existing Python 3.9+ installation.
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>nul
  if not errorlevel 1 set "PY_RUN=py -3"
)
if not defined PY_RUN (
  where python >nul 2>nul
  if not errorlevel 1 (
    python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,9) else 1)" >nul 2>nul
    if not errorlevel 1 set "PY_RUN=python"
  )
)

rem If Python is absent or too old, download the official embeddable runtime.
if not defined PY_RUN (
  set "PY_DIR=%~dp0tools\python-3.12.10-embed-amd64"
  set "PY_EXE=%~dp0tools\python-3.12.10-embed-amd64\python.exe"
  if not exist "!PY_EXE!" (
    echo Python 3.9+ not found. Downloading the official Python 3.12.10 embeddable runtime...
    powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ErrorActionPreference='Stop'; $dir=Join-Path $env:QPOLLLAB_ROOT 'tools\python-3.12.10-embed-amd64'; $zip=Join-Path $env:TEMP 'qpoll-python-3.12.10-embed-amd64.zip'; New-Item -ItemType Directory -Force -Path $dir | Out-Null; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath $dir -Force; Remove-Item -LiteralPath $zip -Force"
    if errorlevel 1 (
      echo [ERROR] Could not bootstrap Python. Check PowerShell and network access to python.org.
      popd
      exit /b 2
    )
  )
  if not exist "!PY_EXE!" (
    echo [ERROR] Python bootstrap completed without python.exe.
    popd
    exit /b 2
  )
  set "PY_RUN=!PY_EXE!"
)

if "%PY_RUN%"=="py -3" (
  py -3 "%~dp0build.py" %*
) else if "%PY_RUN%"=="python" (
  python "%~dp0build.py" %*
) else (
  "%PY_RUN%" "%~dp0build.py" %*
)
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
