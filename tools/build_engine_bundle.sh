#!/usr/bin/env bash
#
# build_engine_bundle.sh — build the self-contained aarch64 opencode runtime
# that the SVN Agent APK downloads (Shizuku pipeline) into
# /data/local/tmp/svnagent_opencode/.
#
# Output: svnagent-opencode-workspace-aarch64.tar.gz
# Then publish it to the repo's `dist` branch (see bottom).
#
# Prereqs: curl, tar, pigz, patchelf, dpkg-deb, and an aarch64 cross toolchain
#   (aarch64-linux-gnu-gcc / -ld) — e.g. Debian: patchelf pigz gcc-aarch64-linux-gnu.
#
# Pieces:
#   1. opencode         glibc arm64 Bun binary (upstream release)
#   2. glibc            Termux glibc runtime (loader + libs + gconv)
#   3. shim             claude-resolvfix.so, rebuilt against the Termux glibc
#
set -euo pipefail

WORKSPACE=/data/local/tmp/svnagent_opencode        # final on-device path (baked into the binary)
OPENCODE_VER="${OPENCODE_VER:-latest}"
TERMUX_GLIBC_DEB_URL="${TERMUX_GLIBC_DEB_URL:-https://packages.termux.dev/apt/termux-glibc/pool/stable/g/glibc/glibc_2.44_aarch64.deb}"
OUT="$(pwd)/svnagent-opencode-workspace-aarch64.tar.gz"
B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT

say(){ printf '\033[1;36m[bundle]\033[0m %s\n' "$*"; }

# --- 1. opencode (glibc arm64) ----------------------------------------------
say "downloading opencode ($OPENCODE_VER)"
if [ "$OPENCODE_VER" = latest ]; then
  url="https://github.com/anomalyco/opencode/releases/latest/download/opencode-linux-arm64.tar.gz"
else
  url="https://github.com/anomalyco/opencode/releases/download/v${OPENCODE_VER#v}/opencode-linux-arm64.tar.gz"
fi
curl -fL "$url" -o "$B/oc.tgz"
tar xzf "$B/oc.tgz" -C "$B"
OC="$(find "$B" -type f -name opencode | head -1)"
[ -n "$OC" ] || { echo "opencode binary not found in archive"; exit 1; }

# --- 2. Termux glibc runtime -------------------------------------------------
say "fetching Termux glibc runtime"
curl -fL "$TERMUX_GLIBC_DEB_URL" -o "$B/glibc.deb"
dpkg-deb -x "$B/glibc.deb" "$B/glibc-extract"
GL="$B/glibc-extract/data/data/com.termux/files/usr/glibc"

# --- 3. DNS shim (rebuilt for glibc) ----------------------------------------
# fix_resolv.c lives tracked in tools/ — it redirects glibc's resolver config
# paths (both /etc and the Termux compiled-in sysconfdir) into the workspace.
say "building claude-resolvfix.so"
cp "$(dirname "$0")/fix_resolv.c" "$B/fix_resolv.c"
aarch64-linux-gnu-gcc -fPIC -O2 -fno-stack-protector -c "$B/fix_resolv.c" -o "$B/fix_resolv.o"
aarch64-linux-gnu-ld -shared -o "$B/claude-resolvfix.so" "$B/fix_resolv.o" -L"$GL/lib" -l:libc.so.6 -l:libdl.so.2

# --- 3b. netprobe (on-device DNS/outbound self-test) ------------------------
# Runs under the exact engine env (glibc loader + resolvfix shim) so its report
# reflects what the engine sees. Interpreter is baked to the on-device loader,
# same as opencode.
say "building netprobe"
cp "$(dirname "$0")/probe.c" "$B/probe.c"
aarch64-linux-gnu-gcc -O2 -fno-stack-protector -o "$B/netprobe" "$B/probe.c" \
  "$GL/lib/ld-linux-aarch64.so.1" -L"$GL/lib" -l:libc.so.6 \
  -Wl,--dynamic-linker="$WORKSPACE/glibc/lib/ld-linux-aarch64.so.1" \
  -Wl,-rpath,"$WORKSPACE/glibc/lib"

# --- assemble the workspace --------------------------------------------------
say "assembling workspace"
W="$B/ws"; mkdir -p "$W/glibc" "$W/shim" "$W/etc" "$W/home" "$W/tmp"
cp -a "$GL/lib" "$W/glibc/lib"
rm -f "$W/glibc/lib/"*.o "$W/glibc/lib/"*.a; rm -rf "$W/glibc/lib/audit"
# ld(1) linker scripts (libc.so, libm.so) are ASCII text; a loader asking for
# them gets "bad ELF magic". Replace with symlinks to the real shared objects.
for s in "$W/glibc/lib/"*.so; do
  if file -b "$s" | grep -q 'ASCII text'; then
    target="$(sed -n 's/.*GROUP[^()]*([ ]*\([^ /]*\).*/\1/p' "$s" | head -1)"
    [ -n "$target" ] && [ -f "$W/glibc/lib/$target" ] && ln -sf "$target" "$s" || rm -f "$s"
  fi
done
# glibc bakes its sysconfdir paths into libc.so.6 (the Termux dir, which does
# not exist for the shell user). getaddrinfo() reads resolv.conf/nsswitch.conf
# via internal, non-interposable opens, so the LD_PRELOAD shim CANNOT redirect
# them — Bun's fetch (which uses getaddrinfo, not c-ares) then fails with
# "Unable to connect" for every model. Repoint those strings at our workspace.
say "patching glibc resolver paths -> $WORKSPACE/etc"
python3 - "$W/glibc/lib/libc.so.6" "$WORKSPACE" <<'PY'
import sys
libc, ws = sys.argv[1], sys.argv[2]
d = bytearray(open(libc, "rb").read())
old, new = "/data/data/com.termux/files/usr/glibc/etc/", ws + "/etc/"
for name in ("resolv.conf", "nsswitch.conf", "hosts"):
    b, n = (old + name).encode(), (new + name).encode()
    if len(n) > len(b):
        raise SystemExit("glibc path patch: %s too long for %s" % (n, b))
    pad, i, hits = len(b) - len(n), 0, 0
    while True:
        j = d.find(b, i)
        if j < 0:
            break
        if d[j + len(b):j + len(b) + 1] == b"\x00":   # exact, null-terminated
            d[j:j + len(b)] = n + b"\x00" * pad
            hits += 1
        i = j + 1
    if hits == 0:
        raise SystemExit("glibc path patch: no match for %s" % name)
    print("  %s: %d" % (name, hits))
open(libc, "wb").write(d)
PY
cp "$B/claude-resolvfix.so" "$W/shim/claude-resolvfix.so"
curl -fsSL https://curl.se/ca/cacert.pem -o "$W/etc/cert.pem"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$W/etc/resolv.conf"
printf 'hosts: files dns\n' > "$W/etc/nsswitch.conf"
printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > "$W/etc/hosts"
cp "$OC" "$W/opencode"
patchelf --set-interpreter "$WORKSPACE/glibc/lib/ld-linux-aarch64.so.1" "$W/opencode"
cp "$B/netprobe" "$W/netprobe"
chmod 755 "$W/opencode" "$W/netprobe" "$W/shim/claude-resolvfix.so"

say "packing $OUT"
( cd "$W" && tar -I 'pigz -1' -cf "$OUT" opencode netprobe glibc shim etc home tmp )
ls -lh "$OUT"
echo
say "publish to the dist branch:"
echo "  cp $OUT /tmp/distrepo/ && cd /tmp/distrepo && git add -A && git commit -m dist && git push <remote> HEAD:dist"
