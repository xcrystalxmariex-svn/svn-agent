# SVN Agent APK (com.svndev.svnagent)

Fully-contained opencode agent in an Android APK. No backend server; the
agent runs on-device inside the app.

Fully-contained opencode agent in an Android APK. No backend server; the
agent runs on-device inside the app.

## Quick start — pull into A-IDE, build, install

1. In A-IDE: **three-dot menu → Open project / Project → Clone Git repo**,
   URL `https://github.com/xcrystalxmariex-svn/svn-agent.git`, branch **main**.
   (Or, if you already have it open: **Git → Pull** to get the latest.)
2. Let A-IDE sync Gradle (AGP 3.5.3 / Gradle 6.1.1 per `gradle/wrapper`).
   First sync may need network for the Gradle dist + AGP.
3. **Build → Build APK** (debug). The app has **no external Maven deps**, so it
   builds offline once the toolchain is present.
3. Install the APK, open **SVN Agent** → grant Shizuku, then **ENGINE** to
   download + launch the on-device opencode runtime.
4. New in this build: the **TERMUX** drawer cell (see below).

To pull just the latest changes: open the repo in A-IDE → Git → Pull (branch
`main`). The previous state is preserved on the `old` branch if you ever need
to compare or roll back.

## Build notes

- **Toolchain**: A-IDE friendly. Java sources only use the platform SDK
  (`android.*`, `java.*`) — no androidx/appcompat, no external Maven deps.
  Build in A-IDE with **Android Gradle Plugin 3.5.3** / Gradle 6.1.1
  (the wrapper properties + `apply plugin` style match the buildscript
  classpath approach; `plugins {}` DSL is NOT used for that reason).
- minSdk 21 / targetSdk 33. Compiles fine offline.
- A command-line compile check (no SDK build-tools needed) if you just want to
  verify the Java: `javac -source 8 -target 8 -cp android.jar app/src/main/java/com/svndev/svnagent/*.java`.

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
- `app/src/main/java/com/svndev/svnagent/McpToolsActivity.java`
  **MCP/TOOLS** screen: remote MCP servers (toggle + cached tool sublist) and
  on-device local tools (toggle). See `AddServerActivity` / `AddToolActivity`.
- `app/src/main/java/com/svndev/svnagent/McpStore.java`
  JSON persistence for servers + local tools (`filesDir/mcp.json`); renders the
  opencode `mcp` block via `enabledServersAsMcp()`.
- `app/src/main/java/com/svndev/svnagent/McpClient.java`
  Remote MCP client (`initialize` → `tools/list`) over JSON/SSE HTTP.
- `app/src/main/java/com/svndev/svnagent/McpConfigWriter.java`
  Writes `etc/mcp.json` into the workspace via Shizuku for `OPENCODE_CONFIG`.
- `app/src/main/java/com/svndev/svnagent/LocalMcpServer.java`
  App-hosted localhost MCP server that serves the enabled `DeviceTools`.
- `app/src/main/java/com/svndev/svnagent/DeviceTools.java`
  Catalog of on-device tool definitions (device functions, Termux, installed
  apps).
- `app/src/main/java/com/svndev/svnagent/TermuxActivity.java`
  **TERMUX** screen: detect/install Termux, walk the `RUN_COMMAND` permission
  setup, and host the interactive in-app terminal (see below).
- `app/src/main/java/com/svndev/svnagent/TermuxManager.java`
  Bridge to Termux via `com.termux.RUN_COMMAND` (fire-and-collect-result) plus
  the `termux:run` shell helper.
- `app/src/main/java/com/svndev/svnagent/TermuxSession.java`
  Interactive session: starts a loopback `socat` PTY in Termux and pipes the
  socket to `TerminalView`.
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
4. **Launch** directly out of the workspace — direct exec, **no `sh -c`
   wrapper** (a wrapping bionic shell would preload the glibc shim into itself
   and die with “cannot link executable”):
   ```
   /data/local/tmp/svnagent_opencode/opencode web --hostname 127.0.0.1 --port 4096
   ```
   with `LD_PRELOAD=.../shim/claude-resolvfix.so`, `LD_LIBRARY_PATH=.../glibc/lib`,
   `SSL_CERT_FILE=.../etc/cert.pem`, `CLAUDE_RESOLV_REDIRECT=.../etc/resolv.conf`
   (rewritten each launch with the device's live DNS servers), `HOME=.../home`.
   Stale engines from prior launches are reaped by `pidof opencode` before start.

   `web` serves both the HTTP API **and** the browser UI on the same port. The
   drawer's **UI** cell opens that UI in an **embedded `WebView`**
   (`WebUiActivity`) at `http://127.0.0.1:4096/` — no external browser. The
   server is **not** password-protected: it binds localhost-only, and a
   password breaks the page's own async API calls (the WebView answers the
   main-frame challenge but not the fetch/SSE ones → "Cannot connect to API").

