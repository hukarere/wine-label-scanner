#!/usr/bin/env bash
# Run DeviceScanTest on a phone and record the app's peak memory (VmHWM) per run.
# Usage: tools/device-test.sh [ONNX|MLKIT] [img1.jpg,img2.jpg]   (default: ONNX, all fixtures)
# ADB defaults to the user's adb server tunnelled to port 5038 (see docs/PROJECT_NOTES.md).
# DET_MAX_PIXELS=N forces the PP-OCR detector pixel cap (default: the app's free-memory budget).
set -u
ADB=${ADB:-"$HOME/Android/Sdk/platform-tools/adb -P 5038"}
ENGINE=${1:-ONNX}
IMAGES=${2:-}
method=$(echo "$ENGINE" | tr 'A-Z' 'a-z')
extra=(); [ -n "$IMAGES" ] && extra=(-e images "$IMAGES")
[ -n "${DET_MAX_PIXELS:-}" ] && extra+=(-e detMaxPixels "$DET_MAX_PIXELS")
$ADB logcat -c
$ADB shell am instrument -w -e engines "$ENGINE" "${extra[@]}" \
    -e class "winelabel.app.DeviceScanTest#$method" \
    winelabel.app.test/androidx.test.runner.AndroidJUnitRunner > /tmp/device-test.$$ 2>&1 &
inst=$!
peak=0
while kill -0 $inst 2>/dev/null; do
    pid=$($ADB shell pidof winelabel.app 2>/dev/null | tr -d '\r')
    if [ -n "$pid" ]; then
        hwm=$($ADB shell "grep VmHWM /proc/$pid/status" 2>/dev/null | awk '{print $2}')
        [ -n "$hwm" ] && [ "$hwm" -gt "$peak" ] && peak=$hwm
    fi
    sleep 1
done
cat /tmp/device-test.$$; rm -f /tmp/device-test.$$
echo "PEAK_RSS_MB $((peak / 1024))"
$ADB logcat -d -s WineLabelTest:I WineLabel:E AndroidRuntime:E | grep -v '^---------'
$ADB logcat -d -b main,system | grep -E 'exited due to signal|am_low_memory' | tail -3
