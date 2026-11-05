#!/bin/bash
# Runs on the emulator: install, open a test video, drive the buttons, collect screenshots + logs.
OUT=/tmp/uitest; mkdir -p $OUT
python3 -m http.server 8000 --directory /tmp </dev/null >/dev/null 2>&1 &
HTTP_PID=$!
sleep 45
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb push /tmp/t.mp4 /sdcard/Download/t.mp4
adb push /tmp/cassette.aac /sdcard/Download/cassette.aac
adb push /tmp/album.mp3 /sdcard/Download/album.mp3
adb shell pm grant com.mymusic.cutter android.permission.POST_NOTIFICATIONS || true
adb logcat -G 16M || true
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/t.mp4 -t video/mp4 -n com.mymusic.cutter/.MainActivity
sleep 12
python3 ci/drive.py $OUT 2>> $OUT/drive_err.txt || echo "driver failed" >> $OUT/drive.txt
adb logcat -d > $OUT/logcat.txt
grep -E "MMP|ANR|FATAL|AndroidRuntime|VideoFrameProcessor|ExoPlayerImpl" $OUT/logcat.txt > $OUT/logcat_short.txt || true
kill $HTTP_PID 2>/dev/null
exit 0
