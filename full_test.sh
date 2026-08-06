#!/bin/bash
# LiveTranslate 全功能自动化测试脚本（模拟器）
# 覆盖：服务启动 → 悬浮窗显示 → 二级菜单 → 透明度/字号/字体/圆角 → resize → 无障碍条
set -u
ADB="adb"
PASS=0; FAIL=0

log()  { echo "[TEST] $1"; }
ok()   { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ❌ $1"; }

# ---------- 1. 启动服务（轮询等待授权对话框，稳定可靠） ----------
start_pipeline() {
    $ADB shell am force-stop com.example.livetranslate
    sleep 2
    $ADB logcat -c
    $ADB shell am start -n com.example.livetranslate/.MainActivity
    sleep 6
    $ADB shell input tap 540 884   # ① 授权并开始
    # 轮询等待授权对话框
    for i in $(seq 1 12); do
        $ADB shell uiautomator dump /sdcard/dlg.xml >/dev/null 2>&1
        if $ADB shell cat /sdcard/dlg.xml 2>/dev/null | grep -q "Start now"; then
            $ADB shell input tap 842 1597
            return 0
        fi
        sleep 2
    done
    return 1
}

echo "========== LiveTranslate 全功能测试 =========="

# ---------- 1. 服务启动 ----------
log "1. 服务启动 + 悬浮窗"
if start_pipeline; then
    ok "授权流程"
else
    bad "授权流程（对话框未出现）"
fi
sleep 12
if $ADB shell dumpsys window windows 2>/dev/null | grep -q "ty=APPLICATION_OVERLAY"; then
    ok "悬浮窗已显示"
else
    bad "悬浮窗未显示"
fi

# ---------- 2. 播放 + 字幕更新 ----------
log "2. 播放测试语音 → 字幕更新"
$ADB shell input tap 540 1016
sleep 18
OUT=$($ADB logcat -d -s CaptureService:* 2>/dev/null | grep -cE "ASR \[")
if [ "$OUT" -gt 0 ]; then
    ok "ASR 识别 ($OUT 条)"
else
    bad "ASR 无输出"
fi

# 获取悬浮窗位置
WIN_INFO=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -E "mAttrs" | head -1)
log "   窗口: $WIN_INFO"

# ---------- 3. 二级菜单 ----------
log "3. 点击内容区弹出二级菜单"
$ADB logcat -c
$ADB shell input tap 500 800   # 悬浮窗内容区
sleep 3
if $ADB logcat -d -s OverlayMenu:* 2>/dev/null | grep -q "popup shown"; then
    ok "菜单弹出（asDropDown）"
else
    bad "菜单未弹出"
    $ADB logcat -d -s OverlayMenu:* 2>/dev/null | tail -3
fi
MENU_COUNT=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
log "   overlay 窗口数: $MENU_COUNT (期望 3: 悬浮窗+无障碍+菜单)"
if [ "$MENU_COUNT" -ge 3 ]; then
    ok "菜单窗口存在"
else
    bad "菜单窗口缺失"
fi

# ---------- 4. 字号调整（+ 按钮，点击两次） ----------
log "4. 菜单字号 +（2 次）"
SIZE_BEFORE=$($ADB shell "run-as com.example.livetranslate cat shared_prefs/lt_settings.xml" 2>/dev/null | grep -oE "font_size&quot;:[0-9.]+" | grep -oE "[0-9.]+")
log "   调整前 font_size=$SIZE_BEFORE"
# 菜单在悬浮窗下方：字号行 "+" 位置（估算：菜单顶 + 透明度区 + 字号行）
# 先尝试 (730, 悬浮窗底+110)
Y0=$(echo "$WIN_INFO" | grep -oE "\([0-9]+,[0-9]+\)" | head -1 | tr -d "()" | cut -d, -f2)
$ADB shell input tap 730 $((Y0 + 130))
sleep 1
$ADB shell input tap 730 $((Y0 + 130))
sleep 1
SIZE_AFTER=$($ADB shell "run-as com.example.livetranslate cat shared_prefs/lt_settings.xml" 2>/dev/null | grep -oE "font_size&quot;:[0-9.]+" | grep -oE "[0-9.]+")
log "   调整后 font_size=$SIZE_AFTER"
if [ "$SIZE_AFTER" != "$SIZE_BEFORE" ] && [ -n "$SIZE_AFTER" ]; then
    ok "字号已调整 ($SIZE_BEFORE → $SIZE_AFTER)"
else
    bad "字号未变化"
fi

# ---------- 5. 透明度滑块 ----------
log "5. 透明度调整（滑动到 100）"
$ADB shell input swipe 200 $((Y0 + 90)) 800 $((Y0 + 90)) 600
sleep 1
ALPHA=$($ADB shell "run-as com.example.livetranslate cat shared_prefs/lt_settings.xml" 2>/dev/null | grep -oE "alpha&quot;:[0-9]+" | grep -oE "[0-9]+")
log "   alpha=$ALPHA"
if [ "$ALPHA" != "210" ] && [ -n "$ALPHA" ]; then
    ok "透明度已调整 (210 → $ALPHA)"
else
    bad "透明度未变化"
fi

# ---------- 6. resize 手柄 ----------
log "6. resize 手柄拖动"
W_BEFORE=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -oE "Requested w=[0-9]+ h=[0-9]+" | head -1)
# 关闭菜单（点外部）
$ADB shell input tap 100 1600
sleep 1
# 悬浮窗右下角 handle 拖动
$ADB shell input swipe 980 1050 1080 1200 700
sleep 2
W_AFTER=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -oE "Requested w=[0-9]+ h=[0-9]+" | head -1)
log "   resize: $W_BEFORE → $W_AFTER"
if [ "$W_BEFORE" != "$W_AFTER" ]; then
    ok "尺寸已调整"
else
    bad "尺寸未变化"
fi

# ---------- 7. 无障碍字幕条 ----------
log "7. 无障碍字幕条（若已启用）"
A11Y=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ACCESSIBILITY_OVERLAY")
log "   无障碍窗口数: $A11Y"
if [ "$A11Y" -ge 1 ]; then
    ok "无障碍字幕条存在"
else
    log "   (无障碍服务未启用，跳过)"
fi

# ---------- 8. 关闭按钮 ----------
log "8. 悬浮窗 ✕ 关闭"
OVERLAY_BEFORE=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
$ADB shell input tap 990 690   # ✕ 位置（悬浮窗右上角）
sleep 2
OVERLAY_AFTER=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
log "   overlay: $OVERLAY_BEFORE → $OVERLAY_AFTER"
if [ "$OVERLAY_AFTER" -lt "$OVERLAY_BEFORE" ]; then
    ok "悬浮窗已关闭"
else
    bad "悬浮窗未关闭"
fi

echo ""
echo "========== 结果: ✅ $PASS 通过 / ❌ $FAIL 失败 =========="
