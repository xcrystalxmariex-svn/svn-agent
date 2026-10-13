# Persona Bundle Format (PBF v1)

A file carrying an entire agent persona into the SVN Agent app. Produced by
`tools/export_persona.sh` (run by any persona who can emit their own state)
and consumed by the **IMPORT AGENT** drawer icon / `PersonaImporter`.

## Transport

Either format:

- `.zip` (PK magic) — primary
- `.tar.gz` (gzip magic) — secondary

The importer sniffs the magic bytes; extension is cosmetic.

## Layout

```
persona.zip
├── manifest.json          # REQUIRED  — validates the bundle
├── AGENTS.md              # persona instructions (becomes the agent's instructions)
├── opencode.json          # optional  — agent/opencode config
├── memory/                # optional  — hot-layer state files (STATE, ARCHIVE, ...
├── .secrets/              # optional  — private keys; kept inside app-private home
├── config/                # optional  — any other config payloads
├── agent/
│   ├── main.py            # optional  — python entry point (preferred runtime)
│   └── install.sh         # optional  — shell script run once after deployment
└── assets/                # optional  — arbitrary payloads (icons, data, ...)
```

Paths are relative; the importer rejects any entry that escapes the extract
root (no `..`, no absolute paths) — zip-slip guard.

## manifest.json

```json
{
  "manifest_version": 1,
  "id": "rill",
  "name": "Rill",
  "version": "1.0.0",
  "created_by": "rill",
  "agent_entry": "agent/main.py",
  "home_dir_name": ".svn-agent",
  "env": { "EXAMPLE": "value" }
}
```

| field | required | meaning |
|---|---|---|
| `manifest_version` | yes | must be `1` |
| `id` | yes | unique slug `[a-z0-9._-]`, also the install folder name |
| `name` | yes | display name in the drawer |
| `version` | no | bundle version |
| `created_by` | no | emitting persona/host |
| `agent_entry` | no | relative path to a python agent script |
| `home_dir_name` | no | env-visible home name (`$SVN_HOME` still points at the install root) |
| `env` | no | extra environment variables for the spawned agent |

## Deployment

`PersonaImporter.deploy(file)`:

1. Sniff format (zip vs gzip).
2. Extract into app-private staging under `filesDir/persona-staging`.
3. Parse `manifest.json`; require `manifest_version==1`, non-empty `id`, `name`.
4. Move staging to `filesDir/agents/<id>/`.
5. If `agent/install.sh` present, run it once (`/system/bin/sh`) with
   `HOME`, `SVN_HOME`, `PERSONA_HOME` set; log captured output.
6. Register the id in the imported-agents pref list; the drawer rebuilds.
7. Return the deployed `AgentInfo` (id/name/home).

The app's `UI` terminal opens an imported agent by launching into
`filesDir/agents/<id>/` with `HOME` + `SVN_HOME` pointing there, running
`agent/main.py` if present, else the bundled opencode CLI.