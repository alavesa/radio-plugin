#!/bin/sh
# Zips resourcepack/ into radio-pack.zip and prints its SHA-1 (for pack.delivery: url -> pack.url + pack.sha1).
# The plugin builds the same pack by itself at startup (plugins/Radio/radio-pack.zip); this script is for hosting the
# pack yourself or merging it into another pack. Usage: tools/zip_pack.sh [output.zip]
set -e
cd "$(dirname "$0")/.."
OUT="${1:-$PWD/radio-pack.zip}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
rm -f "$OUT"
# -X: no extra file attributes, -D: no directory entries; sorted names: the same sources give the same zip
(cd resourcepack && find . -type f ! -name '.DS_Store' | LC_ALL=C sort | sed 's|^\./||' | zip -q -X -D -@ "$OUT")
echo "wrote $OUT"
if command -v shasum >/dev/null 2>&1; then echo "SHA-1: $(shasum -a 1 "$OUT" | cut -d' ' -f1)"; else echo "SHA-1: $(sha1sum "$OUT" | cut -d' ' -f1)"; fi
