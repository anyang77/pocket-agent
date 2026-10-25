#!/usr/bin/env sh
#
# Compact source-tree report for Android sh / Termux.
#

set -eu

root="."
large_kb=512
top=10

usage() {
    cat <<'EOF'
Usage: project_report.sh [options] [directory]

Options:
  --large-kb N   report files larger than N KiB (default: 512)
  --top N        number of rows in largest-file tables (default: 10)
  -h, --help     show this help
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --large-kb)
            [ "$#" -ge 2 ] || { echo "missing value for --large-kb" >&2; exit 2; }
            large_kb=$2
            shift 2
            ;;
        --top)
            [ "$#" -ge 2 ] || { echo "missing value for --top" >&2; exit 2; }
            top=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            echo "unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
        *)
            root=$1
            shift
            ;;
    esac
done

[ -d "$root" ] || {
    echo "directory not found: $root" >&2
    exit 1
}

root=$(cd "$root" && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/project-report.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

find "$root" \
    \( -type d \( \
        -name .git -o -name .gradle -o -name .build -o \
        -name .idea -o -name .kotlin -o -name __pycache__ -o \
        -name build -o -name node_modules -o -name vendor \
    \) -prune \) -o -type f -print |
while IFS= read -r file; do
    case "$file" in
        *.c|*.cc|*.cpp|*.h|*.hpp|*.java|*.js|*.jsx|*.kt|*.kts|*.md|*.py|*.sh|*.ts|*.tsx)
            ;;
        *)
            continue
            ;;
    esac
    [ -f "$file" ] || continue
    lines=$(wc -l < "$file" 2>/dev/null | tr -d ' ')
    size=$(wc -c < "$file" 2>/dev/null | tr -d ' ')
    [ -n "$lines" ] && [ -n "$size" ] || continue
    printf '%s\t%s\t%s\n' "$lines" "$size" "${file#"$root"/}" >> "$tmp/files.tsv"
done

touch "$tmp/files.tsv"
files=$(wc -l < "$tmp/files.tsv" | tr -d ' ')
total_lines=$(awk -F '\t' '{ total += $1 } END { print total + 0 }' "$tmp/files.tsv")
total_bytes=$(awk -F '\t' '{ total += $2 } END { print total + 0 }' "$tmp/files.tsv")

markers=0
while IFS="$(printf '\t')" read -r _ignored_lines _ignored_bytes path; do
    count=$(grep -E -o 'TODO|FIXME|HACK' "$root/$path" 2>/dev/null | wc -l | tr -d ' ')
    markers=$((markers + count))
done < "$tmp/files.tsv"

large_limit=$((large_kb * 1024))
large_count=$(awk -F '\t' -v limit="$large_limit" '$2 >= limit { count++ } END { print count + 0 }' "$tmp/files.tsv")

human_size() {
    awk -v bytes="$1" '
        BEGIN {
            if (bytes >= 1048576) printf "%.1f MiB", bytes / 1048576
            else if (bytes >= 1024) printf "%.1f KiB", bytes / 1024
            else printf "%d B", bytes
        }
    '
}

printf '项目：%s\n' "$root"
printf '源文件：%s\n' "$files"
printf '总行数：%s\n' "$total_lines"
printf '总大小：%s\n' "$(human_size "$total_bytes")"
printf 'TODO/FIXME/HACK：%s\n' "$markers"
printf '超大文件（≥ %s KiB）：%s\n' "$large_kb" "$large_count"

if [ "$large_count" -gt 0 ]; then
    printf '\n超大文件：\n'
    awk -F '\t' -v limit="$large_limit" -v top="$top" '
        $2 >= limit {
            printf "%d\t%s\t%s\n", $2, $1, $3
        }
    ' "$tmp/files.tsv" |
    sort -nr |
    head -n "$top" |
    while IFS="$(printf '\t')" read -r size lines path; do
        printf '  %10s  %7s 行  %s\n' "$(human_size "$size")" "$lines" "$path"
    done
fi

printf '\n最大文件：\n'
sort -k2,2nr "$tmp/files.tsv" |
head -n "$top" |
while IFS="$(printf '\t')" read -r lines size path; do
    printf '  %10s  %7s 行  %s\n' "$(human_size "$size")" "$lines" "$path"
done

printf '\n按扩展名统计行数：\n'
awk -F '\t' '
    {
        path = $3
        dot = match(path, /\.[^.\/]+$/)
        suffix = dot ? substr(path, dot) : "(none)"
        totals[suffix] += $1
    }
    END {
        for (suffix in totals) print totals[suffix] "\t" suffix
    }
' "$tmp/files.tsv" |
sort -nr |
while IFS="$(printf '\t')" read -r lines suffix; do
    printf '  %-10s %9s\n' "$suffix" "$lines"
done
