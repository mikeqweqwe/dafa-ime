#!/usr/bin/env bash
# 模擬器冒煙測試：驗證鍵盤崩潰修復
# 硬指標：(1) 鍵盤召喚後 full deploy 編出 /sdcard/rime/build/default.yaml
#         (2) 無 StackOverflow/FATAL (3) 進程存活
# 注意：RIME build 產物在 userDataDir=/sdcard/rime，assets 在 app 外部目錄，兩者不同！
# 開 App 只會進設定精靈、不觸發 RIME 初始化；必須聚焦輸入框叫出鍵盤。
set -x

adb root || true
sleep 3
adb install app/build/outputs/apk/debug/*-debug.apk
adb shell pm grant com.tumuyan.trime android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant com.tumuyan.trime android.permission.WRITE_EXTERNAL_STORAGE || true
adb logcat -c || true
adb shell ime enable com.tumuyan.trime/com.osfans.trime.TrimeImeService
adb shell ime set com.tumuyan.trime/com.osfans.trime.TrimeImeService
adb shell settings put secure show_ime_with_hard_keyboard 1

# 開簡訊 app 並點輸入框 → 召喚鍵盤 → TrimeImeService onCreate → Config.get → full deploy
adb shell am start -a android.intent.action.SENDTO -d sms:5551234 || true
sleep 10
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | cut -dx -f1)
H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | cut -dx -f2)
adb shell input tap $((W * 45 / 100)) $((H * 94 / 100)) || true
sleep 5

# 等 RIME full deploy 編譯 build/（首次叫鍵盤時同步進行），輪詢 user 目錄的 build
# dafa.15 起 user_data_dir=app 專屬目錄（與 shared 相同），build 在其下
BUILD_FILE=/storage/emulated/0/Android/data/com.tumuyan.trime/files/rime/build/default.yaml
DEPLOY_OK=no
for i in $(seq 1 24); do
  sleep 5
  if adb shell "test -s $BUILD_FILE && echo BUILD_OK" | grep -q BUILD_OK; then
    DEPLOY_OK=yes
    break
  fi
done

adb exec-out screencap -p > smoke-keyboard.png || true

# 收集證據
adb shell "ls -laR /storage/emulated/0/rime" > smoke-rime-user-dir.txt 2>&1 || true
adb shell "ls -laR /storage/emulated/0/Android/data/com.tumuyan.trime/files/rime" > smoke-rime-shared-dir.txt 2>&1 || true
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
  adb shell "ls -la /storage/emulated/0/Android/data/com.tumuyan.trime/files/rime/build" 2>&1 || true
} > smoke-verdict.txt
cat smoke-verdict.txt
exit 0
