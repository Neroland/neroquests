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
| **Measured** | Recounted from the world as it is right now | Only `collect_item` | `collect_item`, `reach_dimension`, `visit_structure`, `gate_open`, `quest_complete` |
| **Counted** | Adds up as events happen | No | `craft_item`, `kill_entity`, `custom_event`, `economy` |
| **Confirmed** | Ticked by a quest author with a command | No | `manual` |

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
| `consume` | boolean | no | `false` | Hand the items in: the quest takes them when it completes. |

Exactly one of `item` / `tag` must be given; declaring both, or neither, drops the quest with a
warning.

**`"consume": true` turns the objective into a hand-in.** Without it the objective only checks what
you carry, so one stack passed from account to account completes the quest on every one of them.
With it, the quest takes `count` matching items from the completing player at the moment it
completes; every hand-in of a quest is checked first and taken together, so a quest with two
hand-ins never takes one and then fails on the other. The quest book shows these as "Hand in".
Prefer it on any collect-only quest that pays out, or pair the reward with
[`once_per_world`](Rewards.md#neroquestscurrency).

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

Another quest must be complete, checked in the scope of the quest it names — exactly like a
prerequisite: a `scope: player` quest must be complete for the player, a `scope: server` quest for
the world, whatever the scope of the quest that asks.

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

### `neroquests:custom_event`

Another mod reported that one of its tracked quantities crossed a threshold.

```json
{ "type": "neroquests:custom_event", "channel": "nerocolonies:oxygen", "direction": "rising" }
{ "type": "neroquests:custom_event", "channel": "nerocolonies:structures", "min_value": 3,
  "audience": "everyone" }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `channel` | id | yes | — | The quantity to watch, as `<modid>:<channel>`. |
| `event_scope` | string | no | any | Exact match on the crossing's scope key — *where* it crossed. |
| `direction` | `rising` \| `falling` \| `any` | no | `any` | Which way the value went. |
| `min_value` | integer | no | none | The crossing's value must be at least this. |
| `max_value` | integer | no | none | The crossing's value must be at most this. |
| `count` | integer ≥ 1 | no | `1` | How many matching crossings are needed. |
| `audience` | `world` \| `everyone` | no | `world` | Who the crossing is credited to. |

Where [`gate_open`](#neroquestsgate_open) waits on **your** progress, this waits on the **world's**.
The publishing mod fires the crossing on Neroland Core's shared event bus and NeroQuests listens, so
neither mod imports the other and a quest can be written against a mod that is not even installed.

Counted, and the tally never falls. A crossing is an event, not a state you can re-measure, so one
that happened while you were logged out is simply missed — the same way a kill nobody made credits
nobody.

**`rising` does not mean "good".** Core defines it as "the value crossed upward", which is a recovery
on `nerocolonies:oxygen` and a *worsening* on `nerotech:pollution`.

**`audience` is the field to get right.** A crossing names a place or a system and never a person —
that is a privacy rule the publishers are held to — so nothing in the event says who should be
credited:

| `audience` | Who is credited | Notes |
| --- | --- | --- |
| `world` (default) | The quest's shared counter, **once** per crossing | Needs `"scope": "server"`. |
| `everyone` | Every online player working on the quest | The explicit opt-in for letting world news tick a personal quest. |

The conservative default is deliberate: without it, one colony's good news would silently complete a
personal quest for everybody logged in, including people nowhere near it. A `custom_event` left on
`world` inside a `"scope": "player"` quest could never advance, so the loader drops that quest and
says why in `/neroquests reload-check`.

[Quest format](Quest-Format.md) carries the `<modid>:<channel>` convention and **the table of channels
that exist today** across Nerotech, NeroColonies and NeroCreatures.

### `neroquests:economy`

Trade on the server's economy: sell or buy goods, or earn credits from other activities.

```json
{ "type": "neroquests:economy", "event": "sold", "tag": "c:ingots/iron", "count": 64 }
{ "type": "neroquests:economy", "event": "bought", "count": 32 }
{ "type": "neroquests:economy", "event": "earned", "count": 500 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `event` | `sold` \| `bought` \| `earned` | yes | — | What counts. |
| `item` | item id | no | any | Only this item counts (`sold` / `bought`). Mutually exclusive with `tag`. |
| `tag` | item tag id | no | any | Only items in this tag count (no `#` prefix). |
| `count` | integer ≥ 1 | no | `1` | Units sold or bought, or credits earned. |

Fed by an economy mod's trade events (NeroEconomy 0.3.0 or later). `sold` and `bought` count
units of every settled trade on your side of it, whichever venue settled it. `earned` counts credits
other mods paid you through the economy's budgeted payouts — **never** NeroQuests' own quest or
contract rewards, or a reward could complete an "earn" quest by itself.

Counted, and the tally never falls. Only an online player is credited: a trade settled while you are
logged out (a resting order filling) is not stored for later.

**Without an economy mod the objective loads fine and never advances.** It is deliberately not
treated as missing content, so `missingModObjectivePolicy` can never skip or auto-complete it. Hide
such quests behind an economy milestone gate with `visible_gate` (the economy mod's player gates,
such as `neroeconomy:first_sale`) and they stay invisible on servers without one — the built-in
market quests do exactly that.

### `neroquests:visit_structure`

Stand inside a generated structure — a village, a ruin, a mod's outpost.

```json
{ "type": "neroquests:visit_structure", "structure": "minecraft:village_plains" }
{ "type": "neroquests:visit_structure", "tag": "minecraft:village" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `structure` | structure id | one of | A single structure. Mutually exclusive with `tag`. |
| `tag` | structure tag id | one of | Any structure in the tag (no `#` prefix). Mutually exclusive with `structure`. |

Measured on the once-a-second sweep, for players who have the quest in progress: your position is
checked against the structure pieces of the chunk you stand in — data that chunk already holds, so it
costs no searching. Standing anywhere inside a piece counts. A milestone: once visited it stays done.
No position is stored or sent anywhere. An unknown structure from a mod that is not installed is
missing content; an unknown one from a loaded mod is a typo.

### `neroquests:manual`

The escape hatch for things the game cannot detect — "build a statue in the town square".

```json
{ "type": "neroquests:manual", "label": "quest.mypack.town.statue.manual" }
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `label` | translation key | yes | The objective's text in the book. |

It never advances by itself. A quest author ticks it for an **online** player with
`/neroquests manual complete <quest> <player>` (see [Commands](Commands.md)); the quest must be
available to that player. If the quest has other objectives it completes once they are done too. Who
may run the command — a player who may author quests ([Editor permissions](Editor-Permissions.md)), at
most 10 times a minute, or the console — is decided on the server every time. In a custom quest the
`label` is the author's own plain text instead of a translation key. Each use is counted in the log; the log never names who.

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

Either way the quest stays completable as long as **at least one** of its objectives is present, and
the offending objective is logged once per server run naming only resource ids — never player data.
A completion that relied on `autocomplete` pays **no rewards** unless the server-authoritative
`degradedCompletionsPay` key is set to `true` (default `false`).

An objective counts as "missing content" when its item or entity id is unregistered, its tag
resolves to nothing, its dimension is not loaded, its `custom_event` channel cannot fire here (see
below), its `gate_open` gate is defined by nothing installed (and is not open anyway), or the quest it
points at no longer exists. A quest whose objectives *all* degrade is **not completable** — it would
otherwise complete, and pay, the moment it became available.

**Typos are not missing content.** An id that does not resolve although the mod owning its namespace
is loaded (or the namespace is `minecraft`, `neroquests` or `nerolandcore`) is treated as a mistake:
the whole quest is dropped and `/neroquests reload-check` names the objective. Tags count as typos
only in those three namespaces, because another mod's tag may legitimately be filled by a third mod
that is not installed.

A `custom_event` channel is present when the **quest** belongs to a pack whose mod is loaded (or to a
plain datapack namespace); for NeroQuests' own quests, when the channel's mod is loaded, the channel
has been seen this session, or its namespace is not a mod id at all.

**Quest packs never auto-complete.** A pack only loads when its mod is installed, so the
`autocomplete` policy applies to NeroQuests' own quests only; a missing piece of a pack quest is always
skipped. A pack chapter shown with `show_when_absent` (its mod missing) is never completable at all.

A `custom_event` channel whose mod *is* installed but whose publisher is switched off (NeroColonies'
`thresholdEventsEnabled`, Nerotech's `pollutionEventThreshold`) is **not** missing content. The mod is
there, so the objective waits rather than degrading — which is the right answer, because an operator
can turn the publisher back on.

## See also

- [Quest format](Quest-Format.md) — the quest and chapter files these objectives live in
- [Data storage](Data-Storage.md) — where the counters are kept, and how they are erased
- [Home](Home.md)
