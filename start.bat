@echo off
chcp 65001 >nul
setlocal
cd /d %~dp0

echo ========================================
echo   遇你 ^| culture 一键启动（双击运行）
echo   前端 Vue3 SPA :8080   后端 API :8081
echo ========================================

rem ---------- 1. MySQL ----------
powershell -NoProfile -Command "if (Test-NetConnection 127.0.0.1 -Port 3306 -InformationLevel Quiet) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
  echo [1/4] 启动 MySQL ...
  start "MySQL" /min "D:\me\SQL\MySQL\MySQL\bin\mysqld.exe" --defaults-file="D:\me\SQL\MySQL\MySQL\my.ini"
  timeout /t 8 /nobreak >nul
) else (
  echo [1/4] MySQL 已在运行
)

rem ---------- 2. Redis ----------
powershell -NoProfile -Command "if (Test-NetConnection 127.0.0.1 -Port 6379 -InformationLevel Quiet) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
  echo [2/4] 启动 Redis ...
  start "Redis" /min "D:\me\SQL\Redis\redis-server.exe" "D:\me\SQL\Redis\redis.windows.conf"
  timeout /t 3 /nobreak >nul
) else (
  echo [2/4] Redis 已在运行
)

rem ---------- 3. 后端（纯 REST API） ----------
powershell -NoProfile -Command "if (Test-NetConnection 127.0.0.1 -Port 8081 -InformationLevel Quiet) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
  if not exist "backend\target\culture-0.0.1-SNAPSHOT.jar" (
    echo [3/4] 构建后端 ...
    cd backend
    call mvn -q -DskipTests clean package
    cd ..
  )
  echo [3/4] 启动后端 ...
  start "CultureApp" /min java -jar "backend\target\culture-0.0.1-SNAPSHOT.jar"
  timeout /t 25 /nobreak >nul
) else (
  echo [3/4] 后端已在运行
)

rem ---------- 4. 前端（Vue3 构建产物 + 本机预览服，等价 Nginx） ----------
powershell -NoProfile -Command "if (Test-NetConnection 127.0.0.1 -Port 8080 -InformationLevel Quiet) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
  if not exist "web\node_modules" (
    echo [4/4] 安装前端依赖 ...
    cd web
    call npm install --no-audit --no-fund
    cd ..
  )
  if not exist "web\dist\front.html" (
    echo [4/4] 构建前端 ...
    cd web
    call npm run build
    cd ..
  )
  echo [4/4] 启动前端预览服 ...
  start "CultureWeb" /min cmd /c "cd web && node tools\serve-dist.mjs"
  timeout /t 3 /nobreak >nul
) else (
  echo [4/4] 前端预览服已在运行
)

echo.
echo 启动完成：
echo   前台 http://localhost:8080/
echo   后台 http://localhost:8080/admin
echo   接口 http://localhost:8081/api/home
echo   生产部署：Nginx 静态托管 web\dist（见 web\nginx.conf.example）
start http://localhost:8080/
pause
