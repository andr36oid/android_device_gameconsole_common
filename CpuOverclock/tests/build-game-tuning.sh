#!/bin/bash
# Targeted Android 11 resource/Java/DEX validation, no platform/image build.
set -euo pipefail
common=${1:?common tree}
out=${2:?output directory}
app=${3:?GameComfort or CpuOverclock}
case "$app" in GameComfort|CpuOverclock) ;; *) exit 2;; esac
src=/andr36oid/src
jdk=$src/prebuilts/jdk/jdk11/linux-x86/bin
mkdir -p "$out/gen" "$out/classes" "$out/dex"
python3 - "$common/$app/res" "$out/res" <<'PY'
import pathlib,shutil,sys,xml.etree.ElementTree as E
source=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);shutil.copytree(source,out)
n=0
for d in sorted(out.glob('values*')):
 seen=set()
 for f in sorted(d.glob('*.xml')):
  t=E.parse(f);root=t.getroot()
  for e in list(root):
   key=(e.tag,e.get('name'))
   if key in seen:root.remove(e);n+=1
   else:seen.add(key)
  t.write(f,encoding='utf-8',xml_declaration=True)
print('Private staged duplicate entries removed:',n)
PY
"$src/out/host/linux-x86/bin/aapt2" compile --dir "$out/res" -o "$out/res.zip"
"$src/out/host/linux-x86/bin/aapt2" compile --dir "$common/HintBar/res" -o "$out/hints.zip"
"$src/out/host/linux-x86/bin/aapt2" link -I "$src/out/target/common/obj/APPS/framework-res_intermediates/package-export.apk" --manifest "$common/$app/AndroidManifest.xml" --extra-packages org.andr36oid.hints --auto-add-overlay -o "$out/$app.apk" --java "$out/gen" -R "$out/res.zip" -R "$out/hints.zip" --min-sdk-version 30 --target-sdk-version 30
rg --files "$common/$app/src" "$common/HintBar/src" "$out/gen" -g '*.java' > "$out/sources"
cp="$src/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar:$src/out/target/common/obj/JAVA_LIBRARIES/core-oj.com.android.art.release_intermediates/classes.jar:$src/out/target/common/obj/JAVA_LIBRARIES/core-libart.com.android.art.release_intermediates/classes.jar"
"$jdk/javac" -proc:none -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$cp" -d "$out/classes" @"$out/sources"
echo 'javac exit=0 (Android 11)'
"$jdk/jar" cf "$out/classes.jar" -C "$out/classes" .
"$jdk/java" -cp "$src/prebuilts/r8/r8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$src/prebuilts/sdk/30/public/android.jar" --output "$out/dex" "$out/classes.jar"
(cd "$out/dex" && zip -q -0 "$out/$app.apk" classes.dex)
stat -c '%s %n' "$out/$app.apk"
