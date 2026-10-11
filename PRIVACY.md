# NeroQuests — Privacy & Data Protection

NeroQuests is designed to comply with POPIA and GDPR. This document describes what
player data the mod stores and how players and server admins control it.

## Every store at a glance

The only player identifier NeroQuests ever stores is the player's existing Minecraft game UUID — never
a name, IP address, chat line or coordinates.

| Store | Where | Purpose | Player identifier | Retention | Export | Erasure |
| --- | --- | --- | --- | --- | --- | --- |
| Quest progress (counters, completions, paid ledger, revoke markers, repeat stamps, daily contracts) | world file `neroquests:quest_progress` + Core backup | run quests; pay each reward once | game UUID | inactivity sweep / Core purge | yes | yes |
| Owed and kept rewards | inside quest progress | hand over rewards that could not be delivered | game UUID | until delivered, or with the player's data | yes | yes |
| Payout journal | `data/neroquests_payout_journal.json` | crash-safe credit payouts | game UUID | seconds (removed when the economy answers) | not needed (transient) | yes |
| Custom quests (`created_by`) | world file `neroquests:custom_quests` + backup | run player-made quests; show "you" to the author | game UUID of the author | until the quest is deleted | yes (own quests) | `created_by` cleared, quest kept |
| Editor allow-list and lock | inside the custom-quest file | decide who may author | game UUID | until removed by an operator | yes (on the list or not) | yes |
| World completion counts | inside quest progress | completion signals for other mods | none | kept | — | — |
| Seen event channels, rate-limit buckets, seen chapters, post-erasure hold | memory only | editor channel picker, flood limits, app alerts, erasure safety | channels: none; others: game UUID | until the player leaves or the server stops | — | never saved |

## What is stored

Quest progress only, keyed by the player's existing Minecraft game UUID:

- quest IDs the player has started or completed
- objective counters (e.g. items collected toward a goal)
- completion timestamps
- currency rewards still **owed** to the player (an economy refused them for now): the source
  quest or contract id, currency, amount, reason code, request key, first-attempt time and retry
  count — paid and removed automatically once the economy can cover them
- item and experience rewards **kept** for the player (they were offline, dead or had no inventory
  room): the source quest id, item id, count and the item's component data (or the experience
  amount), request key and time — handed over and removed on their next join, respawn or claim
- daily contracts: the UTC day and the ids of the contracts handed in that day
- the **paid ledger**: the ids of the quests whose rewards have already been paid, so a quest can
  never pay twice
- **revoked** quests: for a quest an operator revoked, the objective counters at that moment, so it
  is not re-completed until the player's progress changes
- **repeat timestamps**: for a repeatable custom quest, when it last paid the player, so it pays at
  most once per cooldown

No names, IP addresses, chat, coordinates history, or any other personal information
is stored — nothing beyond what Minecraft itself already keeps.

World-wide, with no player identifier at all: how many times each quest and each quest-book chapter
has been completed on the server. When a quest completes, NeroQuests announces that anonymously to
other mods on Neroland Core's event bus (the quest or chapter id and that world count — never who). A
quest pack's `mod_hook` reward opens a Neroland Core progression gate for the completing player; that
gate row is kept, exported and erased by Neroland Core like every other gate.

### Economy integration

With an economy mod installed (NeroEconomy), NeroQuests pays credit rewards through its budgeted
payout API and counts trades for `neroquests:economy` objectives. Payout request keys are
`nq:<sequence>:<quest or contract id>` and never contain a player id or name; NeroQuests never logs a
player id or name with a payment. Trade events carry only your own side of a trade (never the other
party); NeroQuests counts them against your quest progress and keeps nothing else. What the economy
itself records about these payouts is described in that mod's own privacy document.

