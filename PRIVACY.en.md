# Privacy Policy — Lunar Gold

Last updated: 2026-11-11

> 中文版：[PRIVACY.md](PRIVACY.md)

Lunar Gold is free, open-source software. This policy describes what the desktop
application and the optional web edition do with data. It matches the shipped
code; if you build from source, the same behavior applies.

## Summary

- **Desktop edition: no telemetry, no analytics, no crash uploads, no account.**
  Your projects, conversations, knowledge bases, uploads and database stay on
  your machine.
- The desktop app contacts the network only for (a) update checks against
  GitHub Releases, (b) requests to the model/embedding providers you configure,
  (c) built-in web tools when you invoke them, and (d) MCP servers you add.
- Model API keys are encrypted on disk with a locally generated key and are sent
  only to the provider you configured — never to the project maintainers.
- Local diagnostics are written to disk only. Exporting a diagnostic bundle
  requires you to pick a save location; nothing is uploaded automatically.

## Desktop edition

### Data stored on your device

The desktop app keeps its data in `%USERPROFILE%\.lunargold\desktop` by default
(override with the `DESKTOP_DATA_DIR` environment variable):

| Path | Contents |
|---|---|
| `db/` | Embedded PostgreSQL data: conversations, messages, projects, knowledge-base metadata and vectors |
| `aes.key` | AES-256-GCM key generated on first run, used to encrypt model API keys at rest |
| `local-token` | Random token that lets the desktop shell talk to its local backend |
| `sidecar.log` | Backend log |
| `diagnostics/reports/*.json` | Local diagnostic reports (at most 10, oldest pruned) |
| `diagnostics/session.json` | Crash marker, deleted on a clean exit |
| `upgrade-state.json` | Temporary marker written during an upgrade |

Related locations:

- `%USERPROFILE%\.lunargold\updater.log` — auto-update log.
- `%USERPROFILE%\.lunargold\backup\<old-version>-<timestamp>` — pre-upgrade
  backups of the data directory; the two most recent are kept.
- `uploads\documents` and `uploads\chat-attachments` under the data directory
  (by default `%USERPROFILE%\.lunargold\desktop\uploads`) — knowledge-base
  files and chat attachments.

You can inspect, back up or delete any of these files at any time. Deleting the
data directory resets the app.

### Local diagnostics

When the app fails (Electron main process, renderer, Java backend, embedded
PostgreSQL or port binding), it shows a diagnostic card and writes a report.
Reports use a fixed field whitelist (version, OS, error code, sanitized summary
and log tail). Before anything is written or exported, the app redacts API keys,
tokens, `Bearer` headers, JWTs, credentials embedded in URLs, user paths and
`LocalTokenService` token log lines.

The **Export diagnostic bundle** button produces a ZIP containing only
`report.json`, the tail of `sidecar.log` (at most 400 lines / 512 KB) and the
tail of `updater.log`. The ZIP is saved wherever you choose. It never contains
source code, conversations, knowledge bases, the database, model keys,
`local-token` or `aes.key`, and the app has no code path that uploads it.

### Network connections

1. **Update checks.** `electron-updater` queries the public GitHub Releases for
   `nichengyv/lunar-gold`, about 8 seconds after startup (adjustable with the
   `LUNARGOLD_UPDATE_INITIAL_DELAY_MS` environment variable). GitHub receives
   standard HTTP request metadata (such as your IP address and user agent).
   Updates are downloaded and installed only after you agree, and you can choose
   "remind me later". If the operator sets `LUNARGOLD_UPDATE_MODE=auto`, updates
   are downloaded and installed without prompts; that override is off by default
   and is meant for managed installations. No update check happens while running
   from source.
2. **Model and embedding providers.** Prompts, selected code context,
   attachments and tool results needed for a request are sent to the endpoint
   you configure (for example DeepSeek, OpenAI, Zhipu, Moonshot, SiliconFlow).
   Your API key is sent to that provider for authentication and is stored
   encrypted locally. Those providers handle data under their own policies.
   Charges billed by providers are between you and them.
3. **Built-in web tools.** When you use them, the relevant query or URL is sent
   directly to the third-party service: web search to Tavily (with your own
   Tavily key), weather to `wttr.in` and Open-Meteo, and page fetching to the URL
   you specify. Results are returned to the model as tool output.
4. **MCP servers.** If you configure MCP servers — including local stdio servers
   you add in Settings — the app starts and connects to them as configured. Those
   servers may contact their own services; their behavior is outside this policy.
   User-added server configurations are stored in the local database, their
   environment variables are encrypted with a local key, and Settings only shows
   variable names, never values. Commands run locally with your user privileges.

The desktop edition does **not** send usage analytics, telemetry, crash reports,
device identifiers or advertising data to the maintainers.

### Uninstall and data retention

- Uninstalling removes the program files, including the bundled runtime. The
  data directory (including its `uploads` folder) is **kept**.
- The data directory under `%USERPROFILE%\.lunargold` (database, `aes.key`,
  `local-token`, logs, diagnostic reports) and the `backup` folder are **kept**
  so that reinstalling preserves your work. Delete that folder manually to
  remove everything.
- Diagnostic reports are kept locally up to 10 files and can be deleted at any
  time.

## Web edition

The web edition is an optional, separately deployed service built from the same
code base:

- **Account data.** We store your email address and a salted password hash
  (BCrypt) to provide registration, login and password recovery. No profile,
  phone number or payment data is required.
- **Content.** Conversations, knowledge bases and uploaded documents are stored
  on the server to provide the service. They are not sold, and are not used to
  train models.
- **No tracking.** No advertising networks, cross-site trackers or third-party
  analytics are embedded.
- **Model calls.** Requests are sent to the model provider configured for that
  deployment; in BYOK mode, the key you enter is used for your requests.

To request export or deletion of web account data, open an issue at
<https://github.com/nichengyv/lunar-gold/issues> from the address associated with
the account, or use any contact channel published with that deployment.

## Security

- Model API keys are encrypted at rest with AES-256-GCM and a key that never
  leaves your machine (desktop).
- The desktop shell talks to its backend over `127.0.0.1` and every request must
  carry the per-install `local-token`, which is stored in your user data
  directory.
- Web deployments are expected to serve traffic over HTTPS and to keep
  credentials in environment variables, not in the repository.

No method of storage or transmission is perfectly secure; keep your own backups
of important projects.

## Children

The service is not directed at children. Do not use it if you are below the age
at which you can consent to data processing in your jurisdiction.

## Changes

Material changes to this policy will be announced in release notes and reflected
in the "Last updated" date above.

## Contact

Questions or requests: open an issue at
<https://github.com/nichengyv/lunar-gold/issues>.
