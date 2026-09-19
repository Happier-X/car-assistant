@echo off
REM ============================================================
REM  车机助手 - 一键构建并安装到车机
REM
REM  用法：
REM    build.bat              构建 debug APK
REM    build.bat release      构建 release APK
REM    build.bat install      构建并 adb 安装 + 授予关键权限
REM ============================================================

setlocal

REM Android Studio 自带的 JBR（如果没有配置 JAVA_HOME 就用它）
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

set TASK=assembleDebug
if /I "%1"=="release" set TASK=assembleRelease
if /I "%1"=="install" set TASK=assembleDebug

echo.
echo [1/2] 构建 %TASK% ...
call gradlew.bat %TASK% || goto :error

set APK=app\build\outputs\apk\debug\app-debug.apk
if /I "%1"=="release" set APK=app\build\outputs\apk\release\app-release.apk

echo.
echo 构建完成: %APK%

if /I not "%1"=="install" goto :done

echo.
echo [2/2] 安装到车机 ...
adb install -r "%APK%" || goto :error

echo.
echo 授予关键权限（无 root 开 WiFi 的后路）...
adb shell pm grant com.carassistant android.permission.WRITE_SECURE_SETTINGS
echo   WRITE_SECURE_SETTINGS 已授予

echo.
echo 完成。接下来请在车机上：
echo   1. 打开「车机助手」App
echo   2. 设置 -^> 开启无障碍服务
echo   3. 加入电池优化白名单
echo   4. 启动项 -^> 添加应用 -^> 选中「亿连」
goto :done

:error
echo.
echo 构建或安装失败，请检查上面的错误信息。
exit /b 1

:done
endlocal