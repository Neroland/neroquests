# Rewards

What a quest pays out when it completes. A quest's `rewards` list holds zero or more entries of the
form `{ "type": "<id>", ... }`; the remaining fields depend on the type. Five types ship:

| Type | Pays out | Key fields |
| --- | --- | --- |
| [`neroquests:item`](#neroquestsitem) | A stack of items | `item`, `count` |
| [`neroquests:xp`](#neroquestsxp) | Raw experience points | `amount` |
| [`neroquests:gate`](#neroquestsgate) | Opens a Neroland Core progression gate | `gate` |
| [`neroquests:currency`](#neroquestscurrency) | Money, via Core's currency contract | `currency`, `amount` |
| [`neroquests:reputation`](#neroquestsreputation) | Standing with a faction, via Core's reputation contract | `faction`, `amount` |

Rewards are granted **once**, the moment the quest completes, on the server, in the order they are
listed in the file. Nothing is granted twice: a completed quest never completes again.

## How a payout runs

1. The engine records the completion in the progress store.
2. The completing player gets a chat line — `Quest complete: <title>`.
3. Each reward is granted in turn, **each isolated in its own error boundary**. One reward that fails
   is logged and skipped; every other reward in the list still pays out, and the completion stands.

Every reward log line names the quest id, the reward type and (where relevant) the resource id it
could not use. Log lines never contain player names or UUIDs
(see [`../PRIVACY.md`](../PRIVACY.md)).

## Reward types

### `neroquests:item`

```json
{ "type": "neroquests:item", "item": "minecraft:iron_ingot", "count": 8 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `item` | item id | yes | — | The item to hand over. |
| `count` | integer 1–6400 | no | `1` | How many. Split into whole stacks if it exceeds the item's stack size. |

Items go into the player's inventory; **anything that does not fit is dropped at their feet**, so a
full inventory never silently swallows a reward.

If `item` names something this installation does not have (a modpack quest shipped to a smaller
instance), the reward grants nothing and logs once by resource id. The quest still completes and its
other rewards still pay out — same philosophy as the objective side's missing-content handling.

### `neroquests:xp`

```json
{ "type": "neroquests:xp", "amount": 50 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `amount` | integer ≥ 1 | yes | — | Experience **points** (not levels). `0` or negative is rejected and drops the quest. |

### `neroquests:gate`

Opens a **Neroland Core** progression gate — the way a quest line drives the wider ecosystem: finish
the quest, unlock the tier.

```json
{ "type": "neroquests:gate", "gate": "nerolandcore:industrial_power" }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `gate` | gate id | yes | — | The Core progression gate to open. |

**The gate graph stays authoritative.** NeroQuests asks Core to open the gate *only if its own
`requires` chain is already satisfied*. When it is not, the gate is left closed and the attempt is
logged at debug level. A quest can **grant** progression; it can never bypass the progression graph
Core or a datapack defines. A gate that was already open is a no-op, not a failure.

Which path is used depends on the quest's `scope` — see [Server-scope semantics](#server-scope-semantics).

### `neroquests:currency`

```json
{ "type": "neroquests:currency", "amount": 250 }
{ "type": "neroquests:currency", "currency": "mypack:scrip", "amount": 5 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `currency` | currency id | no | `nerolandcore:credits` | Which currency to pay in. |
| `amount` | integer ≥ 1 | yes | — | How much to deposit. A reward pays out; it never charges. |

Balances are keyed by player UUID, so this reward lands whether or not the recipient is online.

### `neroquests:reputation`

```json
{ "type": "neroquests:reputation", "faction": "nerofactions:miners_union", "amount": 25 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `faction` | faction id | yes | — | Whose opinion of you changes. |
| `amount` | integer | yes | — | The **change**, not the new value. **May be negative** — siding with one faction can legitimately cost you standing with its rival. |

Reputation is keyed by player UUID, so this reward also lands for an offline recipient. An `amount`
of `0` does nothing.

## Degradation — a reward never breaks a quest

NeroQuests is built to survive being installed next to fewer mods than the quest pack expects. Every
reward that cannot pay out **degrades to a no-op and logs once**; the quest still completes, and
every other reward in the list still runs.

| Situation | What happens |
| --- | --- |
| `currency` with no economy mod installed | Nothing is granted; logged once at debug. Core's contract has no persistent backing until a mod (NeroEconomy) implements it, and paying into a volatile in-memory balance would be a lie. |
| `reputation` with no faction mod installed | Nothing is granted; logged once at debug. Same reason (NeroFactions implements it). |
| `gate` while `gateWritesEnabled=false` | The whole reward type is a no-op server-wide; logged once at info. Run NeroQuests as pure content without it touching ecosystem progression. |
| `gate` whose Core prerequisites are not met | The gate stays closed; logged at debug. Never forced open. |
| `item` naming an unregistered item id | Nothing is granted for that entry; logged once by resource id. |
| The recipient is offline | Player-bound rewards (`item`, `xp`, the chat line, a player-scope `gate`) are skipped with a debug line. `currency` and `reputation` still land — they are UUID-keyed. |
| A reward throws unexpectedly | Logged with the quest id and reward type; the remaining rewards still run and the completion stands. |

`gateWritesEnabled` is a **server-authoritative** config key in `neroquests.properties`, hot-reloadable
via Neroland Core's `/neroland config reload`.

> **No deferred-reward queue yet.** A player-bound reward skipped because the recipient was offline is
> currently dropped, not parked for next login. Holding unclaimed rewards is planned alongside the
> NeroLink companion's `claim_reward` action.

## Server-scope semantics

A `scope: server` quest completes **once for the whole world**, not once per player. Its rewards still
have to go somewhere, so:

- **Player-targeted rewards** — `item`, `xp`, `currency`, `reputation` — go to the **triggering
  player**: whoever's action tipped the shared quest over. If they have logged off between the trigger
  and the payout, the UUID-keyed ones (`currency`, `reputation`) still land and the rest are skipped
  with a debug line.
- **`neroquests:gate`** is the exception. On a server-scope quest it sets the **server gate** for the
  whole world instead of opening a per-player one, so everybody advances together.

The completion chat line likewise goes to the triggering player only.

If you want a shared milestone that rewards everyone, model it as a server-scope quest whose only
reward is a `gate`, and gate the follow-on player-scope quests behind it.

## Worked example

A quest that pays loot, experience and progression at once:

```json
{
  "title": "quest.mypack.first_reactor.title",
  "description": "quest.mypack.first_reactor.desc",
  "icon": "nerolandcore:starsteel_ingot",
  "objectives": [
    { "type": "neroquests:collect_item", "item": "nerolandcore:starsteel_ingot", "count": 16 }
  ],
  "rewards": [
    { "type": "neroquests:item", "item": "minecraft:diamond", "count": 4 },
    { "type": "neroquests:xp", "amount": 250 },
    { "type": "neroquests:currency", "amount": 500 },
    { "type": "neroquests:gate", "gate": "nerolandcore:industrial_power" }
  ]
}
```

On a server with no economy mod, that quest pays the diamonds, the experience and the gate, and
quietly skips the credits.

## See also

- [Quest format](Quest-Format.md)
- [Objectives](Objectives.md)
- [Data storage](Data-Storage.md)
- [Home](Home.md)
- [Privacy & data protection](../PRIVACY.md)
