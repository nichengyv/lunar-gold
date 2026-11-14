# Terms of Service — Lunar Gold

Last updated: 2026-11-11

> 中文版：[TERMS.md](TERMS.md)

These terms apply to the Lunar Gold desktop application and, where it is
deployed, the web edition ("the service"). By downloading, installing or using
the service you agree to them. If you do not agree, do not use the service.

## 1. License

Lunar Gold is open-source software licensed under the [Apache License 2.0](LICENSE).
That license governs your rights to use, copy, modify and distribute the software.
Nothing in these terms restricts rights granted by that license. If these terms
conflict with the Apache License 2.0 for the software itself, the license prevails.

The names "Lunar Gold" and "金月", the project logo and the release branding are
not licensed for endorsement purposes; see section 6 of the Apache License 2.0.

## 2. What the service is

Lunar Gold is an AI coding agent. It can read and write files in the projects you
open, run commands, use Git, connect to MCP tools and surface LSP diagnostics.
Commands that modify your system are approval-gated, and changes are grouped by
turn so they can be reverted. The web edition offers chat, knowledge-base
retrieval and attachments and does not operate on your local workspace.

## 3. Your responsibilities

- **Approvals.** You are responsible for reviewing every command and file change
  before you approve it. Approving a step authorizes the agent to execute it.
- **Your content.** You must have the rights needed to submit the code,
  documents and prompts you provide.
- **Backups.** Keep backups of work that matters. The agent and the revert
  feature can make mistakes; reversion is a convenience, not a guarantee.
- **Credentials.** Keep your model API keys, MCP credentials and web account
  password confidential. You are responsible for activity under your account.
- **Lawful use.** Do not use the service to violate applicable law, infringe
  others' rights, distribute malware, or access systems without authorization.

## 4. Third-party services and costs

The service is free; there is no subscription and no payment processing.

You connect it to third parties at your own choice: model and embedding
providers (bring your own key), web search, weather data, page fetching and MCP
servers. Those services are governed by their own terms and privacy policies, and
**you are responsible for any fees they charge**. The maintainers do not bill you
and do not receive your API keys.

## 5. Updates

The desktop app checks for updates from GitHub Releases and asks before
downloading or installing one. You may decline or skip a version. Updates may
change features; release notes describe material changes. You can uninstall at
any time; uninstalling keeps the local data directory, which you can delete
manually (see [PRIVACY.md](PRIVACY.md)).

## 6. Web edition accounts

Where the web edition is deployed, you may need an account to use it. Keep your
credentials secure, do not share accounts, and do not attempt to bypass usage
limits, probe other tenants' data, or disrupt the service. We may suspend or
remove accounts that abuse the service or violate these terms. Account data is
handled as described in [PRIVACY.md](PRIVACY.md).

## 7. No warranty

The service is provided **"as is" and "as available", without warranties of any
kind**, express or implied, including merchantability, fitness for a particular
purpose, title and non-infringement. The maintainers do not warrant that the
service will be error-free, that generated code is correct, or that data loss
will not occur.

## 8. Limitation of liability

To the maximum extent permitted by applicable law, the maintainers and
contributors are not liable for indirect, incidental, special, consequential or
punitive damages, or for lost profits, data, or business interruption arising
from the use of or inability to use the service. This does not exclude liability
that cannot be excluded by law.

## 9. Termination

You may stop using the service at any time. These terms apply while you use it.
Sections 7, 8 and 10 survive termination.

## 10. Changes and governing law

We may update these terms as the project evolves; material changes will be noted
in release notes. Continued use after a change means you accept the updated
terms.

These terms are governed by the laws of the People's Republic of China, without
regard to conflict-of-law rules. Disputes will be submitted to the competent
court at the maintainers' domicile, unless mandatory local consumer law provides
otherwise. The Apache License 2.0 remains governed by its own terms, including
its provisions on jurisdiction and venue.

## Contact

Open an issue at <https://github.com/nichengyv/lunar-gold/issues>.