While a payout is being made, NeroQuests writes it — your game UUID, the amount, reason code, source
quest or contract id and request key — to a small journal file (`neroquests_payout_journal.json` in the
world's `data/` folder) and deletes the entry as soon as the economy has answered. It exists so a server
crash can neither pay a reward twice nor lose it: an entry left behind by a crash is settled on the next
start and then removed. The file is normally absent or empty, and an erasure removes your entries from it.

Owed rewards, daily-contract records, the paid ledger and revoked markers are part of your quest
data: they are included in the export below and removed by the same erasure and retention controls.
After an erasure the server additionally remembers, in memory only and until you next join, that
your quests must not be re-evaluated — so erasing an online player does not immediately recreate
their data. Operator edits (`grant`, `revoke`, `reset`, `export`) and `manual complete` ticks are
logged as counts only, never with who ran them or whom they concerned.

### Custom quests (in-game editor)

Players who may author can make their own quests in game. These live in a separate world file
(`neroquests:custom_quests` in the overworld's `data/` folder, with a recovery backup like the progress
file). It holds:

- the custom chapters and quests themselves — titles, descriptions and labels the author wrote, icons,
  layout, objectives, rewards, repeat settings, timestamps and escrow counts;
- **`created_by`** on each quest: the author's game UUID — the only player identifier those rows add.
  It is used so the author can be shown "you" (everyone else sees "another player"; it is never turned
  into a name), and so it can be exported and erased;
- the **editor allow-list**: game UUIDs an operator allowed to author, and whether the editor is locked.

Purpose: running the quests the server's players made, and deciding who may make them. Retention: until
the quest is deleted (by an author or an operator) or the player is removed from the list; the quests are
world content, so the inactivity sweep does not delete them. Author-written text is shown only as plain
text, is never written to the server log, is never included in crash reports and is sent nowhere
except to the players (and their paired companion app) who can see that quest. An export of custom
quests as a datapack (`/neroquests custom export`) contains no `created_by` and nothing else about any
player.

## Erasure

NeroQuests registers with Neroland Core's shared per-player data-erasure hook. A single
request purges the player across all Nero mods, including NeroQuests:

- players: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

Erasure never logs player identity. Custom quests the player authored are kept as world content with
their `created_by` cleared, and the player is removed from the editor allow-list.

The quest data has a second copy: Neroland Core's last-known-good **backup** of the store
(`data/neroquests_quest_progress_backup.dat` in the overworld folder), used to recover from a corrupt
file. Every erasure, `/neroquests reset <player>` and retention purge refreshes that backup at once,
so the player is removed from both copies in the same request. If the main file is ever found
corrupt, it is copied aside as `quest_progress.corrupt-<time>` for the operator before recovery; such
a file cannot be read by the mod, so erasure cannot reach it — operators should inspect and then
delete it.

## Retention

Quest progress for inactive players is purged automatically, two ways:

- **Core-driven** — when Core's `purge-inactive` runs, the erasure hook above clears
  the named players' quest progress along with every other Nero mod's data.
- **NeroQuests' own sweep** — set `questDataRetentionDays` in `neroquests.properties`
  to a number of days and NeroQuests prunes every player record that has not been active
  within that window (joining the server counts as activity; online players are never pruned).
  The sweep runs when quest progress is first touched after start-up and then once per in-game
  day, and logs only *how many* records were pruned — never who. `0` (the default) disables
  the sweep and follows Core's `dataRetentionDays` only.

Each player row carries a single "last updated" timestamp for exactly this purpose;
shared server-wide quest progress carries no player identifiers and is never pruned. Custom quests and
the editor allow-list are not pruned by inactivity (see above); erasure reaches them.

## Access / export

NeroQuests can produce an admin-safe JSON export of **a single player's own quest
data and no one else's** — their quest IDs, objective counters and timestamps, owed and kept
rewards, paid ledger, repeat timestamps, the custom quests they authored (with their own text) and
whether they are on the editor allow-list — for data-access requests. No other player's rows and no
shared server-scoped progress are included.

- admins: `/neroquests export <player>` (permission level 3, run by a player or the console), where `<player>` is an
  online player's name or a raw UUID, so a player who has left the server can still be
  exported. The JSON is printed to the operator who ran the command and to nobody else.
- in code: `QuestProgressState.exportPlayer(server, uuid)` and `CustomQuestState.get(server).export(uuid)`.

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

Since 0.4.0 the app can also see the rewards kept for you (type, amount or item, the quest they came
from, when they were kept) and whether you may author custom quests (yes/no only). Custom quest titles
and descriptions — text written by players — reach only players who can see that quest.

NeroQuests may raise two **app alerts** for you, stored by Neroland Core's alert store under your game
UUID and erased with the rest of your data by Core's shared erasure hook: "A reward is waiting for you"
and "A new chapter appeared". Their text is fixed — never a quest title, amount or name. To notice a new
chapter, NeroQuests remembers which chapters your quest book showed **in memory only**, while you are
online; it is never saved and is forgotten when you log off.

Erasure and retention need no separate handling here: every read goes to the live progress store,
so once your data is erased (see above) there is nothing left for the link to return.

Details: [`wiki/Link-Module.md`](wiki/Link-Module.md).

## Held in memory only

- **Seen event channels:** the ids of `custom_event` channels other mods fired this session (at most
  256), so the editor can offer them. Channel ids only — never who fired them. Cleared when the server
  stops.
- **Editor and `manual complete` rate limits:** a per-player counter keyed by game UUID, never logged
  or saved, dropped when idle and when the server stops.
- **Seen chapters** for the "A new chapter appeared" alert and the **post-erasure hold** described
  above: in memory while you are online, never saved.

## Telemetry

NeroQuests ships anonymous crash reporting via **Sentry** (EU ingest servers), matching
the rest of the Neroland ecosystem. It is **on by default and opt-out**:

- **Opt out:** set `telemetryEnabled=false` in `config/neroquests.properties`
  (takes effect on restart). This is a client-local setting — a server can never force
  it on or off.
- **NeroQuests-only:** a report is sent only if its stack trace touches
  `za.co.neroland.neroquests`; everything else is dropped before it leaves the game.

### What a report contains

The exception's type and stack frames (class, method, file name and line — **never the
exception's message**, and no file paths); NeroQuests / Minecraft / loader / OS / Java
version strings; the ids and versions of your other installed mods; this mod's own config
values (`gateWritesEnabled`, `missingModObjectivePolicy`, `questDataRetentionDays`); for a
handled command error, the subcommand name; and one anonymous session record per launch
(random session id, start, end, whether it ended in an error) for crash-free-session
statistics. If a fatal log line names NeroQuests without an exception, only the fact that
it happened is sent — its text is replaced by a fixed placeholder. No breadcrumbs and no
performance or timing traces are sent.

### What a report never contains

No IP address, username, player UUID, world name or seed, coordinates, chat, or **any
quest progress**. `sendDefaultPii` is off, the machine hostname is never attached, the
Sentry user object is cleared on every event, exception and log messages are dropped
(they could contain anything), and absolute file paths are removed from stack frames.
NeroQuests uses its own Sentry client and does not install a global one or a JVM-wide
uncaught-exception handler, so it never receives — or changes — another mod's reports. Volume is bounded: events are de-duplicated per session
and capped at 10 per game session.

Player-facing details: [`wiki/Telemetry.md`](wiki/Telemetry.md).

### CurseForge / Modrinth telemetry blurb

Keep the following (or equivalent) in the project description while telemetry is enabled, as
required by CurseForge moderation policy:

> **Telemetry notice:** NeroQuests sends anonymous error reports (stack trace + mod/game
> versions only — never IPs, usernames, UUIDs, quest progress or world data) to the developers
> via Sentry (EU servers) so crashes can be fixed. On by default — opt out any time by setting
> `telemetryEnabled = false` in `config/neroquests.properties`. Full details:
> [PRIVACY.md](https://github.com/Neroland/neroquests/blob/main/PRIVACY.md).

## Contact

Questions, access or erasure requests that a server operator cannot handle, and privacy concerns about
NeroQuests itself: **<info@neroland.co.za>**. Your server's operator is responsible for the data on their
server; NeroQuests' developers never receive quest data.
