#!/bin/bash
# LiveTranslate 全功能测试 v2 —— 每步 uiautomator 精确定位
set -u
ADB="adb"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }

# 工具：dump 并返回指定 text 的中心坐标 "x y"
get_center() {
    local text="$1"
    local xml="/sdcard/t_$$.xml"
    $ADB shell uiautomator dump $xml >/dev/null 2>&1
    local bounds
    bounds=$($ADB shell cat $xml 2>/dev/null | tr ">" "\n" | grep -oE "text=\"$text\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
    if [ -z "$bounds" ]; then echo ""; return 1; fi
    echo "$bounds" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/" | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

start_pipeline() {
    $ADB shell am force-stop com.example.livetranslate
    sleep 2
    $ADB logcat -c
    $ADB shell am start -n com.example.livetranslate/.MainActivity
    sleep 6
    $ADB shell input tap 540 884
    for i in $(seq 1 12); do
        C=$(get_center "Start now")
        if [ -n "$C" ]; then
            $ADB shell input tap $C
            return 0
        fi
        sleep 2
    done
    return 1
}

read_style() {  # 读存储样式字段
    $ADB shell "run-as com.example.livetranslate cat shared_prefs/lt_settings.xml" 2>/dev/null | grep -oE "$1&quot;:[a-z0-9.]+" | head -1 | cut -d: -f2
}

echo "========== LiveTranslate 全功能测试 v2 =========="

# 1. 服务 + 悬浮窗
echo "[1] 服务启动"
if start_pipeline; then ok "授权流程"; else bad "授权流程"; fi
sleep 12
OVERLAY=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
if [ "$OVERLAY" -ge 2 ]; then ok "悬浮窗显示 ($OVERLAY)"; else bad "悬浮窗未显示"; fi

# 2. 播放 + ASR
echo "[2] 播放测试语音"
$ADB shell input tap 540 1016
sleep 20
ASR_N=$($ADB logcat -d -s CaptureService:* 2>/dev/null | grep -cE "ASR \[")
if [ "$ASR_N" -gt 0 ]; then ok "ASR 识别 ($ASR_N 条)"; else bad "ASR 无输出"; fi

# 3. 二级菜单
echo "[3] 点击字幕条弹菜单"
$ADB logcat -c
$ADB shell input tap 500 800
sleep 3
if $ADB logcat -d -s OverlayMenu:* 2>/dev/null | grep -q "popup shown"; then
    ok "菜单弹出"
else
    bad "菜单未弹出"; FAIL=$((FAIL+1))
fi

# 4. 字号 + ×2
echo "[4] 字号调整"
C=$(get_center "+")
if [ -n "$C" ]; then
    $ADB shell input tap $C; sleep 1; $ADB shell input tap $C; sleep 1
    SZ=$(read_style font_size)
    if [ -n "$SZ" ] && [ "$SZ" != "0" ]; then ok "字号=$SZ"; else bad "字号未变"; fi
else
    bad "未找到 + 按钮"
fi

# 5. 字体
echo "[5] 字体选择"
C=$(get_center "默认")
if [ -n "$C" ]; then
    $ADB shell input tap $C; sleep 2
    C2=$(get_center "衬线 serif")
    if [ -n "$C2" ]; then
        $ADB shell input tap $C2; sleep 1
        FF=$(read_style font_family)
        if [ "$FF" = "serif" ]; then ok "字体=serif"; else bad "字体未变 ($FF)"; fi
    else bad "字体选项未出现"; fi
else bad "字体 Spinner 未找到"; fi

# 6. 粗体
echo "[6] 粗体"
C=$(get_center "粗体: 关")
if [ -n "$C" ]; then
    $ADB shell input tap $C; sleep 1
    B=$(read_style bold)
    if [ "$B" = "true" ]; then ok "粗体开"; else bad "粗体未变"; fi
else bad "粗体按钮未找到"; fi

# 7. 透明度滑块
echo "[7] 透明度"
SB=$($ADB shell uiautomator dump /sdcard/sb.xml >/dev/null 2>&1; $ADB shell cat /sdcard/sb.xml 2>/dev/null | tr ">" "\n" | grep -oE "class=\"android.widget.SeekBar\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
if [ -n "$SB" ]; then
    X1=$(echo "$SB" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/")
    Y1=$(echo "$SB" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/")
    X3=$(echo "$SB" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/")
    Y3=$(echo "$SB" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/")
    MY=$(( (Y1+Y3)/2 ))
    $ADB shell input swipe $(( (X1+X3)/2 )) $MY $(( X1 + (X3-X1)/4 )) $MY 500; sleep 1
    A=$(read_style alpha)
    if [ "$A" != "210" ] && [ -n "$A" ]; then ok "透明度=$A"; else bad "透明度未变"; fi
else bad "SeekBar 未找到"; fi

# 8. 圆角滑块（第二个 SeekBar）
SB2=$($ADB shell cat /sdcard/sb.xml 2>/dev/null | tr ">" "\n" | grep -oE "class=\"android.widget.SeekBar\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | sed -n 2p)
if [ -n "$SB2" ]; then
    X1=$(echo "$SB2" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/")
    Y1=$(echo "$SB2" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/")
    X3=$(echo "$SB2" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/")
    Y3=$(echo "$SB2" | sed -E "s/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/")
    MY=$(( (Y1+Y3)/2 ))
    $ADB shell input swipe $(( (X1+X3)/2 )) $MY $(( X1 + (X3-X1)*3/4 )) $MY 500; sleep 1
    CR=$(read_style corner_radius)
    if [ "$CR" != "14" ] && [ -n "$CR" ]; then ok "圆角=$CR"; else bad "圆角未变"; fi
else bad "圆角 SeekBar 未找到"; fi

# 9. 重置
echo "[9] 重置样式"
C=$(get_center "重置样式")
if [ -n "$C" ]; then
    $ADB shell input tap $C; sleep 1
    SZ=$(read_style font_size); A=$(read_style alpha); CR=$(read_style corner_radius)
    if [ "$SZ" = "0" ] && [ "$A" = "210" ] && [ "$CR" = "14" ]; then ok "重置生效"; else bad "重置失败"; fi
else bad "重置按钮未找到"; fi

# 10. 关闭菜单（点外部）
echo "[10] 关闭菜单"
$ADB shell input tap 100 500; sleep 2
N=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
echo "    overlay=$N (悬浮窗+systemui=2)"
if [ "$N" -le 2 ]; then ok "菜单已关闭"; else bad "菜单未关闭"; fi

# 11. resize
echo "[11] resize 手柄"
BEFORE=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -oE "Requested w=[0-9]+ h=[0-9]+" | head -1)
# 悬浮窗右下角 handle（窗口底部偏右）
Y_H=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -oE "Requested w=[0-9]+ h=[0-9]+" | head -1 | grep -oE "h=[0-9]+" | cut -d= -f2)
$ADB shell input swipe 980 $((Y_H-30)) 1080 $((Y_H+120)) 700; sleep 2
AFTER=$($ADB shell dumpsys window windows 2>/dev/null | grep -A4 "ty=APPLICATION_OVERLAY" | grep -oE "Requested w=[0-9]+ h=[0-9]+" | head -1)
echo "    $BEFORE → $AFTER"
if [ "$BEFORE" != "$AFTER" ]; then ok "尺寸已调整"; else bad "尺寸未变"; fi

# 12. ✕ 关闭
echo "[12] 悬浮窗 ✕"
B4=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
$ADB shell input tap 990 690; sleep 2
A4=$($ADB shell dumpsys window windows 2>/dev/null | grep -c "ty=APPLICATION_OVERLAY")
if [ "$A4" -lt "$B4" ]; then ok "悬浮窗关闭"; else bad "悬浮窗未关"; fi

echo ""
echo "========== 结果: ✅ $PASS 通过 / ❌ $FAIL 失败 =========="
