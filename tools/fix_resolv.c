/*
 * claude-resolvfix shim for the SVN Agent embedded engine (Termux glibc).
 *
 * The bundled glibc was compiled with sysconfdir under /data/data/com.termux
 * glibc/etc, so it reads resolver config from THERE, not /etc. The shell user
 * cannot write the Termux app dir, so we LD_PRELOAD this shim to redirect
 * those paths into our own workspace etc/. Redirects use a suffix match so
 * both the classic /etc and the glibc sysconfdir are covered:
 *   ... /etc/resolv.conf   -> $CLAUDE_RESOLV_REDIRECT (default <ws>/etc/resolv.conf)
 *   ... /etc/nsswitch.conf -> <dirname(resolv)>/nsswitch.conf
 *   ... /etc/hosts         -> <dirname(resolv)>/hosts
 *
 * It also scrubs LD_PRELOAD / LD_LIBRARY_PATH from the environment of any
 * children so subprocesses are not force-loaded with the glibc loader.
 */
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

extern void *dlsym(void *handle, const char *symbol);
extern char *getenv(const char *name);
extern int   unsetenv(const char *name);

#define RTLD_NEXT ((void *) -1L)
#define O_CREAT   0100

typedef struct _IO_FILE FILE;

static char resolver_dir[512];
static char nsswitch_target[600];
static char hosts_target[600];
static int  init_done = 0;

static const char *DEFAULT_RESOLV = "/data/local/tmp/svnagent_opencode/etc/resolv.conf";

static void do_init(void) {
    if (init_done) return;
    init_done = 1;
    const char *r = getenv("CLAUDE_RESOLV_REDIRECT");
    if (!r || !*r) r = DEFAULT_RESOLV;
    /* dirname of the resolv path */
    const char *slash = strrchr(r, '/');
    size_t len = slash ? (size_t)(slash - r) : strlen(r);
    if (len >= sizeof(resolver_dir)) len = sizeof(resolver_dir) - 1;
    memcpy(resolver_dir, r, len);
    resolver_dir[len] = 0;
    snprintf(nsswitch_target, sizeof(nsswitch_target), "%s/nsswitch.conf", resolver_dir);
    snprintf(hosts_target,    sizeof(hosts_target),    "%s/hosts", resolver_dir);
}

static int ends_with(const char *s, const char *suffix) {
    if (!s || !suffix) return 0;
    size_t ls = strlen(s), lf = strlen(suffix);
    return ls >= lf && strcmp(s + ls - lf, suffix) == 0;
}

static const char *resolv_target(void) {
    const char *e = getenv("CLAUDE_RESOLV_REDIRECT");
    return (e && *e) ? e : DEFAULT_RESOLV;
}

static const char *fix(const char *p) {
    if (!p) return p;
    do_init();
    if (ends_with(p, "etc/resolv.conf"))   return resolv_target();
    if (ends_with(p, "etc/nsswitch.conf")) return nsswitch_target;
    if (ends_with(p, "etc/hosts"))         return hosts_target;
    return p;
}

__attribute__((constructor)) static void scrub_loader_env(void) {
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
}

FILE *fopen(const char *p, const char *m) {
    static FILE *(*r)(const char *, const char *);
    if (!r) r = dlsym(RTLD_NEXT, "fopen");
    return r(fix(p), m);
}

FILE *fopen64(const char *p, const char *m) {
    static FILE *(*r)(const char *, const char *);
    if (!r) r = dlsym(RTLD_NEXT, "fopen64");
    return r(fix(p), m);
}

int open(const char *p, int f, ...) {
    static int (*r)(const char *, int, ...);
    if (!r) r = dlsym(RTLD_NEXT, "open");
    int m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, int); va_end(a); }
    return r(fix(p), f, m);
}

int open64(const char *p, int f, ...) {
    static int (*r)(const char *, int, ...);
    if (!r) r = dlsym(RTLD_NEXT, "open64");
    int m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, int); va_end(a); }
    return r(fix(p), f, m);
}

int openat(int d, const char *p, int f, ...) {
    static int (*r)(int, const char *, int, ...);
    if (!r) r = dlsym(RTLD_NEXT, "openat");
    int m = 0;
    if (f & O_CREAT) { va_list a; va_start(a, f); m = va_arg(a, int); va_end(a); }
    return r(d, fix(p), f, m);
}
