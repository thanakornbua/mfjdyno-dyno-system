@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  dyno-ui startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and DYNO_UI_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\dyno-ui.jar;%APP_HOME%\lib\jackson-annotations-2.18.2.jar;%APP_HOME%\lib\jackson-core-2.18.2.jar;%APP_HOME%\lib\jackson-databind-2.18.2.jar;%APP_HOME%\lib\javafx-controls-21.0.4.jar;%APP_HOME%\lib\javafx-controls-21.0.4-linux.jar;%APP_HOME%\lib\javafx-graphics-21.0.4.jar;%APP_HOME%\lib\javafx-graphics-21.0.4-linux.jar;%APP_HOME%\lib\javafx-base-21.0.4.jar;%APP_HOME%\lib\javafx-base-21.0.4-linux.jar;%APP_HOME%\lib\sign-7.2.5.jar;%APP_HOME%\lib\forms-7.2.5.jar;%APP_HOME%\lib\svg-7.2.5.jar;%APP_HOME%\lib\styled-xml-parser-7.2.5.jar;%APP_HOME%\lib\layout-7.2.5.jar;%APP_HOME%\lib\barcodes-7.2.5.jar;%APP_HOME%\lib\pdfa-7.2.5.jar;%APP_HOME%\lib\kernel-7.2.5.jar;%APP_HOME%\lib\commons-csv-1.10.0.jar;%APP_HOME%\lib\io-7.2.5.jar;%APP_HOME%\lib\bcpkix-jdk15on-1.70.jar;%APP_HOME%\lib\bcutil-jdk15on-1.70.jar;%APP_HOME%\lib\bcprov-jdk15on-1.70.jar;%APP_HOME%\lib\font-asian-7.2.5.jar;%APP_HOME%\lib\hyph-7.2.5.jar;%APP_HOME%\lib\commons-7.2.5.jar;%APP_HOME%\lib\slf4j-api-1.7.36.jar


@rem Execute dyno-ui
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %DYNO_UI_OPTS%  -classpath "%CLASSPATH%" com.dyno.fx.OperatorConsoleApp %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable DYNO_UI_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%DYNO_UI_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
