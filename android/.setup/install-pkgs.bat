@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot
call "D:\AndroidSDK\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=D:\AndroidSDK "platforms;android-35" "build-tools;35.0.0"
