# Quest format (datapack JSON)

NeroQuests is **datapack-driven**. Every quest and every chapter of the quest book is a JSON
file, so modpacks and servers can add, change or remove content without touching code — and
without NeroQuests shipping any content of its own yet.

Files live in a datapack (or inside another mod's `data/` folder):

```text
data/<namespace>/neroquests/quests/<path>.json     -> quest   <namespace>:<path>
data/<namespace>/neroquests/chapters/<path>.json   -> chapter <namespace>:<path>
```

The **id comes from the file path**, never from the file body. `data/mypack/neroquests/quests/
chapter1/first_steps.json` defines the quest `mypack:chapter1/first_steps`. Ship a file with an
id that already exists and yours replaces it — that is how you override a quest.

Definitions are read from the server's datapacks and cached. Everything is
**server-authoritative**: the client only ever displays what the server sends it.

## Quest file

```json
{
  "title": "quest.mypack.first_steps.title",
  "description": "quest.mypack.first_steps.desc",
  "icon": "minecraft:iron_pickaxe",
  "prerequisites": ["mypack:chapter1/wake_up"],
  "objectives": [
    { "type": "neroquests:quest_complete", "quest": "mypack:chapter1/wake_up" }
  ],
  "rewards": [
    { "type": "neroquests:xp", "amount": 50 }
  ],
  "scope": "player",
  "visible_gate": "nerolandcore:industrial_power"
}
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `title` | string | yes | — | Display name. A **translation key** (put the text in a lang file); a literal string also renders, but ships untranslated. |
| `description` | string | no | `""` | Longer text in the quest detail panel. Also a translation key. |
| `icon` | item id | no | `minecraft:book` | Item shown as the quest's node icon. |
| `prerequisites` | list of quest ids | no | `[]` | The quest stays locked until all of these are complete. |
| `objectives` | list of objectives | **yes** | — | Must contain at least one entry. All must be done to complete the quest. |
| `rewards` | list of rewards | no | `[]` | Granted once, on completion. |
| `scope` | `player` \| `server` | no | `player` | `player` — everyone completes it for themselves. `server` — one shared completion for the whole world. |
| `visible_gate` | gate id | no | none | A Neroland Core progression gate. While it is closed the quest is hidden entirely. |

## Chapter file

A chapter is one page of the quest book: a title, an icon, and the quests placed on it.

```json
{
  "title": "chapter.mypack.groundwork.title",
  "icon": "minecraft:crafting_table",
  "quests": [
    { "quest": "mypack:chapter1/wake_up",     "x": 0, "y": 0 },
    { "quest": "mypack:chapter1/first_steps", "x": 2, "y": 0 },
    { "quest": "mypack:chapter1/shelter",     "x": 2, "y": 2 }
  ]
}
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `title` | string | yes | — | Chapter tab name (translation key). |
| `icon` | item id | no | `minecraft:written_book` | Item shown on the chapter tab. |
| `quests` | list of entries | no | `[]` | The quests on this page, in listing order. |

Each entry is `{ "quest": <quest id>, "x": <int>, "y": <int> }`. `x`/`y` default to `0` and are
abstract grid units, not pixels, so the book can scale the layout.

**Dependency lines are not stored.** The book draws them from each quest's `prerequisites`, so a
chapter layout can never contradict the real progression graph. Position the nodes; the lines
follow.

A quest should appear in **one** chapter only. If two chapters list the same quest, the
alphabetically first chapter id keeps it and the duplicate entry is dropped with a warning.

## Objective types

An objective is `{ "type": "<id>", ... }`; the remaining fields depend on the type.

### `neroquests:quest_complete`

Satisfied once another quest is complete for the same holder (the player, or the server for a
server-scoped quest).

```json
{ "type": "neroquests:quest_complete", "quest": "mypack:chapter1/wake_up" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `quest` | quest id | yes | The quest that must be complete. |

This is not the same as a prerequisite: a prerequisite decides whether a quest is *available*,
while this objective is one of the boxes to tick *inside* an already-available quest.

> **More objective types are coming.** Collecting and crafting items (by id or by tag), killing
> entities, reaching a dimension, and opening a Neroland Core progression gate all land with the
> objective engine in a later release. Their JSON follows the same `type` + fields shape, so
> chapters and quests written today keep working.

## Reward types

A reward is `{ "type": "<id>", ... }`; the remaining fields depend on the type.

### `neroquests:xp`

Grants raw experience points on completion.

```json
{ "type": "neroquests:xp", "amount": 50 }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `amount` | integer ≥ 1 | yes | Experience points granted. `0` or negative is rejected. |

> **More reward types are coming.** Item stacks, opening a progression gate, currency and
> reputation payouts arrive with the reward engine. Rewards that need a sibling mod (currency,
> reputation) will degrade to a no-op with a log line when that mod is absent, never a crash.

## What happens to a broken file

NeroQuests **never crashes on bad content**. Every problem is written to the server log at
`WARN` level naming the offending resource id, and the smallest sensible thing is dropped:

| Problem | What happens |
| --- | --- |
| Malformed JSON, or a field of the wrong type | That quest/chapter is dropped. |
| An objective or reward `type` that is not registered | The **whole quest** is dropped (it could never be completed, or would pay out something unintended). |
| `objectives` empty or missing | The quest is dropped. |
| `prerequisites` names a quest that does not exist | That **prerequisite is ignored**; the quest itself is kept. |
| Prerequisite cycle (A needs B needs A), including a quest requiring itself | Every quest in the cycle — and every quest behind it — is dropped, because none of them could ever unlock. |
| A chapter lists a quest that does not exist (or was dropped above) | That **entry is dropped**; the chapter is kept. |
| The same quest listed in two chapters | The first chapter by id order keeps it; the duplicate entry is dropped. |

Log lines contain resource ids only — never player names, UUIDs or any other personal data
(see [`../PRIVACY.md`](../PRIVACY.md)).

## Reloading

Definitions are read from the running server's datapacks and cached for that server, matching
how Neroland Core loads its progression gates. A full reload path for `/reload` is wired up with
the rest of the quest engine; until then, restarting the world always picks up changed files.

When definitions change, existing progress is kept as-is. Progress recorded against a quest that
no longer exists is simply inert — it is not deleted, and it comes back if the quest returns.

## See also

- [Home](Home.md)
- [Telemetry](Telemetry.md)
- [Privacy & data protection](../PRIVACY.md)
