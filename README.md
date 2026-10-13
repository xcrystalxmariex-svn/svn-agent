# SVN Agent APK (com.svndev.svnagent)

Fully-contained opencode agent in an Android APK. No backend server; the
agent runs on-device inside the app.

## Build notes

- **Toolchain**: A-IDE friendly. Java sources only use the platform SDK
  (`android.*`, `java.*`) — no androidx/appcompat, no external Maven deps.
  Build in A-IDE with **Android Gradle Plugin 3.5.3** / Gradle 6.1.1
  (the wrapper properties + `apply plugin` style match the buildscript
  classpath approach; `plugins {}` DSL is NOT used for that reason).
- minSdk 21 / targetSdk 33. Compiles fine offline.

## What's where

- `app/src/main/java/com/svndev/svnagent/MainActivity.java`
  App-drawer grid. On first run calls `RuntimeBootstrap.setup()`; after
  bootstrap shows the drawer (UI / RESTART / HOME).
- `app/src/main/java/com/svndev/svnagent/RuntimeBootstrap.java`
  One-time extraction of `assets/runtime/**` into app-private storage,
  writes `~/.config/opencode/opencode.json`, runs
  `python agent/main.py --init`, sets the "bootstrap done" flag.
- `app/src/main/java/com/svndev/svnagent/TerminalActivity.java`
  Spawns the bundled opencode CLI (node + opencode), feeds stdout into
  `TerminalView`, pipes the on-screen input line back to stdin — the opencode
  TUI in-app.
- `app/src/main/java/com/svndev/svnagent/TerminalView.java`
  Pure-Java ANSI/VT terminal widget (character grid + SGR/CUP/clear + block
  cursor). No external library.
- `app/src/main/java/com/svndev/svnagent/OpenCodeService.java`
  Service scaffold for keeping the agent process alive across UI changes
  (wire up START_STICKY when the terminal is moved here).
- `app/src/main/java/com/svndev/svnagent/PersonaImporter.java`
  Deploys persona bundles (PBF v1) into `filesDir/agents/<id>/`, runs the
  optional `agent/install.sh`, and registers the id for the drawer. Sniffs
  zip vs tar.gz; zip-slip guarded. Format: see `BUNDLE_FORMAT.md`.
- `app/src/main/java/com/svndev/svnagent/ImportAgentActivity.java`
  IMPORT AGENT screen: SAF file picker for a `.zip`/`.tar.gz` persona
  bundle → `PersonaImporter.deploy()` → drawer rebuild. Also lists cleared
  imported agents and offers REMOVE ALL.
- `tools/export_persona.sh`
  Run on any machine where a persona's files live; packages a `manifest.json`
  + AGENTS.md + memory/config/agent/assets into a tar.gz persona bundle that
  the app can import. `tools/export_persona.sh OUT_DIR NAME ID [SRC...]`.
- `app/src/main/java/com/svndev/svnagent/EngineActivity.java`
  **ENGINE** console (primary on-device engine path). Shizuku onboarding →
  native `HttpURLConnection` download of a prebuilt Android opencode binary
  → stage into `/data/local/tmp/svnagent/engine` as the shell user → launch
  `engine serve --hostname 127.0.0.1 --port 4096` via `Shizuku.newProcess`
  → stream stdout/stderr into the terminal view. See `ShizukuHelper`,
  `EngineInstaller`, `EngineLauncher`.
- `app/src/main/java/com/svndev/svnagent/NodeMobile.java` (legacy/optional)
  In-process nodejs-mobile engine. **Disabled in `app/build.gradle`** (the
  NDK/CMake wiring is commented out) because the Shizuku path needs no NDK.
  Re-enable `src/main/cpp/native-lib.cpp` + `libnode.so` to use it.

## ENGINE (Shizuku) — how to run opencode on-device

opencode is a glibc Bun binary; it runs on Android once (a) its ELF interpreter
is pointed at a bundled glibc loader, (b) the DNS shim is preloaded, and (c) it
runs from a shell-readable dir. Everything is built in **one workspace**:

    /data/local/tmp/svnagent_opencode/
      opencode                      glibc Bun binary, interpreter patched to the loader below
      glibc/lib/ld-linux-aarch64.so.1   bundled glibc loader + libs (+ gconv)
      shim/claude-resolvfix.so      LD_PRELOAD DNS shim
      etc/cert.pem  etc/resolv.conf CA bundle + nameservers
      etc/nsswitch.conf  etc/hosts  resolver config (glibc reads these paths)
      home/  tmp/                   runtime scratch

