@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem Tim Maven va JDK 21. Tren may nay co the dung ban kem IntelliJ IDEA.
where mvn.cmd >nul 2>&1
if errorlevel 1 (
    set "MVN=C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.7\plugins\maven\lib\maven3\bin\mvn.cmd"
    if not exist "!MVN!" (
        echo Khong tim thay Maven. Hay cai Maven hoac mo pom.xml trong IntelliJ.
        pause
        exit /b 1
    )
) else (
    set "MVN=mvn.cmd"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.7\jbr"
    if not exist "!JAVA_HOME!\bin\javac.exe" (
        echo Khong tim thay JDK 21. Hay dat bien JAVA_HOME.
        pause
        exit /b 1
    )
)

echo Dang mo giao dien quan ly server...
call "%MVN%" "-Dmaven.repo.local=%CD%\.m2" compile exec:java "-Dexec.mainClass=vn.lab.mail.ServerApp"
if errorlevel 1 pause
