# Lunar Gold (金月)

A free, open-source AI coding agent that runs locally, with approval-gated commands and per-turn revert.

> 中文版：[README.md](README.md)

Lunar Gold is a desktop AI agent for Windows. It can read and write files in your project, execute commands, and integrate Git, MCP tools and LSP diagnostics. Every destructive action requires your explicit approval, and every change can be reverted turn by turn. A web edition shares the same code base; the desktop edition needs no account and works with your own model key.

## Highlights

- **Local-first** — the app bundles its own embedded database (PostgreSQL + pgvector) and a private Java runtime. Projects, conversations, knowledge bases and model keys stay on your machine; there is no telemetry or crash upload.
- **Controllable** — command execution is approval-gated; file deletions and rollbacks are explicit and reversible.
- **No lock-in** — Apache-2.0 licensed, no subscription, no account required on desktop.

## Download & Install

Download `LunarGold-Setup-x.y.z.exe` and `SHA256SUMS.txt` from the [GitHub Releases](https://github.com/nichengyv/lunar-gold/releases) page.

Requirements: Windows 10/11 x64.

### 1. Verify the download

```powershell
Get-FileHash .\LunarGold-Setup-x.y.z.exe -Algorithm SHA256
```

The printed hash must match the value in `SHA256SUMS.txt` and in the release notes. If it does not match, delete the file and download it again.

### 2. Run the installer

The installer is per-user by default (no administrator rights needed) and lets you choose the installation directory.

**Installers are currently unsigned.** Windows SmartScreen may show "Unknown publisher" (`未知发布者`); choose "More info → Run anyway" only after verifying the SHA-256 above. We applied to the [SignPath Foundation](https://signpath.org/foundation) free open-source signing program and were declined for now (the program requires a higher level of public visibility); we will reapply as the project grows.

### 3. First launch

The first start initializes the embedded database and may take a few seconds. No account is required. Application data is stored in `%USERPROFILE%\.lunargold\desktop` (override with the `DESKTOP_DATA_DIR` environment variable).

The app checks GitHub Releases for updates about 8 seconds after startup and asks before downloading anything.

### Uninstall

Use Windows Settings → Apps. Uninstalling removes program files, including the bundled runtime and chat/knowledge-base uploads inside the installation directory. **Your data directory `%USERPROFILE%\.lunargold` is preserved** (database, encryption key, local token, logs, diagnostics, upgrade backups); delete it manually to remove everything.

## Privacy and network use

The desktop edition has no telemetry, analytics or crash uploads. Network access happens only when:

- checking for updates against GitHub Releases;
- calling the model or embedding provider you configured (your API keys are encrypted locally and sent only to that provider);
- you invoke a built-in web tool (web search, weather, page fetch);
- you add MCP servers that connect out.

Local diagnostic reports stay on disk; exporting a bundle asks for a save location and uploads nothing. Details: [PRIVACY.md](PRIVACY.md).

## Troubleshooting

If the app fails to start or the window shows a diagnostic card:

1. **Export the diagnostic bundle** from the card (or reproduce the failure and export from the next card). The ZIP contains a whitelisted `report.json` plus redacted log tails only.
2. Check the logs directly:
   - Backend: `%USERPROFILE%\.lunargold\desktop\sidecar.log`
   - Updater: `%USERPROFILE%\.lunargold\updater.log`
   - Diagnostics: `%USERPROFILE%\.lunargold\desktop\diagnostics\reports\`
3. Common cases:
   - **"Unknown publisher" / SmartScreen** — expected for the current unsigned builds; verify the SHA-256 and allow the run.
   - **Window closes immediately or stays on the loading page** — the backend failed to start; read the tail of `sidecar.log` and use the card's "Retry" action.
   - **Port unavailable** — the app picks a new local port on retry; make sure security software is not blocking `127.0.0.1`.
   - **Database initialization failed** — check free disk space and write permissions on the data directory, then retry.
   - **Installer blocked by antivirus** — verify the hash first, then restore from quarantine or add an exclusion for the installer.
   - **Reset the app** — quit it, then delete `%USERPROFILE%\.lunargold` (data, keys, logs, diagnostics and backups). Chat and knowledge-base uploads live in the `uploads` folder of the installation directory and must be deleted separately.

## Compliance

- [LICENSE](LICENSE) — Apache-2.0
- [PRIVACY.md](PRIVACY.md) — privacy policy, network boundaries and data retention
- [TERMS.md](TERMS.md) — terms of service
- [NOTICE](NOTICE) — third-party components and licenses
- [SBOM.json](SBOM.json) — CycloneDX software bill of materials (243 Java backend components)
- The installed `resources/legal` directory and every Release also include `SBOM.frontend.json` (frontend production dependencies), `SBOM.desktop.json` (desktop shell production dependencies), `SBOM.runtime.json` (the Electron, trimmed JRE, PostgreSQL and pgvector runtimes actually shipped) and `THIRD-PARTY-LICENSES.txt` (full third-party license and copyright texts).

Release artifacts: every GitHub Release publishes the installer, `latest.yml`, a blockmap for differential updates, `SHA256SUMS.txt` and the compliance files above.

## Building from Source

Requirements: Windows x64, JDK 21, Maven, Node.js 22.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-desktop-installer.ps1
```

The script builds the frontend, runs the backend test suite, trims a private JRE, packs the Electron shell and produces the NSIS installer under `release/installer/`. PostgreSQL and pgvector runtime inputs are built by `scripts/build-pg-runtime.ps1` and `scripts/build-desktop-runtime.ps1`.

To regenerate the compliance artifacts after changing dependencies (Java SBOM, frontend/desktop/runtime SBOMs and the third-party license bundle):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-compliance.ps1
```

(CycloneDX includes the resolved test-scope graph, so a few test-only artifacts appear in `SBOM.json`; they are not shipped in the installer.)

## Documentation

- [Docs index & writing conventions](docs/README.md)
- [Update plan & roadmap](docs/UPDATE_PLAN.md)
- [Completed work archive](docs/COMPLETED_WORK.md)
- [Windows packaging troubleshooting](docs/troubleshooting/windows-nsis.md)

## License

[Apache-2.0](LICENSE) — Copyright 2026 杨俊懿 (Junyi Yang)