### Background persistence (foreground service)

The engine is owned by **`EngineService`**, a foreground service — *not* by an
Activity. This is what keeps it alive when you leave the app.

Why it's needed: the engine is spawned via Shizuku and its stdout/stderr are
piped back into the app process. Without a foreground service Android free-runs
the app in the background and eventually kills the process (cached-app reclaim),
which tears down `EngineLauncher` (a process-wide singleton) and the pipes — so
the engine dies and the next visit has to relaunch it, losing the session.

- `EngineActivity` handles **onboarding only** (Shizuku, permission, download,
  staging). Once staged it just calls `EngineService.start(ctx)` and then
  **observes** the run: it renders `EngineConsole.snapshot()` and subscribes to
  live output. It never owns the launch.
- `EngineService` does the prelaunch (resolver refresh, net probe, local MCP
  server + MCP config) and the launch, mirroring all output into the shared
  **`EngineConsole`** ring buffer. It shows an ongoing "Engine running"
  notification and returns `START_STICKY`.
- If the engine exits unexpectedly the service relaunches it automatically
  (bounded, `MAX_RESTARTS`) while the service is alive. Explicit teardown is
  `EngineService.stop(ctx)`.
- First run asks once for a **battery-optimization exemption** — the foreground
  service covers stock Android, but aggressive OEM power managers can still
  freeze a backgrounded app.

Net effect: open the engine once, leave the app, come back — the engine (and its
session) is still there; nothing to restart.

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

## MCP / TOOLS

The drawer's **MCP/TOOLS** cell manages what the agent can reach beyond the
model:

- **MCP servers** — remote HTTP MCP endpoints (like `https://svn-dev.online/mcp`).
  `+ ADD SERVER` opens a form (name / URL / optional auth header + value);
  `SAVE & TEST` performs the MCP handshake and caches the exposed tools, which
  are listed under the server. Each server has an enable toggle.
- **Local tools** — on-device actions (open URL, share, dial, compose SMS,
  camera, clipboard read/write, vibrate, toast, open settings) plus every
  launchable app. `+ ADD TOOL` opens a picker; checked tools become
  agent-callable, each with an enable toggle.

Mechanism: `McpConfigWriter` renders the enabled state into
`/data/local/tmp/svnagent_opencode/etc/mcp.json` (staged as the shell user via
Shizuku) and the engine launches with `OPENCODE_CONFIG` pointing at it — opencode
**merges** it, so the model config is preserved. Enabled local tools are served
to the engine by `LocalMcpServer`, a dependency-free MCP server the app hosts on
`http://127.0.0.1:<local_mcp_port>/mcp`, registered in the config as
`svn_local`. Toggles take effect on the **next engine start**.

## TERMUX

