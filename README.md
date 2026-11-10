# Lunar Gold (金月)

A free, open-source AI coding agent that runs fully locally, with approval-gated commands and per-turn revert.

Lunar Gold is a desktop AI agent for Windows. It can read and write files in your project, execute commands, and integrate Git, MCP tools and LSP diagnostics. Every destructive action requires your explicit approval, and every change can be reverted turn by turn. A web edition shares the same code base; the desktop edition works fully offline and requires no account (you bring your own model key).

## Highlights

- **Fully local** — the desktop app bundles its own embedded database (PostgreSQL + pgvector) and a private Java runtime. One installer, no extra setup.
- **Controllable** — command execution is approval-gated; file deletions and rolls back are explicit and reversible.
- **No lock-in** — Apache-2.0 licensed, no subscription, no account required on desktop.

## Download & Install

Download the Windows installer (`LunarGold-Setup-x.y.z.exe`) from the [GitHub Releases](https://github.com/nichengyv/lunar-gold/releases) page.

- Every release ships with its SHA-256 checksum; verify after download.
- **Installers are currently unsigned.** Windows SmartScreen may show "Unknown publisher" (`未知发布者`); choose "More info → Run anyway" after verifying the SHA-256. We applied to the [SignPath Foundation](https://signpath.org/foundation) free open-source signing program and were declined for now (the program requires a higher level of public visibility); we will reapply as the project grows.
- Current platform: **Windows x64**. macOS and Linux packages are planned.

## Privacy

The desktop edition stores all data locally and sends nothing anywhere. See [PRIVACY.md](PRIVACY.md).

## Building from Source

Requirements: Windows x64, JDK 21, Maven, Node.js 22.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-desktop-installer.ps1
```

The script builds the frontend, runs the backend test suite, trims a private JRE, packs the Electron shell and produces the NSIS installer under `release/installer/`.

## Documentation

- [Update plan & roadmap](docs/UPDATE_PLAN.md)
- [Completed work archive](docs/COMPLETED_WORK.md)
- [Windows packaging troubleshooting](docs/WINDOWS_NSIS_TROUBLESHOOTING.md)

## License

[Apache-2.0](LICENSE) — Copyright 2026 杨俊懿 (Junyi Yang)
