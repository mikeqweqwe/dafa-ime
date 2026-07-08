#!/usr/bin/env bash
# 模擬器冒煙測試：驗證鍵盤崩潰修復
# 硬指標：(1) 鍵盤召喚後 full deploy 編出 app 專屬目錄的 rime/build/default.yaml
#         (2) 無 StackOverflow/FATAL/native crash (3) 進程存活
# dafa.15 起 user_data_dir=app 專屬目錄（Android/data/…/files/rime，與 shared 相同）。
# 開 App 只會進設定精靈、不觸發 RIME 初始化；必須聚焦輸入框叫出鍵盤。
set -x

adb root || true
sleep 3
adb install app/build/outputs/apk/debug/*-debug.apk
adb shell pm grant com.tumuyan.trime android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant com.tumuyan.trime android.permission.WRITE_EXTERNAL_STORAGE || true
adb logcat -c || true

# install Success 後系統要幾秒才把 IME 註冊進 InputMethodManager，太早 enable 會
# 「Unknown input method」整場測試白跑 → 先等註冊、set 完驗證真的選上
IME_ID=com.tumuyan.trime/com.osfans.trime.TrimeImeService
for i in $(seq 1 12); do
  if adb shell ime list -a 2>/dev/null | grep -q com.tumuyan.trime; then break; fi
  sleep 5
done
IME_SELECTED=no
for i in $(seq 1 6); do
  adb shell ime enable "$IME_ID" || true
  adb shell ime set "$IME_ID" || true
  if adb shell settings get secure default_input_method | grep -q com.tumuyan.trime; then
    IME_SELECTED=yes
    break
  fi
  sleep 3
done
echo "IME_SELECTED=$IME_SELECTED"
adb shell settings put secure show_ime_with_hard_keyboard 1

# 開簡訊 app 並點輸入框 → 召喚鍵盤 → TrimeImeService onCreate → Config.get → full deploy
adb shell am start -a android.intent.action.SENDTO -d sms:5551234 || true
sleep 10
SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
W=${SIZE%x*}
H=${SIZE#*x}
adb shell input tap $((W * 45 / 100)) $((H * 94 / 100)) || true
sleep 5

# 等 RIME full deploy 編譯 build/（首次叫鍵盤時同步進行）；先查後睡，崩潰就早退
BUILD_FILE=/storage/emulated/0/Android/data/com.tumuyan.trime/files/rime/build/default.yaml
DEPLOY_OK=no
for i in $(seq 1 24); do
  if adb shell "test -s $BUILD_FILE && echo BUILD_OK" | grep -q BUILD_OK; then
    DEPLOY_OK=yes
    break
  fi
  if adb logcat -d 2>/dev/null | grep -A2 -E "StackOverflowError|FATAL EXCEPTION|Fatal signal" | grep -qiE "tumuyan|osfans"; then
    break
  fi
  sleep 5
done
# 補最後一次檢查：build 若在最後一輪 sleep 期間才出現，別誤判 no
if [ "$DEPLOY_OK" = no ] && adb shell "test -s $BUILD_FILE && echo BUILD_OK" | grep -q BUILD_OK; then
  DEPLOY_OK=yes
fi

adb exec-out screencap -p > smoke-keyboard.png || true

# 收集證據
adb shell "ls -laR /storage/emulated/0/rime" > smoke-rime-user-dir.txt 2>&1 || true
adb shell "ls -laR /storage/emulated/0/Android/data/com.tumuyan.trime/files/rime" > smoke-rime-shared-dir.txt 2>&1 || true
adb shell "ps -A | grep -i tumuyan" > smoke-ps.txt 2>&1 || true
adb shell dumpsys input_method > smoke-ime-dump.txt 2>&1 || true
adb logcat -d > smoke-logcat-full.txt 2>&1 || true
grep -iE "tumuyan|TrimeIme|AndroidRuntime|FATAL|StackOverflow|Fatal signal|Rime|deploy|Config|maintenance" smoke-logcat-full.txt > smoke-ime-log.txt 2>&1 || true

# 硬指標判定（含 native crash）
CRASH=no
# 都 scope 到本 app（process=tumuyan、stack frame=osfans），別讓系統其他 process 的崩潰誤紅
if grep -A2 "StackOverflowError" smoke-logcat-full.txt | grep -qiE "tumuyan|osfans"; then CRASH=stackoverflow; fi
if grep -A2 "FATAL EXCEPTION" smoke-logcat-full.txt | grep -qiE "tumuyan|osfans"; then CRASH=fatal; fi
if grep "Fatal signal" smoke-logcat-full.txt | grep -qi tumuyan; then CRASH=native; fi
ALIVE=no
grep -q tumuyan smoke-ps.txt && ALIVE=yes
{
  echo "IME_SELECTED=$IME_SELECTED"
  echo "DEPLOY_OK=$DEPLOY_OK"
  echo "CRASH=$CRASH"
  echo "PROCESS_ALIVE=$ALIVE"
  echo "BUILD_DIR_LISTING:"
  adb shell "ls -la /storage/emulated/0/Android/data/com.tumuyan.trime/files/rime/build" 2>&1 || true
} | tee smoke-verdict.txt

# 硬指標不過就讓 CI 紅：exit 0 會讓部署失敗/崩潰靜默過關（artifacts 上傳有 if: always() 不受影響）
if [ "$DEPLOY_OK" = yes ] && [ "$CRASH" = no ] && [ "$ALIVE" = yes ]; then
  exit 0
fi
echo "SMOKE FAILED: DEPLOY_OK=$DEPLOY_OK CRASH=$CRASH ALIVE=$ALIVE"
exit 1
