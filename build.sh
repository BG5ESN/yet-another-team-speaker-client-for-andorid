#!/bin/bash
# 编译裁剪版 APK（低内存/低并发模式，适配 4核/7.5G/零swap）
cd /home/harold/.hermes/workspace/ts6droid_cn || exit 1
export JAVA_HOME="$HOME/.local/opt/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

echo "=== 环境 ==="
java -version 2>&1 | head -1
echo "内存: $(free -h | sed -n 2p)"
echo "JVM 参数: $(grep '^org.gradle.jvmargs' gradle.properties)"
echo "并发: workers.max=$(grep '^org.gradle.workers.max' gradle.properties | cut -d= -f2)"
echo
echo "=== 开始编译 $(date '+%H:%M:%S') ==="
nice -n 10 ./gradlew --no-daemon --console=plain assembleDebug -x buildRustLibs 2>&1
rc=$?
echo
echo "=== 结束 rc=$rc $(date '+%H:%M:%S') ==="
ls -la app/build/outputs/apk/debug/ 2>/dev/null
exit $rc
