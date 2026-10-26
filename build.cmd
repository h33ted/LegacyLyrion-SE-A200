@echo off
rem Windows version of build.sh: builds dist\LegacyLyrion-Aino.jar and LegacyLyrion-A200.jar (+ .jad).
rem Needs JDK 11 or newer on PATH; the jars in lib\ are downloaded on the first build (curl ships with Windows 10+).
setlocal
cd /d "%~dp0"
if "%VER%"=="" set VER=0.7.3
if not exist lib mkdir lib
set M2=https://repo1.maven.org/maven2
if not exist lib\ecj-4.6.1.jar curl.exe -fsSL -o lib\ecj-4.6.1.jar %M2%/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar || exit /b 1
if not exist lib\cldcapi11-2.0.4.jar curl.exe -fsSL -o lib\cldcapi11-2.0.4.jar %M2%/org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar || exit /b 1
if not exist lib\midpapi20-2.0.4.jar curl.exe -fsSL -o lib\midpapi20-2.0.4.jar %M2%/org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar || exit /b 1
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes build\stubs dist
rem Compile-time stubs for APIs the phone provides (JSR-256 sensors, Nokia UI DeviceControl); never packaged.
java -jar lib\ecj-4.6.1.jar -source 1.3 -target cldc1.1 -nowarn -bootclasspath lib\cldcapi11-2.0.4.jar;lib\midpapi20-2.0.4.jar -d build\stubs stubs\javax\microedition\sensor\*.java stubs\com\nokia\mid\ui\*.java || exit /b 1
java -jar lib\ecj-4.6.1.jar -source 1.3 -target cldc1.1 -nowarn -bootclasspath lib\cldcapi11-2.0.4.jar;lib\midpapi20-2.0.4.jar;build\stubs -d build\classes src\ll\*.java || exit /b 1
copy /y res\icon.png build\classes\icon.png >nul
(
echo Manifest-Version: 1.0
echo MIDlet-1: LegacyLyrion, /icon.png, ll.LegacyLyrion
echo MIDlet-Name: LegacyLyrion
echo MIDlet-Vendor: h33ted
echo MIDlet-Version: %VER%
echo MIDlet-Icon: /icon.png
echo MicroEdition-Configuration: CLDC-1.1
echo MicroEdition-Profile: MIDP-2.0
) > build\MANIFEST.MF
rem Phone-friendly zip (see tools\Pack.java)
java tools\Pack.java dist\LegacyLyrion.jar build\MANIFEST.MF build\classes || exit /b 1
for %%A in (dist\LegacyLyrion.jar) do set SIZE=%%~zA
rem One jar fits every A200 phone; two labelled copies (Aino 240x432 touch, A200 240x320 keypad).
call :target Aino "Sony Ericsson Aino, 240x432 touch"
call :target A200 "W995, C905, W705 and other 240x320 phones"
del dist\LegacyLyrion.jar
echo built dist\LegacyLyrion-Aino.jar and LegacyLyrion-A200.jar (%SIZE% bytes)
goto :eof

:target
copy /y dist\LegacyLyrion.jar dist\LegacyLyrion-%1.jar >nul
findstr /v "Manifest-Version" build\MANIFEST.MF > dist\LegacyLyrion-%1.jad
echo MIDlet-Description: %~2>> dist\LegacyLyrion-%1.jad
echo MIDlet-Jar-Size: %SIZE%>> dist\LegacyLyrion-%1.jad
echo MIDlet-Jar-URL: LegacyLyrion-%1.jar>> dist\LegacyLyrion-%1.jad
goto :eof
