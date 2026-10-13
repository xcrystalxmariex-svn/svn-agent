#!/usr/bin/env bash
# export_persona.sh — package a persona into a PBF v1 bundle (.tar.gz)
# that SVN Agent's IMPORT AGENT screen can deploy.
#
# Usage:
#   tools/export_persona.sh OUT_DIR NAME ID [SRC_DIR...]
#
# SRC_DIRs default to: AGENTS.md opencode.json memory config agent assets
# (whichever exist next to the script / in CWD). Any file/folder listed is
# copied into the bundle root. A manifest.json is generated for you.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v tar >/dev/null 2>&1; then
  echo "tar not found" >&2
  exit 1
fi

OUT_DIR="${1:?usage: export_persona.sh OUT_DIR NAME ID [SRC_DIR...]}"
NAME="${2:?missing persona NAME}"
ID="${3:?missing persona ID}"

# Default sources: whatever exists in CWD at known persona paths
DEFAULT_SRC=(AGENTS.md opencode.json memory config agent assets)
if [ $# -ge 4 ]; then
  SRC=("${@:4}")
else
  SRC=()
  for p in "${DEFAULT_SRC[@]}"; do
    [ -e "$p" ] && SRC+=("$p")
  done
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

for src in "${SRC[@]}"; do
  [ -e "$src" ] || { echo "source not found: $src" >&2; exit 1; }
  cp -r "$src" "$STAGE/"
done

cat > "$STAGE/manifest.json" <<EOF
{
  "manifest_version": 1,
  "id": "$ID",
  "name": "$NAME",
  "version": "$(date +%Y%m%d.%H%M)",
  "created_by": "$(whoami 2>/dev/null || echo persona)",
  "agent_entry": "agent/main.py",
  "home_dir_name": ".${ID}"
}
EOF

mkdir -p "$OUT_DIR"
BUNDLE="$OUT_DIR/${ID}-$(date +%Y%m%d).tar.gz"
tar -C "$STAGE" -czf "$BUNDLE" .
echo "wrote: $BUNDLE"
echo "import it in the SVN Agent app: IMPORT AGENT -> PICK PERSONA BUNDLE"