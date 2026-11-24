#!/bin/sh
# Maven 包装脚本：绕过 Git Bash 下 mvn 启动脚本的 classpath 路径问题。
# 直接用 classworlds launcher 启动 Maven，避免 mvn 脚本被带空格/反斜杠的路径打坏。
#
# ⚠️ 路径必须写成**正斜杠**的绝对路径。历史坑：曾用 "$LOCALAPPDATA/maven/..."，
# 而 Git Bash 下该变量展开成 C:\Users\...\AppData\Local（反斜杠），
# 拼出的 jar 路径 ls 不到 ⇒ classworlds 类找不到 ⇒
# 报 ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
# （报错完全指不到真实原因）。
#
# JDK 固定 17：项目是 Spring Boot 3.2 / Java 17。用 JDK 21+ 编译会因
# Lombok 与旧版 ASM 组合出现大量「找不到符号」。
JAVA_HOME="<JDK_HOME>"
MAVEN_HOME="C:/Program Files/apache-maven-3.9.11"
PRJ="$(cd "$(dirname "$0")/.." && pwd)"
REPO="<MAVEN_REPO>"
exec "$JAVA_HOME/bin/java" -cp "$MAVEN_HOME/boot/plexus-classworlds-2.9.0.jar" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PRJ" \
  "-Dmaven.repo.local=$REPO" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.compiler.encoding=UTF-8" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
