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
say "building claude-resolvfix.so"
cat > "$B/fix_resolv.c" <<'EOF'
#include <stdarg.h>
extern void *dlsym(void *handle, const char *symbol);
extern char *getenv(const char *name);
extern int   strcmp(const char *a, const char *b);
extern int   unsetenv(const char *name);
#define RTLD_NEXT ((void *) -1L)
#define O_CREAT   0100
typedef struct _IO_FILE FILE;
static const char *TARGET = "/etc/resolv.conf";
static const char *REDIR  = "/data/local/tmp/svnagent_opencode/etc/resolv.conf";
static const char *target_path(void){ const char *e=getenv("CLAUDE_RESOLV_REDIRECT"); return (e&&*e)?e:REDIR; }
static const char *fix(const char *p){ return (p && strcmp(p,TARGET)==0)?target_path():p; }
__attribute__((constructor)) static void scrub_loader_env(void){ unsetenv("LD_PRELOAD"); unsetenv("LD_LIBRARY_PATH"); }
FILE *fopen(const char *p,const char *m){ static FILE*(*r)(const char*,const char*); if(!r)r=dlsym(RTLD_NEXT,"fopen"); return r(fix(p),m); }
FILE *fopen64(const char *p,const char *m){ static FILE*(*r)(const char*,const char*); if(!r)r=dlsym(RTLD_NEXT,"fopen64"); return r(fix(p),m); }
int open(const char *p,int f,...){ static int(*r)(const char*,int,...); if(!r)r=dlsym(RTLD_NEXT,"open"); int m=0; if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,int);va_end(a);} return r(fix(p),f,m); }
int open64(const char *p,int f,...){ static int(*r)(const char*,int,...); if(!r)r=dlsym(RTLD_NEXT,"open64"); int m=0; if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,int);va_end(a);} return r(fix(p),f,m); }
int openat(int d,const char *p,int f,...){ static int(*r)(int,const char*,int,...); if(!r)r=dlsym(RTLD_NEXT,"openat"); int m=0; if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,int);va_end(a);} return r(d,fix(p),f,m); }
EOF
aarch64-linux-gnu-gcc -fPIC -O2 -fno-stack-protector -c "$B/fix_resolv.c" -o "$B/fix_resolv.o"
aarch64-linux-gnu-ld -shared -o "$B/claude-resolvfix.so" "$B/fix_resolv.o" -L"$GL/lib" -l:libc.so.6 -l:libdl.so.2

# --- assemble the workspace --------------------------------------------------
say "assembling workspace"
W="$B/ws"; mkdir -p "$W/glibc" "$W/shim" "$W/etc" "$W/home" "$W/tmp"
cp -a "$GL/lib" "$W/glibc/lib"
rm -f "$W/glibc/lib/"*.o "$W/glibc/lib/"*.a; rm -rf "$W/glibc/lib/audit"
cp "$B/claude-resolvfix.so" "$W/shim/claude-resolvfix.so"
curl -fsSL https://curl.se/ca/cacert.pem -o "$W/etc/cert.pem"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$W/etc/resolv.conf"
cp "$OC" "$W/opencode"
patchelf --set-interpreter "$WORKSPACE/glibc/lib/ld-linux-aarch64.so.1" "$W/opencode"
chmod 755 "$W/opencode" "$W/shim/claude-resolvfix.so"

say "packing $OUT"
( cd "$W" && tar -I 'pigz -1' -cf "$OUT" opencode glibc shim etc home tmp )
ls -lh "$OUT"
echo
say "publish to the dist branch:"
echo "  cp $OUT /tmp/distrepo/ && cd /tmp/distrepo && git add -A && git commit -m dist && git push <remote> HEAD:dist"
