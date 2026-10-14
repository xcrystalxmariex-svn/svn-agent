/*
 * netprobe — on-device connectivity self-test for the SVN Agent engine.
 *
 * Runs with the SAME glibc loader + resolvfix shim + env as the opencode
 * engine, so what it reports is what the engine sees. It checks, in order:
 *   1. the (shim-redirected) /etc/resolv.conf contents
 *   2. a raw UDP DNS A-query to the first nameserver (does the network even
 *      carry our DNS packets? — the usual phone-side failure)
 *   3. glibc getaddrinfo() (files+dns via nsswitch, like the Bun/c-ares path)
 *   4. a TCP connect to the resolved host :443 (real reachability)
 *
 * Usage: netprobe [host]   (default host: models.dev)
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>

static void read_resolv(char *first_ns, size_t n) {
    first_ns[0] = 0;
    FILE *f = fopen("/etc/resolv.conf", "r");
    if (!f) {
        printf("[probe] resolv.conf: cannot open (%s)\n", strerror(errno));
        return;
    }
    char line[256];
    printf("[probe] resolv.conf:\n");
    while (fgets(line, sizeof line, f)) {
        printf("    %s", line);
        if (!first_ns[0] && strncmp(line, "nameserver", 10) == 0) {
            char *p = line + 10;
            while (*p == ' ' || *p == '\t') p++;
            size_t len = strcspn(p, "\r\n");
            if (len < n) { memcpy(first_ns, p, len); first_ns[len] = 0; }
        }
    }
    fclose(f);
}

/* Raw UDP DNS A-query. Returns 0 on a reply, -1 on timeout/error. */
static int raw_dns_query(const char *ns, const char *host, char *out, size_t outn) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) { printf("[probe] dns socket: %s\n", strerror(errno)); return -1; }
    struct timeval tv = { 6, 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);

    unsigned char q[512];
    memset(q, 0, sizeof q);
    q[0] = 0x12; q[1] = 0x34;             /* id */
    q[2] = 0x01; q[3] = 0x00;             /* RD */
    q[4] = 0x00; q[5] = 0x01;             /* qdcount=1 */
    size_t o = 12;
    const char *p = host;
    while (*p) {
        const char *dot = strchr(p, '.');
        size_t l = dot ? (size_t)(dot - p) : strlen(p);
        if (l == 0 || l > 63 || o + l + 1 >= sizeof q) { close(s); return -1; }
        q[o++] = (unsigned char) l;
        memcpy(q + o, p, l); o += l;
        if (!dot) break;
        p = dot + 1;
    }
    q[o++] = 0;                           /* root label */
    q[o++] = 0x00; q[o++] = 0x01;         /* QTYPE A */
    q[o++] = 0x00; q[o++] = 0x01;         /* QCLASS IN */

    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port = htons(53);
    if (inet_pton(AF_INET, ns, &sa.sin_addr) != 1) {
        printf("[probe] nameserver '%s' is not IPv4 — skipping raw query\n", ns);
        close(s);
        return -1;
    }
    if (sendto(s, q, o, 0, (struct sockaddr *) &sa, sizeof sa) < 0) {
        printf("[probe] dns sendto %s:53 -> %s\n", ns, strerror(errno));
        close(s);
        return -1;
    }
    unsigned char r[512];
    ssize_t n = recv(s, r, sizeof r, 0);
    close(s);
    if (n < 0) {
        printf("[probe] dns recv from %s:53 -> %s (network dropped our DNS)\n",
               ns, strerror(errno));
        return -1;
    }
    /* walk answers for A records. 'o' is already the offset just past the
     * question (the echoed question has the same length we built). */
    int ancount = (r[6] << 8) | r[7];
    size_t i = o;
    int found = 0;
    for (int a = 0; a < ancount && i + 12 <= (size_t) n; a++) {
        if ((r[i] & 0xC0) == 0xC0) { i += 2; }
        else { while (i < (size_t) n && r[i] != 0) i += r[i] + 1; i++; }
        int type = (r[i] << 8) | r[i + 1];
        int rdlen = (r[i + 8] << 8) | r[i + 9];
        i += 10;
        if (type == 1 && rdlen == 4) {
            char ip[32];
            snprintf(ip, sizeof ip, "%u.%u.%u.%u", r[i], r[i + 1], r[i + 2], r[i + 3]);
            printf("[probe] dns %s @%s -> A %s\n", host, ns, ip);
            if (!found && outn) { strncpy(out, ip, outn); out[outn - 1] = 0; }
            found = 1;
        }
        i += rdlen;
    }
    if (!found) printf("[probe] dns %s @%s -> reply, but no A record\n", host, ns);
    return found ? 0 : -1;
}

int main(int argc, char **argv) {
    const char *host = argc > 1 ? argv[1] : "models.dev";
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("[probe] host=%s\n", host);

    char ns[64];
    read_resolv(ns, sizeof ns);

    char ip[64] = "";
    if (ns[0]) raw_dns_query(ns, host, ip, sizeof ip);

    /* glibc getaddrinfo (files+dns). Bun/c-ares uses the raw path above, so a
     * failure here is only informative, not fatal — but keep its address as a
     * fallback if the raw query found nothing. */
    char gai_ip[64] = "";
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    int g = getaddrinfo(host, "443", &hints, &res);
    if (g != 0) {
        printf("[probe] getaddrinfo(%s) -> %s\n", host, gai_strerror(g));
    } else {
        struct sockaddr_in *sin = (struct sockaddr_in *) res->ai_addr;
        inet_ntop(AF_INET, &sin->sin_addr, gai_ip, sizeof gai_ip);
        printf("[probe] getaddrinfo(%s) -> %s\n", host, gai_ip);
    }

    if (!ip[0]) strncpy(ip, gai_ip, sizeof ip);
    if (!ip[0]) {
        printf("[probe] RESULT: DNS BROKEN (no A record via raw or glibc)\n");
        if (res) freeaddrinfo(res);
        return 1;
    }

    struct sockaddr_in target;
    memset(&target, 0, sizeof target);
    target.sin_family = AF_INET;
    target.sin_port = htons(443);
    inet_pton(AF_INET, ip, &target.sin_addr);
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct timeval tv = { 8, 0 };
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    int c = connect(s, (struct sockaddr *) &target, sizeof target);
    if (c == 0) {
        printf("[probe] tcp connect %s:443 -> OK\n", ip);
        printf("[probe] RESULT: NETWORK OK\n");
    } else {
        printf("[probe] tcp connect %s:443 -> %s\n", ip, strerror(errno));
        printf("[probe] RESULT: DNS ok, but outbound TCP blocked\n");
    }
    close(s);
    if (res) freeaddrinfo(res);
    return 0;
}
