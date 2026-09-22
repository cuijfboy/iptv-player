#!/usr/bin/env bash
#
# 通知渠道守护 / notification-channel guard. See docs/05-过程记录/53-NEW3通知渠道崩溃修复.md.
#
# Why this exists: `NEW-20260922-003` was an S1 that no test could catch, because nothing was
# broken in the code that ran — `RefreshNotifications.ensureChannel()` simply had **no caller**, so
# the channel `refresh` never existed and `startForeground` killed the process. A channel that is
# defined but never created is invisible to the compiler, to lint and to a "does it build" check;
# it only shows up when a device posts the notification. So it gets a static gate.
#
# Scope: Kotlin **main** sources only (`git ls-files -- '*/src/main/kotlin/*.kt'`). Tests may post
# notifications freely; build output and untracked scratch files are not what ships.
#
# Rules (each one is shaped to be quiet on this repo and to have fired on the real defect):
#   channel-not-created      a file that builds a `NotificationChannel` must also call
#                            `createNotificationChannel(...)`. A channel that is only constructed is
#                            never registered with the platform.
#   ensurer-never-called     a `fun ensure*Channel(...)` must be called from **another** main source
#                            file, through its declaring type (`RefreshNotifications.ensureChannel(…)`).
#                            This is the exact NEW-3 shape (definition + no caller). Both halves are
#                            deliberate: another file, because an overload calling itself would
#                            otherwise satisfy the rule while the channel is still never created; the
#                            qualifier, because the playback side defines the *same method name* in a
#                            different object, and a bare name match would let one ensurer cover the
#                            other. The `PlaybackNotifications` + `PlaybackService` pairing passes.
#   foreground-without-ensure a file that calls `setForeground(` / `startForeground(` must call an
#                            `ensure*Channel(...)` in the same file (or create a channel itself), so
#                            "the channel is established before the foreground state is asked for"
#                            is visible at the call site.
#
# Comments are stripped before matching (`/* … */` and `// …`) so prose about `startForeground(...)`
# does not count as a call — `PlaybackServiceLifecycle.kt`'s KDoc does exactly that.
#
# Exit status: 0 = no finding, 1 = at least one finding, 2 = not a git work tree.
set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "notification-channel-audit: not inside a git work tree" >&2
    exit 2
}
cd "$repo_root" || exit 2

# Strip block comments first, then line comments. Perl ships with macOS and the CI image.
strip_comments() {
    perl -0777 -pe 's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' "$1"
}

files=()
while IFS= read -r f; do
    [ -n "$f" ] && files+=("$f")
done < <(git ls-files -- '*/src/main/kotlin/*.kt')

findings=0
report() {
    printf 'notification-channel-audit: [%s] %s\n' "$1" "$2"
    findings=$((findings + 1))
}

creates_channel() { grep -Eq 'NotificationChannel\(|NotificationChannelCompat\.Builder\(' <<<"$1"; }
calls_create() { grep -Eq 'createNotificationChannel(Compat)?\(' <<<"$1"; }
goes_foreground() { grep -Eq '(^|[^A-Za-z])(setForeground|startForeground)\(' <<<"$1"; }

# The ensurer names a file *defines* and the qualified names it *calls* — the call list is per file,
# so "defined here, called nowhere else" is answerable without a quadratic re-scan.
defines_ensurer() { grep -Eq '(^|[[:space:]])fun[[:space:]]+ensure[A-Za-z]*Channel\(' <<<"$1"; }
defined_names() { grep -oE '(^|[[:space:]])fun[[:space:]]+ensure[A-Za-z]*Channel\(' <<<"$1" | grep -oE 'ensure[A-Za-z]*Channel'; }
called_names() {
    grep -E 'ensure[A-Za-z]*Channel\(' <<<"$1" | grep -vE '(^|[[:space:]])fun[[:space:]]' \
        | grep -oE '([A-Za-z_][A-Za-z0-9_]*\.)?ensure[A-Za-z]*Channel' || true
}
# The declaring type of the ensurer in this file (`object RefreshNotifications {` → `RefreshNotifications`).
declaring_type() {
    grep -oE '^[[:space:]]*(internal[[:space:]]+|private[[:space:]]+|public[[:space:]]+)?(object|class|interface)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' <<<"$1" \
        | head -1 | grep -oE '[A-Za-z_][A-Za-z0-9_]*$' || true
}

all_calls=""
for f in "${files[@]}"; do
    text="$(strip_comments "$f")"
    for name in $(called_names "$text"); do
        all_calls+="$f $name"$'\n'
    done
done

for f in "${files[@]}"; do
    text="$(strip_comments "$f")"

    if creates_channel "$text" && ! calls_create "$text"; then
        report channel-not-created "$f (constructs a NotificationChannel without createNotificationChannel)"
    fi

    owner="$(declaring_type "$text")"
    for name in $(defined_names "$text"); do
        called=0
        while IFS= read -r entry; do
            [ -n "$entry" ] || continue
            case "$entry" in "$f "*) continue ;; esac
            site="${entry#* }"
            if [ "$site" = "$name" ] || { [ -n "$owner" ] && [ "$site" = "$owner.$name" ]; }; then
                called=1
                break
            fi
        done <<<"$all_calls"
        if [ "$called" -eq 0 ]; then
            report ensurer-never-called "$f (defines $name, and no other main source calls it)"
        fi
    done

    if goes_foreground "$text" && ! defines_ensurer "$text" && ! calls_create "$text"; then
        if [ -z "$(called_names "$text")" ]; then
            report foreground-without-ensure "$f (asks for the foreground state without ensuring the channel)"
        fi
    fi
done

if [ "$findings" -gt 0 ]; then
    echo "notification-channel-audit: FAIL — $findings finding(s)"
    exit 1
fi

echo "notification-channel-audit: OK — ${#files[@]} main source files, no orphan channel"
