# Quest format (datapack JSON)

NeroQuests is **datapack-driven**. Every quest and every chapter of the quest book is a JSON
file, so modpacks and servers can add, change or remove content without touching code. The
built-in [story chapters](Story-Chapters.md) are written in exactly this format and carry no
special status — they ship in the `neroquests` namespace, and a pack overrides or removes them the
same way it would anyone else's content.

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

An objective is `{ "type": "<id>", ... }`; the remaining fields depend on the type. Six types ship:

| Type | Does what | Key fields |
| --- | --- | --- |
| `neroquests:collect_item` | Have this many matching items on you | `item` \| `tag`, `count` |
| `neroquests:craft_item` | Make this many matching items | `item` \| `tag`, `count` |
| `neroquests:kill_entity` | Kill this many matching creatures | `entity` \| `tag`, `count` |
| `neroquests:reach_dimension` | Set foot in a dimension | `dimension` |
| `neroquests:gate_open` | A Neroland Core progression gate is open for you | `gate` |
| `neroquests:quest_complete` | Another quest is complete for the same holder | `quest` |

**[Objectives](Objectives.md) documents each one in full** — every field, how progress moves, what
happens on a server-scoped quest, and how objectives referencing an absent mod degrade rather than
blocking the quest.

Two things worth knowing before writing a quest file:

- Item and entity objectives take **exactly one** of an id or a tag. Declaring both, or neither, is
  an error and drops the quest.
- An objective naming content this installation does not have (an unknown item, an empty tag, a
  dimension whose mod is missing) never blocks the quest. It follows the server's
  `missingModObjectivePolicy` setting — `skip` (ignore it) or `autocomplete` (count it as done) —
  and is logged once, by resource id.

## Reward types

A reward is `{ "type": "<id>", ... }`; the remaining fields depend on the type. Five types ship:

| Type | Pays out | Key fields |
| --- | --- | --- |
| `neroquests:item` | A stack of items (overflow drops at your feet) | `item`, `count` |
| `neroquests:xp` | Raw experience points | `amount` |
| `neroquests:gate` | Opens a Neroland Core progression gate | `gate` |
| `neroquests:currency` | Money, via Core's currency contract | `currency`, `amount` |
| `neroquests:reputation` | Standing with a faction (may be negative) | `faction`, `amount` |

**[Rewards](Rewards.md) documents each one in full** — every field, how a payout runs, what happens
on a server-scoped quest, and how a reward needing an absent sibling mod degrades to a no-op instead
of failing.

Three things worth knowing before writing a quest file:

- Rewards are granted **once**, on completion, in the order they are listed, each isolated: one
  reward that fails never stops the rest and never undoes the completion.
- A reward that cannot pay out — no economy mod for `currency`, no faction mod for `reputation`, an
  unregistered item id, `gateWritesEnabled=false` for `gate` — grants nothing and logs once. The
  quest still completes.
- On a `scope: server` quest, the player-targeted rewards go to the **triggering** player; only
  `neroquests:gate` takes the server-wide path.

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

You do not have to read the log to find these, though: `/neroquests reload-check` re-reads the
packs and prints the same list in chat, each entry marked `dropped` or `ignored`. See
[Commands](Commands.md).

## Reloading

Definitions are read from the running server's datapacks and cached for that server, matching
how Neroland Core loads its progression gates. A vanilla `/reload` is picked up automatically
within a second, and `/neroquests reload-check` forces a re-read on demand and reports what it
found; restarting the world of course also picks up changed files.

When definitions change, existing progress is kept as-is. Progress recorded against a quest that
no longer exists is simply inert — it is not deleted, and it comes back if the quest returns.

## See also

- [Objectives](Objectives.md)
- [Rewards](Rewards.md)
- [Data storage](Data-Storage.md)
- [Home](Home.md)
- [Telemetry](Telemetry.md)
- [Privacy & data protection](../PRIVACY.md)
