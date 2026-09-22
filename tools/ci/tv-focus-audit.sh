#!/usr/bin/env bash
#
# TV 焦点 / 返回键静态自测（P3-7 第 1、4 项） / TV focus & BACK static audit.
#
# Why this exists: P3-7's exit criteria are "遥控全流程无死角" and a BACK behaviour table for every
# screen. The card allows either `uiautomator` on the television or a self-check script, and the
# television is off limits for this round (`不要用电视`), so this is the script half. It is **static**:
# it reads the layouts and the Activity sources, and it never claims to know what a device would do.
#
# What it checks, per screen layout (`*/src/main/res/layout/activity_*.xml`):
#   1. the screen has somewhere for the remote to land — a focusable widget in the layout, or (search
#      is the one screen whose rows are built in code) a `requestFocus` call in its Activity. A screen
#      with neither is a "无焦点死角" by construction and fails the run.
#   2. it prints which BACK mechanism the screen uses, so the audit table in
#      `docs/05-过程记录/45-P3-7焦点与返回键打磨.md` can be re-read off one command. The *rules* are
#      pinned by unit tests (`BackHierarchyTest`, `PlayerBackPolicyTest`, `BrowseBackPolicyTest`,
#      `SearchBackPolicyTest`, `EpgBackPolicyTest`); this column only shows that a screen decided.
#
# Exit status: 0 = every screen can take focus, 1 = at least one screen cannot.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 2

printf '%-42s %-10s %-30s %s\n' "layout" "焦点控件" "BACK 机制" "Activity"
printf '%-42s %-10s %-30s %s\n' "------------------------------------------" "----------" "------------------------------" "--------"

screens=0
failures=0

for layout in $(git ls-files | grep -E '/res/layout/activity_[a-z0-9_]+\.xml$' | sort); do
    screens=$((screens + 1))
    base="$(basename "$layout" .xml)"             # activity_source_management
    stub="${base#activity_}"                      # source_management
    camel="$(printf '%s' "$stub" | awk -F_ '{ for (i = 1; i <= NF; i++) printf toupper(substr($i, 1, 1)) substr($i, 2) }')"
    activity="$(git ls-files | grep -E "/${camel}Activity\.kt$" | head -1)"

    if [ -z "$activity" ]; then
        printf '%-42s %-10s %-30s %s\n' "$base" "?" "找不到 Activity" "?"
        echo "tv-focus-audit: FAIL — $layout has no matching Activity class" >&2
        failures=$((failures + 1))
        continue
    fi

    focusable="$(grep -cE '<(Button|ImageButton|EditText|CheckBox|RadioButton|Spinner|SeekBar|Switch|ToggleButton|ListView|GridView|RecyclerView|NumberPicker)|android:focusable="true"' "$layout")"
    note=""

    if [ "$focusable" -eq 0 ]; then
        # No focusable widget in the XML: the screen must build its own and focus them itself.
        if grep -qE 'requestFocus' "$activity"; then
            note="代码构建"
        else
            printf '%-42s %-10s %-30s %s\n' "$base" "0" "—" "$(basename "$activity")"
            echo "tv-focus-audit: FAIL — $layout has no focusable widget and $activity never requests focus" >&2
            failures=$((failures + 1))
            continue
        fi
    fi

    # The audit table's "how does BACK get decided" column. The rules are unit-tested; this is only
    # "did the screen decide anything at all".
    if grep -qE 'OnBackPressedCallback|BackPolicy|BackHierarchy' "$activity"; then
        back="显式策略/回调"
    elif grep -qE 'setResult\(|finish\(\)' "$activity"; then
        back="框架 finish（带结果）"
    else
        back="框架默认（返回栈/根）"
    fi

    printf '%-42s %-10s %-30s %s\n' "$base" "$focusable $note" "$back" "$(basename "$activity")"
done

if [ "$failures" -gt 0 ]; then
    echo "tv-focus-audit: FAIL — $failures of $screens screens cannot take the remote's focus" >&2
    exit 1
fi

echo "tv-focus-audit: OK — $screens screen layouts, every one can take focus"
