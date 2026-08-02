# NeroQuests — Privacy & Data Protection

NeroQuests is designed to comply with POPIA and GDPR. This document describes what
player data the mod stores and how players and server admins control it.

## What is stored

Quest progress only, keyed by the player's existing Minecraft game UUID:

- quest IDs the player has started or completed
- objective counters (e.g. items collected toward a goal)
- completion timestamps

No names, IP addresses, chat, coordinates history, or any other personal information
is stored — nothing beyond what Minecraft itself already keeps.

## Erasure

NeroQuests registers with Neroland Core's shared per-player data-erasure hook. A single
request purges the player across all Nero mods, including NeroQuests:

- players: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

Erasure never logs player identity.

## Retention

Quest progress for inactive players is purged automatically, two ways:

- **Core-driven** — when Core's `purge-inactive` runs, the erasure hook above clears
  the named players' quest progress along with every other Nero mod's data.
- **NeroQuests' own sweep** — set `questDataRetentionDays` in `neroquests.properties`
  to a number of days and NeroQuests prunes every player record whose progress has not
  changed within that window. The sweep runs once per server session, the first time
  quest progress is touched, and logs only *how many* records were pruned — never who.
  `0` (the default) disables the sweep and follows Core's `dataRetentionDays` only.

Each player row carries a single "last updated" timestamp for exactly this purpose;
shared server-wide quest progress carries no player identifiers and is never pruned.

## Access / export

NeroQuests can produce an admin-safe JSON export of **a single player's own quest
progress and no one else's** — their quest IDs, objective counters and timestamps —
for data-access requests. No other player's rows and no shared server-scoped progress
are included.

- admins: `/neroquests export <player>` (permission level 2), where `<player>` is an
  online player's name or a raw UUID, so a player who has left the server can still be
  exported. The JSON is printed to the operator who ran the command and to nobody else.
- in code: `QuestProgressState.exportPlayer(server, uuid)`.

The matching admin controls are `/neroquests reset <player>` (wipe one player's quest
progress) and `/neroquests reset <player> <quest>` / `/neroquests revoke <player> <quest>`
(one quest only). Command output is never broadcast to other operators, which also keeps
it out of `latest.log` under the `logAdminCommands` game rule. See
[`wiki/Commands.md`](wiki/Commands.md).

## Companion app (link module)

NeroQuests can expose your quest progress to a **Neroland companion app** through Neroland Core's
link API. NeroQuests ships no server, no HTTP and no outbound connection of its own — it only
registers what it is able to show; a separate bridge mod serves that to a paired app, and **that
pairing is the consent step**. With no bridge installed, nothing is exposed.

What an app can see is **your own quest data only**: your quest IDs, objective counters and
completion timestamps, plus shared server-wide quest progress, which carries no identifiers.
Never another player's rows, never names, never coordinates. Quests hidden behind an unopened
progression gate are filtered out server-side, so the link shows exactly what you would see in the
quest book.

Erasure and retention need no separate handling here: every read goes to the live progress store,
so once your data is erased (see above) there is nothing left for the link to return.

Details: [`wiki/Link-Module.md`](wiki/Link-Module.md).

## Telemetry

NeroQuests ships anonymous crash reporting via **Sentry** (EU ingest servers), matching
the rest of the Neroland ecosystem. It is **on by default and opt-out**:

- **Opt out:** set `telemetryEnabled=false` in `config/neroquests.properties`
  (takes effect on restart). This is a client-local setting — a server can never force
  it on or off.
- **NeroQuests-only:** a report is sent only if its stack trace touches
  `za.co.neroland.neroquests`; everything else is dropped before it leaves the game.

### What a report contains

Stack trace; NeroQuests / Minecraft / loader / OS / Java version strings; the ids and
versions of your other installed mods; this mod's own config values; recent in-game
NeroQuests actions (breadcrumbs); anonymous stability and timing data.

### What a report never contains

No IP address, username, player UUID, world name or seed, coordinates, chat, or **any
quest progress**. `sendDefaultPii` is off, the machine hostname is never attached, the
Sentry user object is cleared on every event, and file paths are scrubbed of your OS
account name before sending. Volume is bounded: events are de-duplicated per session
and capped at 10 per game session.

Player-facing details: [`wiki/Telemetry.md`](wiki/Telemetry.md).
