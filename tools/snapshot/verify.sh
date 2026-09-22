#!/usr/bin/env bash
# Static checks on the shipped snapshot: usable TV playlist, no secrets, PROVENANCE in sync.
# No device and no network needed. Exit 0 = every check passed.
#
#   tools/snapshot/verify.sh [path/to/channels.m3u]
set -uo pipefail

SNAPSHOT="${1:-app/src/main/assets/snapshot/channels.m3u}"
PROVENANCE="$(dirname "$SNAPSHOT")/PROVENANCE.md"
MIN_CHANNELS=150
fail=0

check() { # check <ok|fail> <label> <detail>
  if [[ "$1" == ok ]]; then
    printf 'ok   %-34s %s\n' "$2" "${3:-}"
  else
    printf 'FAIL %-34s %s\n' "$2" "${3:-}"
    fail=1
  fi
}

[[ -f "$SNAPSHOT" ]] || { echo "verify.sh: no such file: $SNAPSHOT" >&2; exit 2; }
text="$(cat "$SNAPSHOT")"
extinf=$(grep -c '^#EXTINF' "$SNAPSHOT" || true)
urls=$(grep -cE '^(https?|rtmp)://' "$SNAPSHOT" || true)

check "$([[ "$(head -1 "$SNAPSHOT")" == "#EXTM3U" ]] && echo ok || echo fail)" \
  "extm3u-header" "$(head -1 "$SNAPSHOT")"
check "$([[ "$extinf" == "$urls" ]] && echo ok || echo fail)" \
  "one-stream-per-channel" "extinf=$extinf url=$urls"
check "$([[ "$extinf" -ge "$MIN_CHANNELS" ]] && echo ok || echo fail)" \
  "channels>=${MIN_CHANNELS}" "channels=$extinf"

missing=$(grep '^#EXTINF' "$SNAPSHOT" \
  | grep -vc 'tvg-id=".*" tvg-name=".*" tvg-chno=[0-9]* group-title=".*"' || true)
check "$([[ "$missing" == "0" ]] && echo ok || echo fail)" "row-metadata" "rows missing metadata=$missing"

blank=$(grep '^#EXTINF' "$SNAPSHOT" | awk -F, '{print $NF}' | grep -c '^[[:space:]]*$' || true)
check "$([[ "$blank" == "0" ]] && echo ok || echo fail)" "display-name-present" "blank names=$blank"

seq_ok=$(grep '^#EXTINF' "$SNAPSHOT" \
  | sed -E 's/.*tvg-chno=([0-9]+).*/\1/' | awk -v n="$extinf" '{ if ($1 != NR) { print "bad"; exit } } END { if (NR != n) print "bad" }')
check "$([[ -z "$seq_ok" ]] && echo ok || echo fail)" "tvg-chno-1..N-contiguous" ""

groups=$(grep '^#EXTINF' "$SNAPSHOT" | sed -E 's/.*group-title="([^"]*)".*/\1/' | sort | uniq -c | sort -rn)
for want in 央视 卫视 地方/其他; do
  check "$(printf '%s' "$groups" | grep -q " $want$" && echo ok || echo fail)" "group-covered:$want" ""
done

private=$(printf '%s' "$text" \
  | grep -Ec '(^|[^0-9A-Za-z.])((192\.168|10)\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})' || true)
check "$([[ "$private" == "0" ]] && echo ok || echo fail)" "no-private-address" "hits=$private"

invalid=$(printf '%s' "$text" | grep -c '\.invalid' || true)
check "$([[ "$invalid" == "0" ]] && echo ok || echo fail)" "no-synthetic-invalid-host" "hits=$invalid"

# (name_key, group_key) must be unique with the app's own normalization, otherwise Room collapses
# rows on seed/import and the browse list shows fewer channels than the file claims.
dupes=$(python3 - "$SNAPSHOT" <<'PY'
import re, sys


def fold(ch):
    if ch == "\u3000":
        return " "
    if "\uFF01" <= ch <= "\uFF5E":
        return chr(ord(ch) - 0xFEE0)
    return ch


def key(raw):
    out, pending = [], False
    for ch in raw:
        c = fold(ch)
        if c.isspace():
            pending = bool(out)
        else:
            if pending:
                out.append(" ")
            pending = False
            out.append(c)
    return "".join(c for c in "".join(out) if not c.isspace()).lower()


seen, dup = set(), []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if not line.startswith("#EXTINF"):
        continue
    group = (re.search(r'group-title="([^"]*)"', line) or [None, "other"])[1]
    name = line.split(",", 1)[1].strip() if "," in line else ""
    k = (key(name), key(group) or "other")
    if k in seen:
        dup.append(name)
    seen.add(k)
print("\n".join(dup))
PY
)
check "$([[ -z "$dupes" ]] && echo ok || echo fail)" "name-key-unique" \
  "$(printf '%s' "$dupes" | tr '\n' ' ')"

cred=$(printf '%s' "$text" \
  | grep -Ec '(^|[^0-9A-Za-z_])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|access[_-]?token|auth[_-]?token)[[:space:]]*[:=]' || true)
check "$([[ "$cred" == "0" ]] && echo ok || echo fail)" "no-credential-query-key" "hits=$cred"

if [[ -f "$PROVENANCE" ]]; then
  declared=$(grep -oE '<!-- snapshot-channels: [0-9]+ -->' "$PROVENANCE" | grep -oE '[0-9]+' | head -1 || true)
  check "$([[ "$declared" == "$extinf" ]] && echo ok || echo fail)" \
    "provenance-channel-count" "declared=$declared file=$extinf"
  machine=$(grep -oE '<!-- snapshot-groups: [^>]*-->' "$PROVENANCE" | sed -E 's/.*snapshot-groups: (.*) -->/\1/' | head -1 || true)
  mismatch=0
  pairs=()
  if [[ -n "$machine" ]]; then IFS=',' read -r -a pairs <<< "$machine"; fi
  for pair in ${pairs[@]+"${pairs[@]}"}; do
    g="${pair%%=*}"; n="${pair##*=}"
    actual=$(printf '%s' "$groups" | grep -F " $g" | head -1 | awk '{print $1}')
    [[ "$actual" == "$n" ]] || { mismatch=$((mismatch + 1)); echo "     group $g: provenance=$n file=${actual:-0}"; }
  done
  [[ -n "$machine" ]] || mismatch=1
  check "$([[ "$mismatch" == "0" ]] && echo ok || echo fail)" "provenance-group-counts" "mismatched=$mismatch"
else
  check fail "provenance-present" "missing $PROVENANCE"
fi

echo
echo "groups in $SNAPSHOT:"
printf '%s\n' "$groups"
if [[ "$fail" == "0" ]]; then
  echo "verify.sh: OK — $SNAPSHOT ($extinf channels)"
else
  echo "verify.sh: FAILED — see the FAIL lines above" >&2
fi
exit "$fail"
