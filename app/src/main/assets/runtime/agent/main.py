#!/usr/bin/env python3
"""SVN agent backend — runs inside the app via the bundled Python runtime.

This is the extensible core: replace/adjust it to be the real agent logic.
The app calls it with --init on first run, and the terminal "UI" can invoke
it interactively (stdin/stdout) — or call opencode, which is bundled under
assets/runtime/opencode/.

Usage:
  python agent/main.py --init     # first-run setup, writes agent state
  python agent/main.py --version  # smoke test
  python agent/main.py            # interactive REPL over stdin/stdout
"""

import json
import os
import sys


def home() -> str:
    base = os.environ.get("SVN_HOME") or os.path.expanduser("~")
    return os.path.join(base, ".svnagent")


def do_init() -> int:
    h = home()
    os.makedirs(h, exist_ok=True)
    state = os.path.join(h, "agent.json")
    if not os.path.exists(state):
        with open(state, "w") as f:
            json.dump({"initialized": True, "runtime": "python-contained"}, f)
    print("agent initialized @ %s" % h)
    return 0


def do_version() -> int:
    print("sy.svnagent python backend 1.0 (bundled python %s)" %
          (sys.version.split()[0],))
    return 0


def interactive() -> int:
    print("SVN agent backend ready. Type a directive (Ctrl-D to quit).")
    sys.stdout.flush()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line.lower() in ("quit", "exit"):
            print("bye")
            sys.stdout.flush()
            return 0
        # TODO: hand the directive to the bundled opencode CLI here.
        print("[agent] received: %s" % line)
        sys.stdout.flush()
    return 0


def main() -> int:
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg == "--init":
            return do_init()
        if arg == "--version":
            return do_version()
    return interactive()


if __name__ == "__main__":
    sys.exit(main())