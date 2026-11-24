@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ============================================================
echo  AudioBook 工程环境配置 (JBR 当 JDK / 免单独装 JDK)
echo ============================================================

REM ---- 1. JDK: 优先复用 Android Studio 自带的 JBR(OpenJDK21, 含 javac) ----
echo.
echo [1/4] JDK
set "JBR=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%JBR%\bin\javac.exe" (
    echo      OK : 使用 Studio 内置 JBR (OpenJDK 21) 作为 JDK
    set "JAVA_HOME=%JBR%"
) else (
    where java >nul 2>nul
    if %errorlevel%==0 (
        echo      OK : 系统已存在 java
    ) else (
        echo      MISSING - 安装 Microsoft.OpenJDK.21 ...
        winget install -e --id Microsoft.OpenJDK.21
        for /d %%p in ("%ProgramFiles%\Microsoft\jdk-21*") do set "JAVA_HOME=%%p"
    )
)
if defined JAVA_HOME (
    setx JAVA_HOME "!JAVA_HOME!" >nul
    echo      JAVA_HOME=!JAVA_HOME!
)

REM ---- 2. Maven (无本地包, 走 winget; 也可用 scripts\mvn.sh 直接二进制方式) ----
echo.
echo [2/4] Maven  (构建 AudioBookServer; 工程无 mvnw 包装器)
where mvn >nul 2>nul
if %errorlevel%==0 (
    echo      OK : mvn present
) else (
    echo      MISSING - 安装 Apache.Maven ...
    winget install -e --id Apache.Maven
)

REM ---- 3. Android SDK ----
echo.
echo [3/4] Android SDK  (compileSdk 34, for AudioBookPlayer)
set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
if exist "%ANDROID_SDK%\platforms\android-34" (
    echo      OK : %ANDROID_SDK% (API 34 present)
    goto :sdk_done
)
if exist "%ProgramFiles%\Android\Android Studio\bin\studio64.exe" (
    echo      MISSING - 通过 Studio 的 sdkmanager 安装 SDK 组件 ...
    set "SDKMGR=%ProgramFiles%\Android\Android Studio\cmdline-tools\latest\bin\sdkmanager.bat"
    if not exist "!SDKMGR!" (
        echo         未找到内置 sdkmanager, 请打开 Android Studio -> SDK Manager 手动安装:
        echo             Android 14 (API 34) + Build-Tools + Platform-Tools
        goto :sdk_done
    )
    echo y | "!SDKMGR!" --sdk_root="%ANDROID_SDK%" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
) else (
    echo [!] 未检测到 Android Studio, 请先安装 Android Studio 或 command-line-tools。
)
:sdk_done
setx ANDROID_HOME "%ANDROID_SDK%" >nul
REM 写回 local.properties
set "LOCAL_PROP=E:\study_audiobook\WorkSpace_AudioBook\AudioBook\AudioBookPlayer\local.properties"
if exist "%LOCAL_PROP%" (
    >"%LOCAL_PROP%" echo sdk.dir=<ANDROID_SDK>
    echo      [+] 已写入 %LOCAL_PROP%
)

REM ---- 4. 运行期依赖提示 ----
echo.
echo [4/4] 运行期依赖 (编译不需要, 运行后端需要)
echo      MySQL   : 执行 AudioBookServer\init_database.sql (建 audiobook_db)
echo      Redis   : localhost:6379
echo      MOSS-TTS: http://localhost:18083 (可选语音合成)

echo.
echo ============================================================
echo  构建命令:
echo    cd AudioBookManager ^&^& npm install ^&^& npm run build
echo    cd AudioBookWeb     ^&^& npm install ^&^& npm run build
echo    cd AudioBookServer  ^&^& mvn clean package   (或用 scripts\mvn.sh)
echo    cd AudioBookPlayer  ^&^& gradlew assembleDebug
echo  TIP: 若报 "failed to resolve @audiobook/shared", 运行: npm install --force
echo ============================================================
echo  IMPORTANT: 安装完成后请打开【新的终端】, 使 PATH/JAVA_HOME 生效再构建。
echo ============================================================
pause
