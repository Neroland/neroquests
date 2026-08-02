# Objectives

An objective is one box to tick inside a quest. A quest completes when **all** of its objectives
reach their target, at which point its rewards pay out.

Every objective is a JSON object with a `type` and whatever fields that type needs:

```json
{ "type": "neroquests:collect_item", "item": "minecraft:iron_ingot", "count": 10 }
```

Everything on this page is evaluated **on the server**. The client only ever displays what the
server tells it, so no amount of client-side fiddling can move a counter.

## How progress moves

Objectives advance in one of two ways, and knowing which is which explains most of their
behaviour:

| Style | How it moves | Can it go down? | Types |
| --- | --- | --- | --- |
| **Measured** | Recounted from the world as it is right now | Only `collect_item` | `collect_item`, `reach_dimension`, `gate_open`, `quest_complete` |
| **Counted** | Adds up as events happen | No | `craft_item`, `kill_entity` |

A **measured** objective is recomputed rather than tallied, which makes it impossible to farm — you
cannot cycle the same ten iron ingots through your inventory and be credited twenty. The trade-off
is that `collect_item` falls back when you spend the items; the other measured types are milestones
and stay ticked once reached.

A **counted** objective is a running total that nothing takes away. Craft the items, then use them:
the progress stands.

Measuring runs on a **once-a-second sweep**, and again immediately after a craft or a kill. Because
recounting is idempotent, that cadence only affects how quickly the quest book catches up — never
what the answer is.

## Objective types

### `neroquests:collect_item`

**Have** this many matching items on you at once.

```json
{ "type": "neroquests:collect_item", "item": "minecraft:iron_ingot", "count": 10 }
{ "type": "neroquests:collect_item", "tag": "c:ingots/iron", "count": 10 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `item` | item id | one of | — | A single item. Mutually exclusive with `tag`. |
| `tag` | item tag id | one of | — | Any item in the tag (no `#` prefix). Mutually exclusive with `item`. |
| `count` | integer ≥ 1 | no | `1` | How many are needed. |

Exactly one of `item` / `tag` must be given; declaring both, or neither, drops the quest with a
warning.

Counted slots are the whole player inventory: hotbar, main inventory, off-hand and worn equipment.
Chests, shulker boxes and backpacks are **not** counted — you have to be carrying it. Give the
items away and the counter drops again.

### `neroquests:craft_item`

**Make** this many matching items. Same fields as `collect_item`.

```json
{ "type": "neroquests:craft_item", "tag": "c:ingots/iron", "count": 32 }
```

"Craft" means taking the output of a result slot, which covers the crafting grid, a
furnace/smoker/blast-furnace output, the stonecutter, the smithing table and a villager trade. That
is deliberate: a quest asking for 20 iron ingots crafted should be satisfied by smelting them.

The tally never falls; spending what you made does not undo the progress.

### `neroquests:kill_entity`

Kill this many matching creatures.

```json
{ "type": "neroquests:kill_entity", "entity": "minecraft:zombie", "count": 10 }
{ "type": "neroquests:kill_entity", "tag": "minecraft:skeletons", "count": 10 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `entity` | entity type id | one of | — | A single entity type. Mutually exclusive with `tag`. |
| `tag` | entity type tag id | one of | — | Any entity type in the tag. Mutually exclusive with `entity`. |
| `count` | integer ≥ 1 | no | `1` | How many kills are needed. |

The kill goes to whoever the game credits with it: the attacker if that is a player, otherwise the
player behind an arrow, a pet or another indirect kill. A creature that dies of fall damage, lava or
old age credits nobody.

### `neroquests:reach_dimension`

Set foot in a dimension.

```json
{ "type": "neroquests:reach_dimension", "dimension": "nerospace:greenxertz" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `dimension` | dimension id | yes | The dimension to visit. |

Sticky: it ticks the first time you are seen there and stays ticked after you leave. This is how a
quest reacts to another mod's worlds — a Nerospace planet, a modded Nether analogue — without
NeroQuests depending on that mod at all.

### `neroquests:gate_open`

A **Neroland Core progression gate** must be open for you.

```json
{ "type": "neroquests:gate_open", "gate": "nerolandcore:reached_orbit" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `gate` | gate id | yes | The Core progression gate that must be open. |

Sticky, and picked up the moment Core reports the change rather than on the next sweep.

This is the ecosystem's universal join. Every Nero mod drives Core's gates as a player passes its
own milestones, so a quest can require "has reached orbit" or "has founded a colony" while
NeroQuests depends on nothing but Core.

Not to be confused with a quest's `visible_gate`, which hides the entire quest until the gate opens;
this is one tick-box inside an already-visible quest.

A gate id that no mod defines simply never opens — Core treats an unknown gate as a closed
player-scope gate, so there is no missing-content degradation here (see below). Check your gate ids.

### `neroquests:quest_complete`

Another quest must be complete for the same holder.

```json
{ "type": "neroquests:quest_complete", "quest": "mypack:chapter1/wake_up" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `quest` | quest id | yes | The quest that must be complete. |

Not the same as a prerequisite: a prerequisite decides whether a quest is *available*, while this is
one of the boxes to tick *inside* an already-available quest.

Completing a quest re-checks every other quest that names it, so finishing one can finish the next
in the same instant. Quest completion never reverses, so these chains always settle.

## Server-scoped quests

A quest with `"scope": "server"` keeps **one shared set of counters for the whole world**. Any
player's action advances them, and the quest completes once, for everyone.

Counted objectives simply add up across everybody. Measured objectives behave slightly differently
there: one player's snapshot may only ever *raise* shared progress, never lower it — otherwise the
first player to spend their iron would undo what the server had collectively gathered.

## Missing content (other mods not installed)

A quest that names an item, entity or dimension this installation does not have would otherwise be
uncompletable forever. Instead it **degrades**, controlled by the server-authoritative
`missingModObjectivePolicy` config key:

| Value | Behaviour |
| --- | --- |
| `skip` (default) | The objective is ignored — it neither blocks the quest nor shows progress. |
| `autocomplete` | The objective counts as done and its counter is filled in, so the book shows it ticked. |

Either way the quest stays completable, and the offending objective is logged once per server run
naming only resource ids — never player data.

An objective counts as "missing content" when its item or entity id is unregistered, its tag
resolves to nothing, its dimension is not loaded, or the quest it points at no longer exists. Note
that a quest whose objectives *all* degrade completes as soon as it becomes available; that is the
intended consequence of `skip`.

`gate_open` is the one exception — Core resolves any gate id, so an unknown gate is simply a closed
gate rather than missing content.

## See also

- [Quest format](Quest-Format.md) — the quest and chapter files these objectives live in
- [Data storage](Data-Storage.md) — where the counters are kept, and how they are erased
- [Home](Home.md)
