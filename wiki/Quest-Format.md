# Quest format (datapack JSON)

This page is **the contract** quest packs are written against: every field of a quest and a chapter,
every objective and reward type, the `custom_event` channel syntax, what NeroQuests publishes when a
quest completes, and the validation rules for each source. If you ship quests inside your own mod,
read [Shipping a quest pack](Shipping-a-Quest-Pack.md) next — it is the step-by-step version.

NeroQuests is **datapack-driven**. Every quest and every chapter of the quest book is a JSON file, so
mods, modpacks and servers add, change or remove content without touching code.

```text
data/<namespace>/neroquests/quests/<path>.json     -> quest   <namespace>:<path>
data/<namespace>/neroquests/chapters/<path>.json   -> chapter <namespace>:<path>
```

The **id comes from the file path**, never from the file body:
`data/mypack/neroquests/quests/start/first_steps.json` defines the quest `mypack:start/first_steps`.
Ship a file with an id that already exists and yours replaces it. Definitions are read from the
server's datapacks; everything is **server-authoritative** and the client only displays what the
server sends.

## Sources

Every quest and chapter has a **source**, decided by its namespace when it loads:

| Source | Namespace | What it is | Editable in game |
| --- | --- | --- | --- |
| `spine` | `neroquests` | NeroQuests' own [story chapters](Story-Chapters.md) | Never |
| `pack` | any other datapack namespace | A mod's quest pack (shipped in that mod's jar) or a server's own datapack | Never |
| `custom` | `custom` | Reserved for quests made in game | Yes, by authorised players |

A datapack file in the `custom` namespace is **dropped** — that namespace belongs to the in-game
store. Spine and pack quests can never be changed, hidden, deleted or re-rewarded at runtime.

## Quest file

```json
{
  "title": "quest.mypack.start.first_steps.title",
  "description": "quest.mypack.start.first_steps.desc",
  "icon": "minecraft:iron_pickaxe",
  "prerequisites": ["mypack:start/wake_up"],
  "objectives": [
    { "type": "neroquests:craft_item", "item": "minecraft:iron_pickaxe" }
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
| `title` | string | yes | — | A **translation key**. Packs must use a key (see [Validation](#validation-rules)); the recommended shape is `quest.<namespace>.<path with / as .>.title`. |
| `description` | string | no | `""` | Longer text in the quest detail panel. A translation key (`….desc`). |
| `icon` | item id | no | `minecraft:book` | Item shown as the quest's node icon. |
| `prerequisites` | list of quest ids | no | `[]` | The quest stays locked until all of these are complete. |
| `objectives` | list of objectives | **yes** | — | At least one. All must be done to complete the quest. |
| `rewards` | list of rewards | no | `[]` | Granted once per player, on completion, in order. |
| `scope` | `player` \| `server` | no | `player` | `player` — everyone completes it for themselves. `server` — one shared completion for the whole world (a "world log" quest). |
| `visible_gate` | gate id | no | none | A Neroland Core progression gate. While it is closed for a player the quest is hidden from them entirely. |

## Chapter file

A chapter is one page of the quest book: a title, an icon, and the quests placed on it.

```json
{
  "title": "chapter.mypack.start.title",
  "icon": "minecraft:crafting_table",
  "show_when_absent": false,
  "allow_server_scope": false,
  "quests": [
    { "quest": "mypack:start/wake_up",     "x": 0, "y": 0 },
    { "quest": "mypack:start/first_steps", "x": 2, "y": 0 }
  ]
}
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `title` | string | yes | — | Chapter tab name — a translation key (`chapter.<namespace>.<path>.title`). |
| `icon` | item id | no | `minecraft:written_book` | Item shown on the chapter tab. |
| `quests` | list of entries | no | `[]` | The quests on this page. Each entry is `{ "quest": <id>, "x": <int>, "y": <int> }`; `x`/`y` default to `0` and are grid units, not pixels. |
| `show_when_absent` | boolean | no | `false` | Packs only. Keep this chapter visible even when the pack's mod is **not** installed: its quests show a "needs a mod that is not installed" badge and can never be completed. |
| `allow_server_scope` | boolean | no | `false` | Packs only. The quests this chapter lists may use `"scope": "server"`. |

**Dependency lines are not stored.** The book draws them from each quest's `prerequisites`, so a
layout can never contradict the real progression graph. A quest should appear in **one** chapter; if
two chapters list it, the alphabetically first chapter id keeps it. A pack chapter may list only its
own namespace's quests.

