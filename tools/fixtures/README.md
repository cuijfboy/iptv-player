# Test fixtures (`tools/fixtures/`)

Static samples for the parser regression suite (`docs/04` P0-8 created the directory, P1-1 uses it).
The tests read these files straight off disk — `FixtureRegressionTest` walks up from the test working
directory to the repo root — so nothing here is duplicated into `src/test/resources`.

## Inventory

| File | What it exercises |
| --- | --- |
| `m3u/valid.m3u` | the happy path: quoted/bare attributes, `#EXTGRP`, `#EXTVLCOPT` UA + referrer |
| `m3u/attrs-edge.m3u` | attribute chaos: comma inside a quoted group, bare values, missing attrs, empty title |
| `m3u/malformed.m3u` | row-level junk: `#EXTINF` without URL, URL without `#EXTINF`, unusable URL, blank name, trailing `#EXTINF` |
| `m3u/bom-crlf.m3u` | UTF-8 BOM + CRLF line endings |
| `m3u/gb18030.m3u` | non-UTF-8 bytes (GB18030) → charset fallback |
| `m3u/header-only.m3u` | a file that is only `#EXTM3U` |
| `m3u/empty.m3u` | zero bytes |
| `m3u/real-excerpt.m3u` | 10 real rows copied from the validated baseline list, token URLs included |
| `txt/two-column.txt` | `名称,URL` |
| `txt/three-column.txt` | `名称,URL,分组` |
| `txt/genre.txt` | `#genre#` sections and the group precedence rule |
| `txt/malformed.txt` | one-field rows, blank name, unusable URL, comments, comma inside the group |
| `txt/gb18030.txt` | non-UTF-8 bytes (GB18030) → charset fallback |

## Provenance and redaction

`m3u/real-excerpt.m3u` is a 10-row excerpt of `/Users/jeffrey/temp/dsh/iptv-repo/out/validated.m3u`
(658 channels). The full file is **not** vendored: it is ~150 KB of third-party hosts, it churns, and
the repo is public. The excerpt keeps the real shape, including a `?key=…&authid=…` token URL —
`docs/04` §7 and `docs/02` §13 both name "token URL" as a fixture category on purpose.

Everything else is hand-written for the edge case it covers. No LAN address, hostname, credential or
MAC from the office environment appears in this directory.

## Files that are deliberately not here

- **A GB-scale single line.** The parser is tested against a multi-megabyte URL line built in memory
  by the test, so no large fixture is committed (that is what `.gitignore`'s `tools/fixtures/large/`
  is for). See `docs/05-过程记录/05-P0构建验证.md` §11.
