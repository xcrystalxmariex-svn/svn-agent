# svn-agent engine distribution

Runtime artifacts the SVN Agent APK downloads at first run (Shizuku pipeline).

- `svnagent-opencode-workspace-aarch64.tar.gz` — self-contained opencode runtime
  for aarch64 Android. Extracts into `/data/local/tmp/svnagent_opencode/`:
  - `opencode`            glibc Bun binary, interpreter patched to the bundled loader
  - `netprobe`            on-device DNS/outbound self-test (same env as the engine)
  - `glibc/lib/**`        glibc runtime (loader + libs + gconv)
  - `shim/claude-resolvfix.so`  DNS shim (redirects /etc/resolv.conf)
  - `etc/cert.pem`        CA bundle (SSL_CERT_FILE)
  - `etc/resolv.conf`     nameservers for the shim

Source: opencode 1.19.0 (glibc arm64) + Termux glibc 2.44. Do not edit by hand;
regenerate with the build in the main branch.
