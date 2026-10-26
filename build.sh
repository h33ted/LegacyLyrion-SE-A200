#!/bin/sh
# Builds dist/LegacyLyrion-Aino.jar and LegacyLyrion-A200.jar (+ .jad) for Sony Ericsson A200
# phones (CLDC 1.1 / MIDP 2.0).
# Needs: JDK 11 or newer (runs the compiler and tools/Pack.java) and these jars in lib/, which are
# downloaded from Maven Central on the first build if missing:
#   ecj-4.6.1.jar      https://repo1.maven.org/maven2/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar
#   cldcapi11-2.0.4.jar https://repo1.maven.org/maven2/org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar
#   midpapi20-2.0.4.jar https://repo1.maven.org/maven2/org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar
# ecj's "cldc1.1" target writes Java 1.1-era class files with the StackMap data CLDC needs,
# so no separate preverify step is required.
set -e
cd "$(dirname "$0")"
VER=${VER:-0.7.2}
# Test builds: LL_SERVER=192.168.1.20:9000 sh build.sh  -> dist-test/ with the server preset
OUT=dist
M2=https://repo1.maven.org/maven2
mkdir -p lib
for J in org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar \
         org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar \
         org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar; do
  F=lib/$(basename $J)
  if [ ! -f "$F" ]; then echo "downloading $F"; curl -fsSL -o "$F" "$M2/$J"; fi
done
if [ -n "$LL_SERVER" ]; then OUT=dist-test; fi
rm -rf build $OUT && mkdir -p build/classes build/stubs $OUT
# Compile-time stubs for APIs the phone provides (JSR-256 sensors); never packaged.
java -jar lib/ecj-4.6.1.jar -source 1.3 -target cldc1.1 -nowarn \
  -bootclasspath lib/cldcapi11-2.0.4.jar:lib/midpapi20-2.0.4.jar \
  -d build/stubs stubs/javax/microedition/sensor/*.java
java -jar lib/ecj-4.6.1.jar -source 1.3 -target cldc1.1 -nowarn \
  -bootclasspath lib/cldcapi11-2.0.4.jar:lib/midpapi20-2.0.4.jar:build/stubs \
  -d build/classes src/ll/*.java
cp res/icon.png build/classes/icon.png
cat > build/MANIFEST.MF <<MF
Manifest-Version: 1.0
MIDlet-1: LegacyLyrion, /icon.png, ll.LegacyLyrion
MIDlet-Name: LegacyLyrion
MIDlet-Vendor: h33ted
MIDlet-Version: $VER
MIDlet-Icon: /icon.png
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MF
if [ -n "$LL_SERVER" ]; then echo "LL-Server: $LL_SERVER" >> build/MANIFEST.MF; fi
# Old Java ME installers reject wrapped (continued) manifest lines; keep every line short.
if awk 'length > 70' build/MANIFEST.MF | grep -q .; then
  echo "ERROR: manifest line over 70 characters"; exit 1
fi
# Phone-friendly zip (see tools/Pack.java); the JDK jar tool's output is not accepted everywhere.
java tools/Pack.java $OUT/LegacyLyrion.jar build/MANIFEST.MF build/classes
SIZE=$(wc -c < $OUT/LegacyLyrion.jar | tr -d ' ')
# One jar fits every A200 phone (the layout follows the screen); two labelled copies make it
# clear which to pick. Aino: 240x432 touch. A200: the 240x320 keypad phones.
for T in Aino A200; do
  if [ $T = Aino ]; then D="Sony Ericsson Aino, 240x432 touch"; else D="W995, C905, W705 and other 240x320 phones"; fi
  cp $OUT/LegacyLyrion.jar $OUT/LegacyLyrion-$T.jar
  { cat build/MANIFEST.MF | grep -v '^Manifest-Version'; echo "MIDlet-Description: $D"; echo "MIDlet-Jar-Size: $SIZE"; echo "MIDlet-Jar-URL: LegacyLyrion-$T.jar"; } > $OUT/LegacyLyrion-$T.jad
done
rm $OUT/LegacyLyrion.jar
echo "built $OUT/LegacyLyrion-Aino.jar and LegacyLyrion-A200.jar ($SIZE bytes)"
