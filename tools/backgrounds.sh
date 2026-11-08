#!/bin/bash
# Copies your images in backgrounds-images/ to the TV: Home shows one of them at random, blurred, as its background, a
# new one every few minutes (Backgrounds.java). Only new or changed files are sent; images deleted here are deleted on
# the TV too, and the app drops its cached copy. They stay yours: never in the repository or the APK.
#
# They go to VYRO's own folder (Android/data/io.vyro.tv/files/Backgrounds), which the app reads with no permission:
# /sdcard/Backgrounds needs READ_MEDIA_IMAGES, which Android can take back (it did on a TCL, Android 14). Where adb
# can't write the app's folder (Android 11, Fire OS 8), they go to /sdcard/Backgrounds with that permission granted.
cd "$(dirname "$0")/.."
A="adb -s ${TV:-192.168.1.69:5555}"
DIR=/sdcard/Android/data/io.vyro.tv/files/Backgrounds
if ! $A shell "mkdir -p $DIR && touch $DIR/.probe && rm $DIR/.probe" 2>/dev/null; then
  DIR=/sdcard/Backgrounds
  $A shell mkdir -p $DIR
  $A shell pm grant io.vyro.tv android.permission.READ_MEDIA_IMAGES 2>/dev/null       # Android 13+
  $A shell pm grant io.vyro.tv android.permission.READ_EXTERNAL_STORAGE 2>/dev/null   # Android 8 to 12
fi
on_tv="$($A shell ls $DIR 2>/dev/null | tr -d '\r')"
for f in backgrounds-images/*.{jpg,jpeg,png,webp,JPG,JPEG,PNG,WEBP}; do
  [ -f "$f" ] || continue
  n="$(basename "$f")"
  echo "$on_tv" | grep -qxF "$n" || { $A push "$f" "$DIR/$n" >/dev/null && echo "sent $n"; }
done
echo "$on_tv" | while read -r n; do [ -n "$n" ] && [ ! -f "backgrounds-images/$n" ] && $A shell rm "$DIR/$n" && echo "removed $n"; done
echo "Background images up to date ($DIR)."
