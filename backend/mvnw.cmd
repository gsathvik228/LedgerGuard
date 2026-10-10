@REM Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
@echo off
setlocal
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
pushd "%~dp0"
"%JAVA_EXE%" -classpath "%~dp0.mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%~dp0." org.apache.maven.wrapper.MavenWrapperMain %*
set "MVNW_EXIT=%ERRORLEVEL%"
popd
exit /b %MVNW_EXIT%
