#!/usr/bin/env bash
#
# Self-test for tools/ci/sensitive-info-guard.sh.
#
# A guard that quietly stops matching is worse than no guard: everyone keeps assuming it is watching.
# This script proves, in a throwaway repository, that each rule still fires on a planted leak — and
# that the real tree is clean. CI runs it right after the guard itself.
set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "sensitive-info-guard.test: not inside a git work tree" >&2
    exit 2
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/probe/tools/ci"
cp "$repo_root/tools/ci/sensitive-info-guard.sh" "$repo_root/tools/ci/sensitive-info-allowlist.txt" \
    "$work/probe/tools/ci/"

cd "$work/probe" || exit 2
git init -q .
{
    echo "device: adb connect 192.168.7.77:5555"
    echo "wifi: aa:bb:cc:dd:ee:ff"
    echo "creds: password=hunter2token"
} > leak.md
git add -A

out="$(bash tools/ci/sensitive-info-guard.sh 2>&1)"
status=$?

failures=0
if [ "$status" -eq 0 ]; then
    echo "sensitive-info-guard.test: FAIL — the guard passed a file that leaks all three rules"
    failures=$((failures + 1))
fi
for rule in private-ip mac-address credential-literal; do
    if ! printf '%s' "$out" | grep -q "\[$rule\] leak\.md:"; then
        echo "sensitive-info-guard.test: FAIL — rule '$rule' did not fire on the planted leak"
        failures=$((failures + 1))
    fi
done

if ! (cd "$repo_root" && bash tools/ci/sensitive-info-guard.sh > /dev/null 2>&1); then
    echo "sensitive-info-guard.test: FAIL — the current tree has an unallowlisted hit"
    failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
    printf '%s\n' "$out"
    exit 1
fi

echo "sensitive-info-guard.test: OK — all three rules fire on a planted leak, and the tree is clean"
