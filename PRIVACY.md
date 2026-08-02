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
for data-access requests (`QuestProgressState.exportPlayer`). No other player's rows
and no shared server-scoped progress are included. The in-game command that surfaces
this export arrives with the quest-book/admin command set (PLAN 0.1.0 Stage 8).

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