Flow (`ENGINE` cell in the drawer):
1. **Onboard Shizuku** (install/run Shizuku, grant the API permission).
2. **Download** the runtime bundle with a plain `HttpURLConnection`; gunzip in
   Java and stream the tar into the shell as
   `/system/bin/sh -c 'cat > /data/local/tmp/svnagent_opencode.tar'`
   (the app cannot write /data/local/tmp itself — Shizuku can).
3. **Build the workspace** with a `/system/bin/sh` script run via
   `Shizuku.newProcess()`: `mkdir` → `tar xf` → `chmod 755`.
4. **Launch** directly out of the workspace:
   ```
   /data/local/tmp/svnagent_opencode/opencode web --hostname 127.0.0.1 --port 4096
   ```
   with `LD_PRELOAD=/data/local/tmp/svnagent_opencode/shim/claude-resolvfix.so`,
   `LD_LIBRARY_PATH=.../glibc/lib`, `SSL_CERT_FILE=.../etc/cert.pem`,
   `CLAUDE_RESOLV_REDIRECT=.../etc/resolv.conf`, `OPENCODE_SERVER_PASSWORD=...`,
   `HOME=.../home`; stdout/stderr stream into the console view.

   `web` serves both the HTTP API **and** the browser UI on the same port. The
   drawer's **UI** cell opens that UI in an **embedded `WebView`**
   (`WebUiActivity`) at `http://127.0.0.1:4096/` — no external browser. Basic
   auth is auto-supplied from `EngineLauncher.SERVER_USER/SERVER_PASSWORD`.

### Hosting the bundle

The runtime tarball is hosted on the **`dist` branch** of this repo (not an
external server):

    https://raw.githubusercontent.com/xcrystalxmariex-svn/svn-agent/dist/svnagent-opencode-workspace-aarch64.tar.gz

This is the default **SETTINGS → engine_url**. Regenerate it from the sources in
`tools/` (opencode 1.19.0 glibc arm64 + Termux glibc 2.44 + the DNS shim).

Notes:
- The Shizuku server runs as the **shell user (UID 2000)**, which cannot read
  the app's private `filesDir`; hence `/data/local/tmp`.
- The binary must be **bionic-runnable OR run under the bundled glibc loader**
  (as here). A stock glibc binary alone dies on Android with `SIGSYS`.
- `app/src/main/assets/opencode_project/**` and `tools/build_opencode_bundle.sh`
  are the (superseded) bundled-JS experiment; opencode is a native Bun binary
  and is not bundleable as plain JS.

## Where to put the runtimes (IMPORTANT)

The APK expects these to exist under `app/src/main/assets/runtime/` before
the signed APK is built:

    assets/runtime/node/bin/node            (Android node binary, exec'able)
    assets/runtime/python/bin/python        (Android python, or Termux ext)
    assets/runtime/opencode/bin/opencode    (opencode CLI entry)
    assets/runtime/agent/main.py            (the python agent backend)

Placeholders (README.txt files) are already there. Bootstrap extracts
everything to filesDir/runtime on first run and chmod +x the binaries.

## Import a persona

1. On the source machine run: `tools/export_persona.sh /some/out rill rill`
   (from the persona's home dir, or pass explicit paths).
2. On the phone, open the app → **IMPORT AGENT** → *PICK PERSONA BUNDLE*.
3. The bundle is verified, installed under `filesDir/agents/<id>/`, and a
   new drawer cell (`◉ <id>`) launches the terminal into that persona's own
   home — `agent/main.py` if the persona carries one, else the bundled
   opencode CLI.

## Known limitations / next steps

1. **Runtime binaries must be built on-device or cross-compiled** — A-IDE
   can't produce them; build with Termux/python-for-android/chaquopy and
   copy the outputs into the assets tree before building the APK.
2. **PTY**: opencode's Ink TUI wants a tty. `ProcessBuilder` pipes are pipe
   (not tty) fds. Termux-style app runtimes (or bundling a pty lib) give a
   real pty; until then the bundled `opencode.js`/agent must tolerate a
   non-tty stdout, or drive opencode's `run` mode (one-shot) from a script.
3. **What does the python script do?** The agent is currently a stub REPL
   (`main.py`). Replace it with the real agent logic per what it needs to
   do in-app (RAG, file ops, chat) — then bundle the required python
   packages' wheels too.
4. Any config SeVin wants baked into `opencode.json` (provider/model/token)
   can be generated at first run inside `writeOpencodeConfig()` — or keep
   it as a bundled asset.