The drawer's **TERMUX** cell connects the agent to a locally-installed
[Termux](https://f-droid.org/packages/com.termux/) app — a real Linux
environment on the phone. This is the agent's escape hatch for anything the
sandboxed app can't do: local file access, `ssh`, `git`, `pkg install`, scripts,
and even `adb`/`rish` when wireless debugging is looped back.

One-time setup (the cell walks you through it):

1. **INSTALL TERMUX** (F-Droid; also from f-droid.org).
2. In Termux, allow external apps and reload:
   `echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings`
2. **GRANT RUN_COMMAND PERMISSION** in this panel.
3. **TEST CONNECTION**, then **START / RESTART SESSION**.

How it works: two apps can't share a uid, so the only bridge is Termux's
`com.termux.RUN_COMMAND` service (`TermuxManager` sends the intent, attaches a
`Result` `PendingIntent`, and reads back exit code + stdout/stderr). **One-shot**
commands go through `TermuxManager.runShellSync`. The **interactive terminal**
runs `socat` inside Termux to expose a login shell on `127.0.0.1:9001`
(auto-installs `socat` on first run); the app connects with a plain socket and
pipes it into `TerminalView` (`TermuxSession`).

The agent gets it as a local tool: **`termux:run`** (category *Termux*) — enable
it in **MCP/TOOLS → + ADD TOOL**. It runs a shell command in Termux and returns
the output, so the agent can do files, SSH, `pkg`, and (with wireless debugging
looped back) `adb`/`rish` without leaving the app.

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

## Change log

Newest first. Each entry is a commit on `main`; `old` holds the state before
the MCP/Tools work.

- **engine: background persistence via foreground service** — the engine used to
  die whenever the app was backgrounded (Android reclaimed the process, taking
  `EngineLauncher`'s pipes with it). New **`EngineService`** (foreground, ongoing
  notification, `START_STICKY`) now owns prelaunch + launch and binds no UI, so
  it survives leaving the app; `EngineActivity` only observes. Added
  **`EngineConsole`** (shared ring buffer) so output survives Activity teardown
  and is re-rendered on return, a bounded auto-relaunch if the engine exits, and
  a one-time **battery-optimization exemption** prompt. `OpenCodeService` is the
  legacy stub (superseded).
- **termux: local Linux shell bridge + agent tool** — new **TERMUX** drawer
  cell. Detect/install Termux (F-Droid), grant `com.termux.permission.RUN_COMMAND`,
  and start an interactive in-app terminal backed by a real Termux shell
  (`TermuxManager` fires `com.termux.RUN_COMMAND` with a result `PendingIntent`;
  `TermuxSession` runs a loopback `socat` PTY on `127.0.0.1:9001`, installing
  socat on first use). Adds the `termux:run` **local tool** so the agent can run
  commands in Termux (files, ssh, git, `pkg`, and adb/rish via wireless
  debugging) — it appears under **MCP/TOOLS** local tools. Manifest gains the
  RUN_COMMAND permission + package visibility (QUERY_ALL_PACKAGES) so the app can
  detect Termux. *(not yet device-tested)*
- **mcp/tools: add MCP servers + on-device local tools sections** — new
  **MCP/TOOLS** drawer cell. Remote HTTP MCP servers (add form with
  name/url/optional auth, `SAVE & TEST` handshake caching the tool list,
  per-server toggle + cached tool sublist). Local tools: curated device
  functions (open URL, share, dial, SMS, camera, clipboard r/w, vibrate,
  toast, settings) plus every launchable app, picked via **+ ADD TOOL**, each
  with a toggle. `McpConfigWriter` writes `etc/mcp.json` and the engine runs
  with `OPENCODE_CONFIG` (opencode merges it; the model config is preserved);
  `LocalMcpServer` serves enabled tools to the engine at `127.0.0.1` as
  `svn_local`. *(not yet device-tested)*
- **engine: fix getaddrinfo (root cause of "Cannot connect to API")** —
  glibc bakes `resolv.conf`/`nsswitch.conf`/`hosts` paths into `libc.so.6` and
  reads them via non-interposable syscalls, so the `LD_PRELOAD` DNS shim never
  applied and every model call failed. Fixed by binary-patching those three
  path strings in `libc.so.6` to `/data/local/tmp/svnagent_opencode/etc/...`.
  Engine bundle v5 (`bundle-v5-resolvpatch`). **Engine now returns real model
  replies end-to-end.**
- **engine: on-device netprobe diagnostic + real DNS detection** — an in-app
  network check that exercises the engine's actual DNS/outbound path, and
  rewrites the workspace `resolv.conf` from the device's live nameservers.
- **EngineLauncher: direct exec, no sh wrapper; prune orphans via pidof** —
  launching through a bionic `sh` wrapper preloaded the glibc shim into the
  shell itself and died (`CANNOT LINK EXECUTABLE`); stay on direct exec and
  reap stale engines before start.
- **Fix bionic poisoning: launch sh with clean env, export glibc vars inside
  script**.
- **Bundle v3: libc.so/lib.mo ld-scripts → symlinks (bad ELF magic); bump
  STAMP**.
- Earlier: Shizuku onboarding + engine download/stage/launch, in-app WebView
  UI, terminal view, persona import (`IMPORT AGENT`), settings screen.