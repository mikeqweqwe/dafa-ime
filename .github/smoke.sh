#!/usr/bin/env bash
# 模擬器冒煙測試：驗證 dafa.13 崩潰修復
# 硬指標：(1) 首啟動 full deploy 編出 build/default.yaml (2) 無 StackOverflow/FATAL (3) 進程存活
set -x

adb root || true
sleep 3
adb install app/build/outputs/apk/debug/*-debug.apk
adb logcat -c || true
adb shell ime enable com.tumuyan.trime/com.osfans.trime.TrimeImeService
adb shell ime set com.tumuyan.trime/com.osfans.trime.TrimeImeService
adb shell settings put secure show_ime_with_hard_keyboard 1

# 直接開大發 App：走與鍵盤相同的 Config.get→prepareRime→full deploy 初始化路徑
adb shell monkey -p com.tumuyan.trime -c android.intent.category.LAUNCHER 1

# 等 RIME full deploy 編譯 build/（大詞庫可能要數十秒），輪詢 build/default.yaml
RIME_DIR=/storage/emulated/0/Android/data/com.tumuyan.trime/files/rime
DEPLOY_OK=no
for i in $(seq 1 36); do
  sleep 5
  if adb shell "test -s $RIME_DIR/build/default.yaml && echo BUILD_OK" | grep -q BUILD_OK; then
    DEPLOY_OK=yes
    break
  fi
done

# 叫出鍵盤：開訊息輸入框（best effort，截圖用）
adb shell am start -a android.intent.action.SENDTO -d sms:5551234 || true
sleep 8
adb exec-out screencap -p > smoke-sms.png || true
adb shell input tap 540 1650 || true
sleep 10
adb exec-out screencap -p > smoke-keyboard.png || true

# 收集證據
adb shell "ls -laR $RIME_DIR" > smoke-rime-dir.txt 2>&1 || true
adb shell "ps -A | grep -i tumuyan" > smoke-ps.txt 2>&1 || true
adb shell dumpsys input_method > smoke-ime-dump.txt 2>&1 || true
adb logcat -d > smoke-logcat-full.txt 2>&1 || true
grep -iE "tumuyan|TrimeIme|AndroidRuntime|FATAL|StackOverflow|Rime|deploy|Config|maintenance" smoke-logcat-full.txt > smoke-ime-log.txt 2>&1 || true

# 硬指標判定
CRASH=no
if grep -q "StackOverflowError" smoke-logcat-full.txt; then CRASH=stackoverflow; fi
if grep -A2 "FATAL EXCEPTION" smoke-logcat-full.txt | grep -qi tumuyan; then CRASH=fatal; fi
ALIVE=no
adb shell "ps -A | grep -i tumuyan" | grep -q tumuyan && ALIVE=yes
{
  echo "DEPLOY_OK=$DEPLOY_OK"
  echo "CRASH=$CRASH"
  echo "PROCESS_ALIVE=$ALIVE"
  echo "BUILD_DIR_LISTING:"
  adb shell "ls -la $RIME_DIR/build" 2>&1 || true
} > smoke-verdict.txt
cat smoke-verdict.txt
exit 0
