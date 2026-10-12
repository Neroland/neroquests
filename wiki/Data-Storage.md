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
| Owed rewards | the player's Minecraft game UUID | currency rewards the economy refused for now: source quest/contract id, currency, amount, reason code, request key, first-attempt time, retry count |
| Daily contracts | the player's Minecraft game UUID | the UTC day of the record and the contract ids handed in that day (older days are overwritten) |
| Shared progress | nothing (world-wide) | for `scope: server` quests: quest ID, objective counters, completion timestamp |
| Once-per-world claims | nothing (world-wide) | which quests' `once_per_world` credit reward has been claimed — no player is recorded |
| Request-key sequence | nothing (world-wide) | one counter used to build payout request keys (`nq:<sequence>:<source id>`), which never hold a player id or name |
| Completion counts | nothing (world-wide) | how many times each quest and each chapter has been completed on this server (at most 4096 ids each) — the value of the anonymous `neroquests:quest_completed` / `chapter_completed` signals |
| Repeat timestamps | the player's Minecraft game UUID | for repeatable custom quests: quest id and when it last paid, so it pays at most once per cooldown |
| Custom quests | the author's game UUID (`created_by`, per quest) | quests and chapters made in game, in a separate file (`neroquests:custom_quests`): titles, descriptions, objectives, rewards, layout, timestamps — see [Editor permissions](Editor-Permissions.md) |
| Editor allow-list | game UUIDs | players an operator allowed to author; plus the editor lock flag |

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

With NeroEconomy installed there is one more, normally empty, file next to it:
`neroquests_payout_journal.json`. A credit payout is written there (player UUID, amount, reason, source
id, request key) before the economy is asked and removed when it answers; one left behind by a crash is
retried with the same key on the next start (never paid twice) and then removed. Erasure clears a
player's entries from it too.

### If the file is damaged

A corrupt or unreadable progress file does **not** crash the server. NeroQuests uses Neroland Core's
recovery: the damaged file is first **copied aside** as `quest_progress.corrupt-<time>` (so nothing is
lost for good), then the store is restored from Core's last-known-good backup
(`data/neroquests_quest_progress_backup.dat`), or starts empty if there is no usable backup, and a
clean file is written at the next save. Warnings name only the storage file and dimension — never a
player or a stored value. Delete the `.corrupt-*` copy once you have looked at it: it still holds
player rows that erasure cannot reach.

### Upgrading from an older version

The progress file carries a format number. On the first load of a file written by 0.3.0-beta.1 or
earlier, NeroQuests upgrades it in place:

- every completed quest is entered into the **paid ledger**, so nothing pays a second time after the
  update;
- progress on `neroquests:space_race/breathing_room`, `full_larder` and `settled_ground` moves to
  `nerocolonies:colony_chronicle/…` (those quests now ship with NeroColonies — see
  [Sibling chapters](Sibling-Chapters.md));
- on the first evaluation each row is stamped with its quest's current objective list, keeping its
  counters. From then on, a row whose quest's objectives change is reset if unfinished.

The upgrade runs once; the file is then saved in the new format.

### Held in memory only

Some things are never saved: the event channels other mods fired this session (ids only, for the
editor's picker), the per-player editor rate limits, which chapters your book has shown (for the
companion-app alert) and the short hold after an erasure. All are dropped when you leave or the server
stops.

## Retention

`questDataRetentionDays` in `config/neroquests.properties` controls how long an inactive
player's progress is kept.

| Value | Behaviour |
| --- | --- |
| `0` (default) | NeroQuests runs no sweep of its own; progress is purged when Neroland Core's `purge-inactive` runs, per Core's `dataRetentionDays`. |
| `1`–`3650` | Any player who has not been active (progress changed or joined the server) in that many days is purged; online players never are. The sweep runs when quest progress is first touched after start-up and then once per in-game day. Rows of quests that are no longer loaded (a removed mod or pack) expire after the same number of days. |

The sweep logs only the **number** of records purged. Shared `scope: server` progress belongs to
the world, not to a player, and is never pruned.

**Purging forgets what was paid, too.** A purge removes the player's paid-reward record along with their
progress — keeping it would mean keeping data about a person the server was told to forget. A purged
player who comes back therefore starts fresh, and quests that are *measured* rather than counted (holding
items, standing in a dimension or structure, an open gate, completed quests) can complete and pay again
on their next check. That is why retention is off by default (`0`); if you turn it on, choose a period
long enough that "come back after N idle days and re-collect" is not worth it on your server.

## Erasure

NeroQuests registers with Neroland Core's shared per-player erasure hook, so one request clears
a player everywhere in the Neroland ecosystem at once:

- players: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

That drops every quest row and the activity stamp for that UUID. Erasure never logs who was
erased. Shared server-scoped progress is untouched — it holds no player identifiers. Custom quests the
player authored stay in the world, with `created_by` cleared, and the player leaves the editor
allow-list; the custom store's backup is refreshed in the same request. An escrow deposit on a quest
whose author was erased is discarded when the quest's rewards change or it is deleted (it is never handed
to someone else).

Erasure also removes the paid-reward record (see Retention above), so measured quests can pay an erased
player again if they return. Privacy wins here by design.

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

Operators produce it in-game with `/neroquests export <player>` — an online player's name, or a
UUID for someone who has left. The JSON is printed to whoever ran the command and to nobody else.
See [Commands](Commands.md).

The export also lists the custom quests the player authored and whether they are on the editor
allow-list.

The same tree also holds the per-player admin controls: `/neroquests reset <player>` wipes one
player's stored progress, and `/neroquests revoke <player> <quest>` clears a single quest.

## See also

- [Commands](Commands.md) — the `/neroquests` operator tree, including `export` and `reset`
- [Quest format](Quest-Format.md) — the datapack JSON these counters track
- [Telemetry](Telemetry.md) — crash reporting, which never contains quest progress
- [Privacy & data protection](../PRIVACY.md) — the full POPIA/GDPR statement
