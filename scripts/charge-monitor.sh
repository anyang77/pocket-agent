#!/system/bin/sh
# Read-only Xiaomi charging monitor. Run from an authorized root shell.
# Usage: sh charge-monitor.sh [interval_seconds=2] [samples=0]
usage() {
    echo "用法: sh $0 [间隔秒数=2] [采样次数=0]"
    echo '次数 0 表示持续循环，Ctrl+C 停止；需要 root 终端。'
}
case "${1:-}" in -h|--help) usage; exit 0 ;; esac
[ "$#" -le 2 ] || { usage >&2; exit 2; }
interval=${1:-2}
samples=${2:-0}
case "$interval" in ''|*[!0-9]*) usage >&2; exit 2 ;; esac
case "$samples" in ''|*[!0-9]*) usage >&2; exit 2 ;; esac
interval=$(printf '%s' "$interval" | sed 's/^0*//')
samples=$(printf '%s' "$samples" | sed 's/^0*//')
[ -n "$interval" ] || { usage >&2; exit 2; }
samples=${samples:-0}
base=/sys/class/power_supply
cp=/sys/devices/virtual/xm_power/cp_master
limit=/sys/devices/platform/soc/soc:mca_charge_interface/ichg_limit
if ! cat "$base/battery/voltage_now" >/dev/null 2>&1; then
    echo '无法读取节点。请先在终端执行 su，再用 sh 运行脚本。' >&2
    exit 1
fi
command -v awk >/dev/null 2>&1 || { echo '缺少 awk。' >&2; exit 1; }
read_node() { cat "$1" 2>/dev/null || :; }
trap 'printf "\n监测已停止。\n"; exit 0' INT TERM
printf '%s\n' \
    '只读监测：USB 与充电泵分开计算，不相加，避免重复计算。' \
    '输入参考取已读通道的较大值，不保证覆盖所有输入支路；不是插座侧功率。' \
    '低值/零值可能漏报或通道未工作；N/A 表示不可读。电池功率不是输入功率。' \
    'Ctrl+C 停止。'
count=0
while :; do
    now=$(date '+%F %T')
    uv=$(read_node "$base/usb/voltage_now")
    ui=$(read_node "$base/usb/current_now")
    cv=$(read_node "$cp/vbus")
    ci=$(read_node "$cp/ibus")
    bv=$(read_node "$base/battery/voltage_now")
    bi=$(read_node "$base/battery/current_now")
    temp=$(read_node "$base/battery/temp")
    cap=$(read_node "$base/battery/capacity")
    state=$(read_node "$base/battery/status")
    lim=$(read_node "$limit" | tr '\n' ';')
    # Standard power_supply uses uV/uA; this phone cp_master uses mV/mA.
    # Parenthesized printf arguments also work with Android system awk.
    awk -v now="$now" -v uv="$uv" -v ui="$ui" -v cv="$cv" -v ci="$ci" \
        -v bv="$bv" -v bi="$bi" -v temp="$temp" -v cap="$cap" \
        -v state="$state" -v lim="$lim" '
        function num(v) { return v ~ /^-?[0-9]+([.][0-9]+)?$/ }
        function abs(v) { return v < 0 ? -v : v }
        BEGIN {
            cap_text = num(cap) ? cap : "N/A"
            temp_text = num(temp) ? sprintf("%.1f°C", temp/10) : "N/A"
            state_text = state == "" ? "N/A" : state
            printf("\n[%s] 电量 %s%% | 温度 %s | %s\n", now, cap_text, temp_text, state_text)
            up = -1; cp = -1
            if (num(uv) && num(ui) && uv > 0 && ui >= 0) {
                up = uv*ui/1e12
                printf("USB 通道: %6.2f W (%6.3f V × %6.3f A)\n", up, uv/1e6, ui/1e6)
            } else print "USB 通道: N/A"
            if (num(cv) && num(ci) && cv > 0 && ci >= 0) {
                cp = cv*ci/1e6
                printf("充电泵通道: %6.2f W (%6.3f V × %6.3f A)\n", cp, cv/1e3, ci/1e3)
            } else print "充电泵通道: N/A"
            best = up > cp ? up : cp
            source = up > cp ? "USB" : "充电泵"
            if (best >= 1)
                printf("输入参考: 约 %.2f W（%s 通道，不是总输入保证值）\n", best, source)
            else print "输入参考: 未知（通道均为低值或不可读，不能认定实际只有零点几瓦）"
            if (num(bv) && num(bi))
                printf("电池侧 |P|: %.2f W (%.3f V / 电流 %+.3f A；非输入功率)\n", abs(bv*bi)/1e12, bv/1e6, bi/1e6)
            else print "电池侧 |P|: N/A"
            printf("限流记录: %s\n", (lim == "" ? "N/A" : lim))
        }' || { echo '格式化失败，已停止监测。' >&2; exit 1; }
    count=$((count + 1))
    if [ "$samples" -ne 0 ] && [ "$count" -ge "$samples" ]; then break; fi
    sleep "$interval" || exit 1
done
