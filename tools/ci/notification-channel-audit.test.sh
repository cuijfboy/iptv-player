#!/usr/bin/env bash
#
# Self-test for tools/ci/notification-channel-audit.sh.
#
# A guard that quietly stops matching is worse than no guard — NEW-20260922-003 shipped because
# nothing checked that a defined channel was ever created. This script proves, in a throwaway
# repository, that each rule still fires on a planted defect (including the exact NEW-3 shape) and
# that a clean tree passes. CI runs it right after the guard itself.
set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "notification-channel-audit.test: not inside a git work tree" >&2
    exit 2
}

audit="$repo_root/tools/ci/notification-channel-audit.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0

# --- 1. a planted defect tree: one file per rule ------------------------------------------------
mkdir -p "$work/dirty/tools/ci" "$work/dirty/app/src/main/kotlin"
cp "$audit" "$work/dirty/tools/ci/"

# channel-not-created: the channel is built and never registered.
cat > "$work/dirty/app/src/main/kotlin/Orphan.kt" <<'KT'
object Orphan {
    fun build(): Notification {
        val channel = NotificationChannel("refresh", "刷新", 2)
        return NotificationCompat.Builder(context, channel.id).build()
    }
}
KT

# ensurer-never-called: exactly NEW-3 — a correct ensurer with no caller anywhere.
cat > "$work/dirty/app/src/main/kotlin/Ensurer.kt" <<'KT'
object Ensurer {
    fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel("refresh", "刷新", 2))
    }
}
KT

# foreground-without-ensure: goes foreground without establishing the channel.
cat > "$work/dirty/app/src/main/kotlin/Worker.kt" <<'KT'
class Worker {
    suspend fun doWork() {
        setForeground(ForegroundInfo(1, notification()))
    }
}
KT

cd "$work/dirty" || exit 2
git init -q .
git add -A
out="$(bash tools/ci/notification-channel-audit.sh 2>&1)"
status=$?
if [ "$status" -eq 0 ]; then
    echo "notification-channel-audit.test: FAIL — the guard passed a tree with all three defects"
    failures=$((failures + 1))
fi
for rule in channel-not-created ensurer-never-called foreground-without-ensure; do
    if ! printf '%s' "$out" | grep -q "\[$rule\]"; then
        echo "notification-channel-audit.test: FAIL — rule '$rule' did not fire on the planted defect"
        failures=$((failures + 1))
    fi
done

# --- 2. a clean tree passes, including the playback pairing that already exists -----------------
mkdir -p "$work/clean/tools/ci" "$work/clean/app/src/main/kotlin"
cp "$audit" "$work/clean/tools/ci/"
cat > "$work/clean/app/src/main/kotlin/Notifications.kt" <<'KT'
object Notifications {
    fun ensureChannel(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "刷新", 2))
        }
        return manager.getNotificationChannel(CHANNEL_ID) != null
    }
}
KT
cat > "$work/clean/app/src/main/kotlin/Worker.kt" <<'KT'
class Worker {
    // Prose about startForeground(...) must not count as a call.
    suspend fun doWork() {
        if (!Notifications.ensureChannel(context)) return
        setForeground(ForegroundInfo(1, notification()))
    }
}
KT

cd "$work/clean" || exit 2
git init -q .
git add -A
out="$(bash tools/ci/notification-channel-audit.sh 2>&1)"
status=$?
if [ "$status" -ne 0 ]; then
    echo "notification-channel-audit.test: FAIL — the guard rejected a clean tree:"
    printf '%s\n' "$out"
    failures=$((failures + 1))
fi

# --- 3. the real tree must be clean --------------------------------------------------------------
out="$(cd "$repo_root" && bash tools/ci/notification-channel-audit.sh 2>&1)"
status=$?
if [ "$status" -ne 0 ]; then
    echo "notification-channel-audit.test: FAIL — the real tree has findings:"
    printf '%s\n' "$out"
    failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
    echo "notification-channel-audit.test: FAIL ($failures)"
    exit 1
fi
echo "notification-channel-audit.test: OK — 3 rules fire on planted defects, clean trees pass"
