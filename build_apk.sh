#!/bin/bash
# Build SpotPlayer debug APK. Proxy settings are read from the environment at run time.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=~/workspace/rea-test/jdk/jdk-21.0.12.1+1
PROPS=$(python3 -c "
import os, urllib.parse
u = urllib.parse.urlparse(os.environ.get('https_proxy',''))
if u.hostname:
    print(f'-Dhttps.proxyHost={u.hostname} -Dhttps.proxyPort={u.port} -Dhttp.proxyHost={u.hostname} -Dhttp.proxyPort={u.port}')
    if u.username: print(f'-Dhttps.proxyUser={urllib.parse.unquote(u.username)} -Dhttp.proxyUser={urllib.parse.unquote(u.username)}')
    if u.password: print(f'-Dhttps.proxyPassword={urllib.parse.unquote(u.password)} -Dhttp.proxyPassword={urllib.parse.unquote(u.password)}')
")
~/workspace/tools-gradle/gradle-8.7/bin/gradle $PROPS :app:assembleDebug --no-daemon --console=plain 2>&1 | tail -25
