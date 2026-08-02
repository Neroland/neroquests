# Data storage (quest progress)

Quest *definitions* come from datapacks (see [Quest format](Quest-Format.md)). Quest
*progress* — who has done what — is saved in your world. This page covers what NeroQuests
stores, where it lives, and the controls over it.

Everything here is **server-authoritative**: progress only ever changes on the server, and the
client is told what to draw.

## What is stored

| Kind | Keyed by | Contents |
| --- | --- | --- |
| Player progress | the player's Minecraft game UUID | quest ID, one counter per objective, completion timestamp |
| Player activity stamp | the player's Minecraft game UUID | one "last updated" epoch-millisecond timestamp, used only for retention |
| Shared progress | nothing (world-wide) | for `scope: server` quests: quest ID, objective counters, completion timestamp |

That is the complete list. NeroQuests stores **no** names, IP addresses, chat, coordinates or
anything else about a player — nothing beyond the UUID Minecraft already uses.

Objective counters are indexed by the objective's **position** in the quest definition, so a
datapack that *appends* an objective keeps existing progress meaningful. Reordering or removing
objectives in a quest players have already started changes what the stored counters mean —
append rather than reshuffle.

Progress for a quest that no longer exists (a datapack removed it) is simply kept and ignored;
if the quest comes back, so does the progress.

## Where it lives

In the world save, as one vanilla saved-data file (id `neroquests:quest_progress`) in the
overworld's `data/` folder, next to Minecraft's own saved data. It is written by the server
whenever progress changes, saved with the world, and never leaves your machine. Deleting the
world deletes it.

### If the file is damaged

A corrupt or unreadable progress file does **not** crash the server. NeroQuests detects the bad
read, starts from empty progress, and writes a clean file at the next world save. A warning is
logged naming only the storage file and dimension — never a player or a stored value.

## Retention

`questDataRetentionDays` in `config/neroquests.properties` controls how long an inactive
player's progress is kept.

| Value | Behaviour |
| --- | --- |
| `0` (default) | NeroQuests runs no sweep of its own; progress is purged when Neroland Core's `purge-inactive` runs, per Core's `dataRetentionDays`. |
| `1`–`3650` | Any player whose progress has not changed in that many days is purged. The sweep runs once per server session, the first time quest progress is touched. |

The sweep logs only the **number** of records purged. Shared `scope: server` progress belongs to
the world, not to a player, and is never pruned.

## Erasure

NeroQuests registers with Neroland Core's shared per-player erasure hook, so one request clears
a player everywhere in the Neroland ecosystem at once:

- players: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

That drops every quest row and the activity stamp for that UUID. Erasure never logs who was
erased. Shared server-scoped progress is untouched — it holds no player identifiers.

## Export (data access)

NeroQuests can produce a JSON export of **one player's own progress and nobody else's**: their
quest IDs, objective counters and timestamps. No other player's rows and no shared progress are
included, so an export can be handed to the player who asked for it as-is.

```json
{
  "last_updated": 1750000000000,
  "quests": {
    "mypack:chapter1/wake_up": { "counters": [1], "completed_at": 1749990000000, "complete": true },
    "mypack:chapter1/first_steps": { "counters": [3, 0], "completed_at": 0, "complete": false }
  }
}
```

The in-game command that produces this file arrives with the quest-book and admin command set.

## See also

- [Quest format](Quest-Format.md) — the datapack JSON these counters track
- [Telemetry](Telemetry.md) — crash reporting, which never contains quest progress
- [Privacy & data protection](../PRIVACY.md) — the full POPIA/GDPR statement
