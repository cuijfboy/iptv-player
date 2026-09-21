#!/usr/bin/env bash
#
# 敏感信息守护 / sensitive-information guard.
#
# Why this exists: three process records (`05-P0构建验证.md`, `13-P1-8…`, `16-P1-4…`) once carried
# the tester's TV intranet address into this public repo. The text was corrected, but the commits
# still contain it, so the cheapest permanent fix is a gate that refuses *new* leaks at the door.
#
# Scope: **tracked files only** (`git ls-files`). That is what a push would publish; local scratch
# files, build output and anything git-ignored are not this guard's business. History is not
# scanned — the guard cannot rewrite it, and `git log -p` on a leak is a separate cleanup.
#
# Rules (each one is deliberately shaped to be quiet on this repo, see docs/05-过程记录/22-守卫与绑定.md):
#   private-ip          RFC 1918 ranges: 192.168.x.x, 10.x.x.x, 172.16–31.x.x.
#                       A full dotted quad is required for the 3-octet forms to avoid matching
#                       version strings (`1.10.2`), and a leading `[^0-9A-Za-z.]` guard keeps
#                       `10.0.0.9` from matching inside a longer run of digits/dots. Loopback
#                       (127.0.0.1) is NOT flagged: it is a local address, not a LAN one.
#   mac-address         AA:BB:CC:DD:EE:FF / AA-BB-… — six hex pairs with a separator.
#   credential-literal  `password=` / `token=` / `secret:` … followed by a value. The key must start
#                       at a word boundary, so `RELEASE_STORE_PASSWORD=` (an env var NAME) and
#                       `storePassword =` (a Kotlin assignment) are not hits; only a lowercase
#                       key token that is not glued to other letters/underscores is.
#
# Allowlist: `tools/ci/sensitive-info-allowlist.txt`, one `PATTERN<TAB>REASON` per line, where
# PATTERN is an ERE matched against the whole `path:line:text` hit. Every entry must carry a reason
# — it is printed back whenever it suppresses something, so a suppression is never silent.
#
# Skipped files: the guard's own apparatus (`tools/ci/sensitive-info-*`). It necessarily *contains*
# every pattern — the script is the pattern list, the test plants a leak on purpose, the allowlist
# spells the allowed ones — so scanning it can only ever produce noise, or worse, force a whole-file
# allowlist entry that would mask a real leak pasted there. Those three files are read by a human
# whenever they change, which is the same review the diff already gets.
#
# Exit status: 0 = no unallowlisted hit (allowlisted hits are reported), 1 = at least one hit.
set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "sensitive-info-guard: not inside a git work tree" >&2
    exit 2
}
cd "$repo_root"

allowlist_file="${1:-tools/ci/sensitive-info-allowlist.txt}"

if [ ! -f "$allowlist_file" ]; then
    echo "sensitive-info-guard: allowlist not found: $allowlist_file" >&2
    exit 2
fi

# name<TAB>extended-regular-expression. `[.]` instead of `\.`: the same string is passed to `grep -E`
# and must survive quoting unchanged.
rules=(
    $'private-ip\t(^|[^0-9A-Za-z.])((192[.]168|10)[.][0-9]{1,3}[.][0-9]{1,3}([.][0-9]{1,3})?|172[.](1[6-9]|2[0-9]|3[01])[.][0-9]{1,3}[.][0-9]{1,3}([.][0-9]{1,3})?)([^0-9]|$)'
    $'mac-address\t([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}'
    $'credential-literal\t(^|[^0-9A-Za-z_])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|access[_-]?token|auth[_-]?token)[[:space:]]*[:=][[:space:]]*[^[:space:]]'
)

tracked_count="$(git ls-files | wc -l | tr -d ' ')"
skip_paths='^tools/ci/sensitive-info-(guard[.]sh|guard[.]test[.]sh|allowlist[.]txt)$'
scanned_count="$(git ls-files | grep -vcE "$skip_paths")"
echo "敏感信息守护 / sensitive-info guard"
echo "  repo            : $repo_root"
echo "  tracked files   : $tracked_count (only tracked files are scanned)"
echo "  skipped         : $((tracked_count - scanned_count)) (the guard's own apparatus: script / self-test / allowlist)"
if [ "$tracked_count" -eq 0 ]; then
    echo "  result          : OK — nothing tracked, nothing to scan"
    exit 0
fi

# All hits, as "rule<TAB>path:line:text" so the rule name never pollutes the reported line.
# -H keeps the path, -n gives the line number (the `file:line` the report must print) and -I skips
# binaries (`keystore/release.jks` is tracked and has no business being grepped).
hits="$(
    for rule in "${rules[@]}"; do
        name="${rule%%$'\t'*}"
        pattern="${rule#*$'\t'}"
        git ls-files -z | grep -zvE "$skip_paths" \
            | xargs -0 grep -HnIE -- "$pattern" 2>/dev/null \
            | awk -v n="$name" 'BEGIN { OFS = "\t" } { print n, $0 }'
    done
)"

allowed=0
failures=0
allowlisted_report=""
failure_report=""

while IFS=$'\t' read -r rule line; do
    [ -n "${rule:-}" ] || continue
    reason=""
    while IFS=$'\t' read -r pattern why; do
        case "$pattern" in '' | '#'*) continue ;; esac
        if printf '%s' "$line" | grep -qE -- "$pattern"; then
            reason="$why"
            break
        fi
    done < "$allowlist_file"
    if [ -n "$reason" ]; then
        allowed=$((allowed + 1))
        allowlisted_report="${allowlisted_report}  [${rule}] ${line}
      reason: ${reason}
"
    else
        failures=$((failures + 1))
        failure_report="${failure_report}  [${rule}] ${line}
"
    fi
done <<EOF
$hits
EOF

if [ "$failures" -gt 0 ]; then
    echo ""
    echo "FAIL: ${failures} unallowlisted hit(s), as <rule> <file>:<line>: <text>"
    printf '%s' "$failure_report"
    echo ""
    echo "Redact the real value, or — when it is genuinely harmless — add one line to"
    echo "${allowlist_file} of the form '<ERE over path:line:text><TAB><reason>'."
else
    echo "  result          : OK — 0 unallowlisted hit(s)"
fi

if [ "$allowed" -gt 0 ]; then
    echo ""
    echo "Allowlisted (${allowed}), each line carries the reason from ${allowlist_file}:"
    printf '%s' "$allowlisted_report"
fi

[ "$failures" -eq 0 ]
