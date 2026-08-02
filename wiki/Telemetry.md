# Telemetry (anonymous crash reporting)

NeroQuests sends **anonymous crash reports** so bugs get fixed without anyone having to
file a report by hand. It is on by default, it is disclosed here and in
[`PRIVACY.md`](../PRIVACY.md), and you can **turn it off**.

Reports go to **Sentry**, on EU ingest servers.

## How to opt out

Set this in `config/neroquests.properties` (created on first launch):

```properties
telemetryEnabled=false
```

Restart the game. That silences everything — including the developer's own dev runs.

The setting is **client-local**: it is not server-authoritative, so a server you join can
never switch your crash reporting on or off. Every other NeroQuests config value the
server does dictate; this one is yours.

## What is sent

Only when NeroQuests itself is involved in the failure — a report is dropped unless its
stack trace touches `za.co.neroland.neroquests`. Crashes from other mods are never sent.

- the stack trace of the error
- version strings: NeroQuests, Minecraft, your loader (Fabric / Forge / NeoForge), OS, Java
- the ids and versions of your other installed mods (public manifest values), to spot
  mod conflicts
- NeroQuests' own config values (e.g. `gateWritesEnabled`)
- breadcrumbs — a short trail of what the mod was doing just before the error
- anonymous stability and timing samples

## What is never sent

- your IP address
- your username, player UUID, or any player identity
- your machine's hostname or OS account name (home-directory paths are rewritten to `/~`)
- world name, seed, coordinates, or chat
- **your quest progress** — none of the data described in [`PRIVACY.md`](../PRIVACY.md)
  "What is stored" ever leaves your machine

## Volume

Identical errors are reported once per game session, and no more than **10** reports are
sent in a session at all.

## See also

- [`PRIVACY.md`](../PRIVACY.md) — full data-protection statement (POPIA / GDPR), including
  quest-progress storage, erasure and retention
- [Home](Home.md)
