#!/usr/bin/env bash
#
# Bundle the OpenCode core into app/src/main/assets/opencode_project/index.js
# as a single compressed JavaScript asset for the embedded Node.js engine.
#
# Pipeline (per the Android app architecture):
#   1. install production deps only
#   2. npm prune --production      (drop devDependencies + bloat)
#   3. esbuild --bundle --minify   (single file, no node_modules at runtime)
#
# Usage:
#   tools/build_opencode_bundle.sh /path/to/opencode [entry.js] [outdir]
#
# Defaults:
#   entry   = ./index.js (or ./src/index.js if present)
#   outdir  = <repo>/app/src/main/assets/opencode_project

set -euo pipefail

SRC="${1:-}"
if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "usage: $0 /path/to/opencode [entry.js] [outdir]" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTDIR="${3:-$REPO_DIR/app/src/main/assets/opencode_project}"

ENTRY="${2:-}"
if [ -z "$ENTRY" ]; then
  if [ -f "$SRC/index.js" ]; then ENTRY="index.js"
  elif [ -f "$SRC/src/index.js" ]; then ENTRY="src/index.js"
  else echo "!! could not find an entrypoint; pass it as arg 2" >&2; exit 3
  fi
fi

echo "== source : $SRC"
echo "== entry  : $ENTRY"
echo "== outdir : $OUTDIR"

cd "$SRC"

echo "-- installing production dependencies"
if [ -f package-lock.json ]; then
  npm ci --omit=dev || npm install --omit=dev
else
  npm install --omit=dev
fi

echo "-- npm prune --production"
npm prune --production

echo "-- bundling with esbuild"
mkdir -p "$OUTDIR"
npx --yes esbuild "$ENTRY" \
  --bundle \
  --platform=node \
  --format=cjs \
  --target=node18 \
  --minify \
  --outfile="$OUTDIR/index.js"

echo "-- done: $(ls -l "$OUTDIR/index.js" | awk '{print $5}') bytes -> $OUTDIR/index.js"
echo "   (drop opencode_project/core.js beside it if the core is split as a lib)"
