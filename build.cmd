@echo off
rem ====================================================================
rem  CodingRemoteAssistant - double-clickable packaging launcher (2.1.1)
rem  Self-contained: calls ONLY the project's own build scripts under
rem  packaging\ (build_native_exe.py / build_native_apk.py). No helper
rem  wrapper file is required. Keep this .cmd anywhere inside the project.
rem ====================================================================
setlocal EnableExtensions

rem --- locate project root: nearest ancestor with the build engine -------
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
:findroot
if exist "%ROOT%\packaging\build_native_exe.py" goto rootok
pushd "%ROOT%\.." 2>nul
set "NEW=%CD%"
popd
if /i "%NEW%"=="%ROOT%" goto rootbad
set "ROOT=%NEW%"
goto findroot
:rootbad
echo [ERROR] Could not find packaging\build_native_exe.py in "%~dp0" or any
echo         parent folder. Keep build.cmd inside the coding-mobile project.
echo.
pause
exit /b 1
:rootok
cd /d "%ROOT%"
set "CRA_ROOT=%ROOT%"

rem --- locate the project Python interpreter -----------------------------
set "PY=%ROOT%\.venv\Scripts\python.exe"
if not exist "%PY%" set "PY=%ROOT%\.local\python313\python.exe"
if not exist "%PY%" (
  echo [ERROR] Python interpreter not found under "%ROOT%"
  echo         expected: %ROOT%\.venv\Scripts\python.exe
  echo.
  pause
  exit /b 1
)

rem --- target: argument or interactive menu ------------------------------
set "TARGET=%~1"
if not "%TARGET%"=="" goto resolve

:menu
echo ==================================================
echo   CodingRemoteAssistant - packaging 2.1.1
echo ==================================================
echo   [1] both      Windows EXE + Android APK  (default)
echo   [2] windows   Windows EXE only
echo   [3] android   Android APK only
echo   [Q] quit
echo.
set "CHOICE="
set /p "CHOICE=Enter choice then press Enter: "
if not defined CHOICE set "TARGET=both" & goto resolve
if /i "%CHOICE%"=="1"       set "TARGET=both"    & goto resolve
if /i "%CHOICE%"=="2"       set "TARGET=windows" & goto resolve
if /i "%CHOICE%"=="3"       set "TARGET=android" & goto resolve
if /i "%CHOICE%"=="both"    set "TARGET=both"    & goto resolve
if /i "%CHOICE%"=="windows" set "TARGET=windows" & goto resolve
if /i "%CHOICE%"=="win"     set "TARGET=windows" & goto resolve
if /i "%CHOICE%"=="android" set "TARGET=android" & goto resolve
if /i "%CHOICE%"=="apk"     set "TARGET=android" & goto resolve
if /i "%CHOICE%"=="q"       exit /b 0
echo.
echo Invalid choice: %CHOICE%
echo.
goto menu

:resolve
set "DOWIN=0"
set "DOAPK=0"
if /i "%TARGET%"=="both"    set "DOWIN=1" & set "DOAPK=1"
if /i "%TARGET%"=="windows" set "DOWIN=1"
if /i "%TARGET%"=="win"     set "DOWIN=1"
if /i "%TARGET%"=="android" set "DOAPK=1"
if /i "%TARGET%"=="apk"     set "DOAPK=1"
if "%DOWIN%%DOAPK%"=="00" (
  echo [ERROR] Unknown target "%TARGET%". Use both ^| windows ^| android.
  echo.
  pause
  exit /b 1
)

echo.
echo Project : %ROOT%
echo Target  : %TARGET%
echo --------------------------------------------------
if "%DOWIN%"=="1" call :buildwin || goto failed
if "%DOAPK%"=="1" call :buildapk || goto failed
call :sums
echo --------------------------------------------------
echo [OK] build finished.
echo.
pause
exit /b 0

:failed
echo --------------------------------------------------
echo [FAIL] build stopped (exit code %errorlevel%).
echo.
pause
exit /b 1

rem --- Windows EXE: discover+cache verified base, then repack -----------
:buildwin
echo === building windows 2.1.1 ===
"%PY%" -c "import os,sys,hashlib,shutil;from pathlib import Path;root=Path(os.environ['CRA_ROOT']);sys.path.insert(0,str(root/'packaging'));import build_common as bc,build_native_exe as bx;cache=root/'.local'/'native-base-windows-1.6.23.exe';cands=[cache,root/'CodexMobileAssistant-Windows-1.6.23.exe',root.parent/'CodexMobileAssistant-Windows-1.6.23.exe',Path('D:/download/CodexMobileAssistant-Windows-1.6.23.exe')];base=next((c for c in cands if c.is_file() and hashlib.sha256(c.read_bytes()).hexdigest()==bc.EXPECTED_EXE),None);assert base is not None,'No verified Windows base CodexMobileAssistant-Windows-1.6.23.exe found under .local or project root';cache.parent.mkdir(parents=True,exist_ok=True);(shutil.copyfile(base,cache) if base.resolve()!=cache.resolve() else 0);print('[windows] base:',cache);bx.build(str(cache))"
exit /b %errorlevel%

rem --- Android APK: fixed-hash base fallback (no original needed) --------
:buildapk
echo === building android 2.1.1 ===
"%PY%" -c "import os,sys;from pathlib import Path;root=Path(os.environ['CRA_ROOT']);sys.path.insert(0,str(root/'packaging'));import build_native_apk as ba;print('[android] base: .local/native-base-2.0.0.apk (fixed-hash fallback)');ba.build(None)"
exit /b %errorlevel%

rem --- refresh dist\SHA256SUMS.txt for whatever 2.1.1 artifacts exist ----
:sums
echo === refresh dist\SHA256SUMS.txt ===
"%PY%" -c "import os,hashlib;from pathlib import Path;root=Path(os.environ['CRA_ROOT']);dist=root/'dist';names=['CodingRemoteAssistant-Windows-2.1.1.exe','CodingRemoteAssistant-Android-2.1.1.apk'];ent={n:hashlib.sha256((dist/n).read_bytes()).hexdigest() for n in names if (dist/n).is_file()};(dist/'SHA256SUMS.txt').write_text(''.join(ent[n]+'  '+n+chr(10) for n in sorted(ent)));[print(ent[n],'',n) for n in sorted(ent)]"
exit /b 0
