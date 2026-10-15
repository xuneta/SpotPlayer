#!/bin/bash
# Manual APK build: aapt2 + javac + R8 (shrink) / D8 (fallback) + apksigner.
# v5.0 adds Media3 ExoPlayer to the dex inputs.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=~/workspace/rea-test/jdk/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH
BT=$HOME/android-sdk/build-tools/34.0.0
AJ=$HOME/android-sdk/platforms/android-34/android.jar
OUT=build_manual
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes $OUT/dex
echo '[1/6] aapt2 compile'
$BT/aapt2 compile --dir app/src/main/res -o $OUT/res.zip
echo '[2/6] aapt2 link'
mkdir -p app/src/main/assets
$BT/aapt2 link -o $OUT/unsigned.apk -I $AJ --manifest app/src/main/AndroidManifest.xml -A app/src/main/assets $OUT/res.zip --java $OUT/gen
echo '[3/6] javac'
M3CP=$(ls "$PWD"/libs-media3/classes/*.jar | tr '\n' ':')"$PWD"/libs-media3/dl/guava-33.3.1-android.jar:"$PWD"/libs-media3/dl/annotation-1.6.0.jar:"$PWD"/libs-media3/dl/annotation-experimental-1.3.1.aar
# annotation-experimental classes jar also exists under classes/
javac --release 17 -cp "$AJ:$M3CP" -d $OUT/classes $(find app/src/main/java $OUT/gen -name '*.java')
echo '[4/6] dex (R8 shrink, D8 fallback)'
jar cf $OUT/classes.jar -C $OUT/classes .
DEXFILES="$OUT/classes.jar $(ls "$PWD"/libs-media3/classes/*.jar) $PWD/libs-media3/dl/guava-33.3.1-android.jar $PWD/libs-media3/dl/annotation-1.6.0.jar"
cat > $OUT/keep.pro <<'EOF'
-keep class com.arman.spotplayer.** { *; }
-dontwarn **
-dontnote **
EOF
R8OK=1
{
  PG=""
  for f in "$PWD"/libs-media3/proguard/*.txt; do PG="$PG --pg-conf $f"; done
  # shellcheck disable=SC2086
  java -cp $HOME/workspace/tools-gradle/r8b.jar com.android.tools.r8.R8 \
    --release --min-api 26 --lib "$AJ" --output $OUT/dex \
    --pg-conf $OUT/keep.pro $PG --pg-map-output $OUT/mapping.txt \
    $DEXFILES
} || R8OK=0
if [ "$R8OK" = "0" ]; then
  echo '[4b/6] R8 failed -> D8 fallback (no shrink, bigger APK)'
  # shellcheck disable=SC2086
  java -cp $HOME/workspace/tools-gradle/r8b.jar com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 26 --output $OUT/dex $DEXFILES
fi
echo '[5/6] pack + align'
python3 -c "import zipfile; z=zipfile.ZipFile('$OUT/unsigned.apk','a',zipfile.ZIP_DEFLATED); z.write('$OUT/dex/classes.dex','classes.dex'); z.close()"
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
echo '[6/6] sign'
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
    -dname 'CN=Android Debug,O=Android,C=US' > /dev/null 2>&1
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out SpotPlayer.apk $OUT/aligned.apk
$BT/apksigner verify --print-certs SpotPlayer.apk | head -3
ls -la SpotPlayer.apk
