@echo off
setlocal enabledelayedexpansion

REM ReWear WMS (due-prog-03) — Windows Bootstrap Launcher
REM Detects Java 17+ or downloads a portable Adoptium Temurin JDK 17 into .\.runtime\jdk-17.

cd /d "%~dp0"

set "JAR_PATH=%~dp0target\due-prog-03.jar"
if not exist "%JAR_PATH%" if exist "%~dp0due-prog-03.jar" set "JAR_PATH=%~dp0due-prog-03.jar"
if not exist "%JAR_PATH%" (
    echo [ReWear WMS] JAR not found at %JAR_PATH%. Run 'mvn package' first.
    pause
    exit /b 1
)

set "JAVA_BIN="
if exist "%~dp0.runtime\jdk-17\bin\javaw.exe" (
    set "JAVA_BIN=%~dp0.runtime\jdk-17\bin\javaw.exe"
) else (
    for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
        set "VER=%%~v"
        for /f "delims=." %%m in ("!VER!") do (
            if %%m GEQ 17 set "JAVA_BIN=javaw"
        )
    )
)

if "%JAVA_BIN%"=="" (
    echo [ReWear WMS] Java 17+ not found. Downloading portable Temurin JDK 17...
    if not exist "%~dp0.runtime" mkdir "%~dp0.runtime"
    powershell -NoProfile -Command ^
        "$ProgressPreference = 'Continue'; " ^
        "$zip = '%~dp0.runtime\temurin17.zip'; " ^
        "Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $zip; " ^
        "$tmp = '%~dp0.runtime\extract'; " ^
        "if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }; " ^
        "Expand-Archive -Path $zip -DestinationPath $tmp -Force; " ^
        "$inner = Get-ChildItem -Directory $tmp | Select-Object -First 1; " ^
        "if (Test-Path '%~dp0.runtime\jdk-17') { Remove-Item -Recurse -Force '%~dp0.runtime\jdk-17' }; " ^
        "Move-Item $inner.FullName '%~dp0.runtime\jdk-17'; " ^
        "Remove-Item -Recurse -Force $tmp; Remove-Item -Force $zip"
    set "JAVA_BIN=%~dp0.runtime\jdk-17\bin\javaw.exe"
)

echo [ReWear WMS] Launching ReWear WMS...
start "" "%JAVA_BIN%" -jar "%JAR_PATH%"
endlocal
