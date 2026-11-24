#!/bin/sh
# Gradle 包装脚本：绕过 bin/gradle 对 cygpath 的依赖(Git Bash 无 cygpath)，
# 直接用 Studio 自带的 JBR 启动 Gradle。需先下载 Gradle 8.10 到:
#   %LOCALAPPDATA%/gradle/gradle-8.10  (scripts 已含下载说明)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
GRADLE_HOME="$LOCALAPPDATA/gradle/gradle-8.10"
CLASSPATH="$GRADLE_HOME/lib/gradle-gradle-cli-main-8.10.jar"
AGENT="$GRADLE_HOME/lib/agents/gradle-instrumentation-agent-8.10.jar"
exec "$JAVA_HOME/bin/java" -Xmx64m -Xms64m "-javaagent:$AGENT" \
  -Dorg.gradle.appname=gradle \
  -classpath "$CLASSPATH" \
  org.gradle.launcher.GradleMain "$@"