## Objective types

An objective is `{ "type": "<id>", ... }`. Ten types ship. [Objectives](Objectives.md) has the full
behaviour of each (how progress moves, server scope, missing content).

| Type | Done when | Fields (`*` = required) |
| --- | --- | --- |
| `neroquests:collect_item` | You hold this many matching items (or hand them in) | `item`* \| `tag`*, `count` (1), `consume` (`false`) |
| `neroquests:craft_item` | You crafted this many matching items | `item`* \| `tag`*, `count` (1) |
| `neroquests:kill_entity` | You killed this many matching creatures | `entity`* \| `tag`*, `count` (1) |
| `neroquests:reach_dimension` | You set foot in a dimension | `dimension`* |
| `neroquests:visit_structure` | You stood inside a generated structure | `structure`* \| `tag`* |
| `neroquests:gate_open` | A Neroland Core gate is open for you (read-only) | `gate`* |
| `neroquests:quest_complete` | Another quest is complete for the same holder | `quest`* |
| `neroquests:custom_event` | A mod published a matching threshold crossing | `channel`*, `event_scope`, `direction` (`any`), `min_value`, `max_value`, `count` (1), `audience` (`world`) |
| `neroquests:economy` | You sold, bought or earned on the server economy (needs an economy mod) | `event`* (`sold` \| `bought` \| `earned`), `item` \| `tag`, `count` (1) |
| `neroquests:manual` | A quest author confirmed it for you | `label`* (a translation key shown as the objective's text) |

`*` with `|` means **exactly one** of the two. Tags are written without `#`. Counts are integers ≥ 1.

- **`manual`** never advances by itself. An authorised person ticks it for an online player with
  `/neroquests manual complete <quest> <player>` (see [Commands](Commands.md)) — use it for "build a
  thing" quests the game cannot detect.
- **`visit_structure`** is checked once a second for players who have the quest in progress, against
  the structure pieces of the chunk they stand in. Once visited it stays done.
- **`economy`** loads without an economy mod but never advances; `earned` never counts NeroQuests'
  own payouts.

### `neroquests:custom_event` — channels and the audience rule

A **channel** is a quantity another mod tracks, named `<modid>:<channel>`. When it crosses a threshold
the publisher fires a *threshold crossing* on Neroland Core's event bus, and NeroQuests listens.
Neither mod imports the other.

```json
{ "type": "neroquests:custom_event", "channel": "nerocolonies:oxygen", "direction": "rising" }
```

A crossing carries exactly five things — `channel`, a scope key (*where*: a colony id, a region, a
dimension), `value`, `threshold` and whether it was `rising` — and those are all you can match on.
**A crossing never names a player.** So the quest author must say who hears it:

| `audience` | Who is credited | Needs |
| --- | --- | --- |
| `world` (default) | The quest's **shared** counter, once per crossing | `"scope": "server"` (a pack also needs `allow_server_scope` on its chapter) |
| `everyone` | Every online player who has the quest available and unfinished | — |

A `custom_event` left on the default `world` inside a `"scope": "player"` quest could never advance,
so that quest is dropped with the reason. `rising` means "crossed upward" — recovery on one channel,
worsening on another; read the publisher's documentation. The namespace of the channel must be the
publishing mod's id; for a pack quest the channel counts as present whenever the pack is loaded.

## Reward types

A reward is `{ "type": "<id>", ... }`. Seven types ship. [Rewards](Rewards.md) has the full behaviour
(order, kept rewards, server scope, degradation).

| Type | Pays out | Fields (`*` = required) | Allowed for |
| --- | --- | --- | --- |
| `neroquests:item` | A stack of items | `item`*, `count` (1–2304) | all |
| `neroquests:xp` | Experience points | `amount`* (1–1,000,000) | all |
| `neroquests:currency` | Credits (budgeted when an economy mod is installed) | `amount`* (1–1,000,000), `currency` (`nerolandcore:credits`), `once_per_world` (`false`) | all |
| `neroquests:loot_table` | One roll of a loot table | `table`* | all |
| `neroquests:mod_hook` | Tells your mod "this player finished it" (see below) | `gate`* — `<own namespace>:hook/<id>` | spine and packs |
| `neroquests:gate` | Opens a Neroland Core progression gate | `gate`* | spine only |
| `neroquests:reputation` | Standing with a faction | `faction`*, `amount`* | spine only |

Rewards are paid **once per player per quest** — a revoke and re-completion does not pay again
unless an operator asks for it — and each reward is isolated, so one that fails never stops the rest.

### `neroquests:mod_hook` — your mod's own reward

```json
{ "type": "neroquests:mod_hook", "gate": "mypack:hook/star_chart" }
```

A pack that wants a custom effect (unlock a page, refill a tank, grant a cosmetic) ships a **record
gate** in its own jar, `data/mypack/neroland_gates/hook/star_chart.json`, and names it here:

```json
{ "scope": "player", "title": "Star chart bonus" }
```

The scope must be written out: a Core gate defaults to `server` scope.

On completion NeroQuests opens that gate for the player through Neroland Core (it works for an
offline player too). Your mod listens on Core's gate events — `CoreEvents.onProgression` or
`GateEvents.onChange`, where the change's `target` is the player's UUID string — applies its effect,
and may `close` the gate again to re-arm it for a repeat. Rules:

- The gate must be in the **quest's own namespace** and its path must start with `hook/`.
- It must be a **player-scope gate with no `requires`**. An undefined gate (your mod is not installed)
  or one of the wrong shape is reported once in the log and nothing is written.
- Hook gates are records that **gate nothing**. Never check a hook gate to allow or deny access to
  anything — that is why they are written even when the server sets `gateWritesEnabled=false`.

## Completion signals

Whenever a quest completes, NeroQuests publishes an anonymous crossing on Core's `ThresholdEvents`
bus (publisher `neroquests`). Any mod can listen — you need no dependency on NeroQuests:

| Channel | When | `scope` | `value` / `threshold` | `rising` |
| --- | --- | --- | --- | --- |
| `neroquests:quest_completed` | Every completion of any quest | The quest id | How many times that quest has now been completed in this world | `true` |
| `neroquests:chapter_completed` | A completion that finished every quest of its chapter for that player | The chapter id | How many times that chapter has now been completed in this world | `true` |

These say **that** something was completed, never **who**. A server-scope quest completes once, so
it publishes once. Use them for world effects; use `mod_hook` when the effect belongs to the player.

## Validation rules

NeroQuests **never crashes on bad content**. Every problem is logged at `WARN` naming the resource id,
the smallest sensible thing is dropped, and `/neroquests reload-check` lists it with the rule it broke.

### Every source

| Problem | What happens |
| --- | --- |
| Malformed JSON, a wrong field type, or a value outside its range | The quest/chapter is dropped (never half-loaded). |
| An objective or reward `type` that is not registered | The whole quest is dropped. |
| `objectives` empty or missing | The quest is dropped. |
| A `custom_event` on `audience: world` in a `scope: player` quest | The quest is dropped. |
| An id that does not resolve although its mod is loaded (a typo) | The quest is dropped, naming the objective. |
| A `mod_hook` gate outside the quest's namespace or without the `hook/` prefix | The quest is dropped. |
| A prerequisite that does not exist | That prerequisite is ignored; the quest is kept. |
| A prerequisite cycle (including a quest requiring itself) | Every quest in or behind the cycle is dropped. |
| A chapter lists a quest that does not exist (or was dropped) | That entry is ignored; the chapter is kept. |
| The same quest in two chapters | The first chapter by id keeps it. |
| A file in the reserved `custom` namespace | Dropped. |

### Packs (any namespace other than `neroquests`)

| Rule | If broken |
| --- | --- |
| No `neroquests:gate` or `neroquests:reputation` rewards — packs record progress, they never unlock it | Quest dropped |
| `"scope": "server"` only when a chapter **of the same namespace** lists the quest and sets `"allow_server_scope": true` | Quest dropped |
| Prerequisites and `quest_complete` objectives may name only quests of the **same namespace** or the `neroquests` spine | Quest dropped |
| `title` and `description` must be translation keys (lower-case, digits, `_ . -`, at least one dot) — not literal text | Quest dropped |
| A chapter's `title` must be a translation key | Chapter dropped |
| A chapter may list only quests of its own namespace | That entry ignored |

### Custom quests (made in game)

Custom quests come from the world's editor store, not from files, but pass the same validation plus
these rules. A spine or pack quest that breaks the first one is dropped; the rest are checked when an
author saves (see [Editor permissions](Editor-Permissions.md)).

| Rule | If broken |
| --- | --- |
| A spine or pack quest may not depend on a custom quest (prerequisite or `quest_complete`) | That spine/pack quest dropped |
| `scope: player` only; no `visible_gate` | Custom quest dropped |
| 1–12 objectives of `collect_item`, `craft_item`, `kill_entity`, `reach_dimension`, `gate_open`, `quest_complete`, `custom_event`, `visit_structure`, `manual`; counts 1–1,000,000 | Custom quest dropped |
| At most 9 rewards of `item` (one stack each), `loot_table`, `xp`, `currency`, within the server's caps | Custom quest dropped |
| Title, description and manual labels are plain text (sanitised, at most 64 / 512 / 64 characters) | Custom quest dropped |
| A custom chapter lists only custom quests; no other chapter may list one | That entry ignored |

### Packs follow their mod

- A pack whose namespace is a **mod that is not installed** loads nothing — no error, it is a smaller
  install. `reload-check` lists it as "mod not installed".
- With `"show_when_absent": true` on a chapter, that chapter and the quests it lists stay visible with
  a badge, and are never completable.
- A namespace that is **not a mod id** (a server's own datapack) always loads.
- Pack quests never use the `missingModObjectivePolicy=autocomplete` escape hatch: a pack only loads
  when its mod is present, so an absent piece of it is simply skipped.

Log lines carry resource ids only — never player names, UUIDs or other personal data (see
[`../PRIVACY.md`](../PRIVACY.md)).

## Two complete examples

### A player quest (inside a mod's pack)

`data/mypack/neroquests/chapters/start.json`

```json
{
  "title": "chapter.mypack.start.title",
  "icon": "mypack:wrench",
  "quests": [
    { "quest": "mypack:start/first_wrench", "x": 0, "y": 0 },
    { "quest": "mypack:start/workshop",     "x": 2, "y": 0 }
  ]
}
```

`data/mypack/neroquests/quests/start/workshop.json`

```json
{
  "title": "quest.mypack.start.workshop.title",
  "description": "quest.mypack.start.workshop.desc",
  "icon": "mypack:workbench",
  "prerequisites": ["mypack:start/first_wrench", "neroquests:groundwork/first_tools"],
  "objectives": [
    { "type": "neroquests:craft_item", "item": "mypack:workbench" },
    { "type": "neroquests:collect_item", "tag": "c:ingots/iron", "count": 16, "consume": true }
  ],
  "rewards": [
    { "type": "neroquests:xp", "amount": 100 },
    { "type": "neroquests:item", "item": "mypack:wrench", "count": 1 },
    { "type": "neroquests:mod_hook", "gate": "mypack:hook/workshop_blueprint" }
  ]
}
```

`assets/mypack/lang/en_us.json`

```json
{
  "chapter.mypack.start.title": "Getting Started",
  "quest.mypack.start.workshop.title": "Set Up Shop",
  "quest.mypack.start.workshop.desc": "Build a workbench and hand in sixteen iron ingots."
}
```

### A server world-log quest

`data/mypack/neroquests/chapters/world_log.json`

```json
{
  "title": "chapter.mypack.world_log.title",
  "icon": "minecraft:filled_map",
  "allow_server_scope": true,
  "quests": [
    { "quest": "mypack:world_log/first_meltdown_averted", "x": 0, "y": 0 }
  ]
}
```

`data/mypack/neroquests/quests/world_log/first_meltdown_averted.json`

```json
{
  "title": "quest.mypack.world_log.first_meltdown_averted.title",
  "description": "quest.mypack.world_log.first_meltdown_averted.desc",
  "icon": "minecraft:blue_ice",
  "scope": "server",
  "objectives": [
    {
      "type": "neroquests:custom_event",
      "channel": "mypack:reactor_heat",
      "direction": "falling",
      "audience": "world"
    }
  ],
  "rewards": [
    { "type": "neroquests:xp", "amount": 200 }
  ]
}
```

The world completes it once; its player rewards go to every online player who has it available at
that moment, each paid once.

## Reloading

Definitions are cached per server. A vanilla `/reload` is picked up within a second; restarting the
world also picks up changed files. `/neroquests reload-check` reports what the running server loaded,
per source. Existing progress is kept when definitions change; progress for a quest that no longer
exists is inert and comes back if the quest returns.

## See also

- [Shipping a quest pack](Shipping-a-Quest-Pack.md)
- [Objectives](Objectives.md)
- [Rewards](Rewards.md)
- [Commands](Commands.md)
- [Data storage](Data-Storage.md)
- [Home](Home.md)
- [Privacy & data protection](../PRIVACY.md)
