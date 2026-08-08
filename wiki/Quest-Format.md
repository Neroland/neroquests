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

An objective is `{ "type": "<id>", ... }`; the remaining fields depend on the type. Seven types ship:

| Type | Does what | Key fields |
| --- | --- | --- |
| `neroquests:collect_item` | Have this many matching items on you | `item` \| `tag`, `count` |
| `neroquests:craft_item` | Make this many matching items | `item` \| `tag`, `count` |
| `neroquests:kill_entity` | Kill this many matching creatures | `entity` \| `tag`, `count` |
| `neroquests:reach_dimension` | Set foot in a dimension | `dimension` |
| `neroquests:gate_open` | A Neroland Core progression gate is open for you | `gate` |
| `neroquests:quest_complete` | Another quest is complete for the same holder | `quest` |
| `neroquests:custom_event` | Another mod reported a tracked quantity crossing a threshold | `channel`, `direction`, `audience`, `count` |

**[Objectives](Objectives.md) documents each one in full** — every field, how progress moves, what
happens on a server-scoped quest, and how objectives referencing an absent mod degrade rather than
blocking the quest.

Two things worth knowing before writing a quest file:

- Item and entity objectives take **exactly one** of an id or a tag. Declaring both, or neither, is
  an error and drops the quest.
- An objective naming content this installation does not have (an unknown item, an empty tag, a
  dimension whose mod is missing, a `custom_event` channel whose mod is missing) never blocks the
  quest. It follows the server's `missingModObjectivePolicy` setting — `skip` (ignore it) or
  `autocomplete` (count it as done) — and is logged once, by resource id.

### `neroquests:custom_event` — reacting to another mod's world state

`gate_open` waits on **your** progress. `custom_event` waits on the **world's**: a region's pollution
passing its event threshold, a colony's life support failing or recovering, a boss changing phase.
The publishing mod fires a *threshold crossing* on Neroland Core's shared event bus and NeroQuests
listens. Neither mod imports the other — both depend only on Core — so a quest can be written against
a mod that is not even installed.

```json
{
  "type": "neroquests:custom_event",
  "channel": "nerocolonies:oxygen",
  "direction": "rising",
  "audience": "world"
}
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `channel` | id | **yes** | — | The quantity to watch, as `<modid>:<channel>` (see below). |
| `event_scope` | string | no | any | Exact match on the crossing's scope key — *where* it crossed (a colony id, a region key, a dimension id). Named `event_scope` so it can never be confused with the quest's own `scope`. |
| `direction` | `rising` \| `falling` \| `any` | no | `any` | Which way the value went. |
| `min_value` | integer | no | none | The crossing's value must be at least this (inclusive). |
| `max_value` | integer | no | none | The crossing's value must be at most this (inclusive). |
| `count` | integer ≥ 1 | no | `1` | How many matching crossings are needed. |
| `audience` | `world` \| `everyone` | no | `world` | Who the crossing is credited to (see below). |

A crossing carries exactly five things — channel, scope key, value, threshold and direction — and
those are the only things you can match on. There is no player in it, deliberately: **a crossing
names a place or a system and never a person**, which is a privacy rule the publishing mods are held
to, not an oversight.

Note that **`rising` does not mean "good"**. Core defines it as "the value crossed upward", so it is
recovery on `nerocolonies:oxygen` and a *worsening* on `nerotech:pollution`. Check the channel before
you assume.

#### The `<modid>:<channel>` convention

A channel id is namespaced by the mod that publishes it, and NeroQuests relies on that: the namespace
is how it works out whether the publisher is installed at all. A channel from a mod that is not
present can never fire, so the objective is treated as **missing content** and degrades under
`missingModObjectivePolicy` exactly like an unknown item id — instead of leaving the quest stuck
forever. Use a namespace that is a real mod id, or the degradation cannot work.

#### Broadcast versus player-scoped (`audience`)

Because a crossing has no player attached, something has to decide who hears it. Getting this wrong
would let one colony's good news quietly complete a personal quest for a player who was asleep on the
other side of the world, so the quest author has to say which they meant:

| `audience` | Who is credited | Use it for |
| --- | --- | --- |
| `world` (default) | The quest's **shared** counter, **once** per crossing, however many players were online. | World news. Requires `"scope": "server"`. |
| `everyone` | **Every online player** whose copy of the quest is available and unfinished. | An event the whole server should get personal credit for. Players offline at that moment miss it. |

The default is the conservative one. `world` needs a shared counter, and only a `"scope": "server"`
quest has one — so a `custom_event` objective left on the default inside a `"scope": "player"` quest
could never advance. Rather than let that ship, the loader **drops the quest** and names the reason
in `/neroquests reload-check`. Either give the quest server scope, or write `"audience": "everyone"`
to say you really do mean everybody.

Progress is a tally of crossings, never a re-measure: there is no way to ask the world how many times
something has already happened, so a crossing that nobody was online for is simply missed — the same
way a kill nobody made credits nobody.

#### Channels that exist today

Every channel below is published by a shipping Neroland mod. None of them is required: a quest naming
one on a server without that mod degrades rather than blocking.

| Channel | Published by | Scope key is | Value is | `rising` means |
| --- | --- | --- | --- | --- |
| `nerotech:pollution` | Nerotech | A packed region key | The region's pollution after the change | The region crossed **above** its `pollutionEventThreshold` (worse) |
| `nerocolonies:oxygen` | NeroColonies | The colony id | `1` holding, `0` failed | Life support came **back up** |
| `nerocolonies:food_stock` | NeroColonies | The colony id | The colony's stored rations | The colony **stopped** starving |
| `nerocolonies:morale` | NeroColonies | The colony id | Morale, 0–100 | Work **resumed** (morale back above the work-stop threshold) |
| `nerocolonies:structures` | NeroColonies | The colony id | How many structures the colony has now built | Always `true` — this one only ever fires on a completion |
| `nerocreatures:boss_pressure` | NeroCreatures | The dimension id | The boss's current phase number, or `0` on defeat | A phase **advanced**; `falling` is the boss being **defeated** |

Two caveats worth reading before you build content on these:

- **NeroColonies publishes only on an actual change of state.** A colony that has been starving for
  an hour fires nothing further; you get the crossing when it starts and when it recovers. That is
  what makes these usable as quest triggers rather than a firehose.
- **Publishers can be switched off.** NeroColonies gates all four of its channels behind its own
  `thresholdEventsEnabled` config key, and Nerotech's pollution channel goes quiet when
  `pollutionEventThreshold` is `0`. A silent publisher is not the same as an absent one: the mod is
  installed, so the objective does **not** degrade — it simply waits.

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
| An objective that could never advance at the quest's `scope` — today, a `custom_event` on `audience: world` inside a `scope: player` quest | The **whole quest** is dropped, with the reason spelled out. |
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
