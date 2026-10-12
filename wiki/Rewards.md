# Rewards

What a quest pays out when it completes. A quest's `rewards` list holds zero or more entries of the
form `{ "type": "<id>", ... }`; the remaining fields depend on the type. Six types ship:

| Type | Pays out | Key fields |
| --- | --- | --- |
| [`neroquests:item`](#neroquestsitem) | A stack of items | `item`, `count` |
| [`neroquests:xp`](#neroquestsxp) | Raw experience points | `amount` |
| [`neroquests:gate`](#neroquestsgate) | Opens a Neroland Core progression gate | `gate` |
| [`neroquests:currency`](#neroquestscurrency) | Money, via Core's currency contract | `currency`, `amount` |
| [`neroquests:reputation`](#neroquestsreputation) | Standing with a faction, via Core's reputation contract | `faction`, `amount` |
| [`neroquests:mod_hook`](#neroquestsmod_hook) | Tells the pack's own mod that this player finished the quest | `gate` |

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
| `count` | integer 1–2304 | no | `1` | How many (at most a full inventory of 64-stacks). Split into whole stacks if it exceeds the item's stack size. A larger value drops the quest. |

Items go into the player's inventory; **anything that does not fit is kept for the player** and
handed over once there is room (on join, on respawn, every five minutes, or through the companion
app's `claim_reward`). Set the server config `owedItemsDropWhenFull=true` to drop the overflow at
their feet instead. A recipient who is offline, dead or disconnecting has the whole stack kept the
same way — see [Kept rewards](#kept-rewards).

If `item` names something this installation does not have (a modpack quest shipped to a smaller
instance), the reward grants nothing and logs once by resource id. The quest still completes and its
other rewards still pay out — same philosophy as the objective side's missing-content handling.

### `neroquests:xp`

```json
{ "type": "neroquests:xp", "amount": 50 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `amount` | integer 1–1,000,000 | yes | — | Experience **points** (not levels). Anything outside the range is rejected and drops the quest. |

Experience for a recipient who is offline or dead is kept and granted on their next join or respawn.

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

**The gate's declared scope decides how it is written.** A gate declared `"scope": "server"` on a
server-scope quest is set once for the whole world. Every other gate — `player` or `team` scope — is
opened for each recipient in its own scope, also on a server-scope quest (see
[Server-scope semantics](#server-scope-semantics)). For a recipient who is offline the gate is opened
through Core's offline API, after the same requirement check. A `gate` id that no gate definition
declares grants nothing and is logged once.

**Only quests in the `neroquests` namespace may use `gate`** (and `reputation`). These rewards write
ecosystem-wide state, so a pack's own quests (`data/<yourpack>/neroquests/quests/...`) that carry one
are dropped at load, with the reason in `/neroquests reload-check`.

### `neroquests:currency`

```json
{ "type": "neroquests:currency", "amount": 250 }
{ "type": "neroquests:currency", "currency": "mypack:scrip", "amount": 5 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `currency` | currency id | no | `nerolandcore:credits` | Which currency to pay in. |
| `amount` | integer 1–1,000,000 | yes | — | How much to deposit. A reward pays out; it never charges. Outside the range the quest is dropped. |
| `once_per_world` | boolean | no | `false` | Pay only the first player in the world who completes the quest. |

Balances are keyed by player UUID, so this reward lands whether or not the recipient is online.

**Budgeted payouts.** With an economy mod that offers budgeted payouts (NeroEconomy 0.3.0 or
later), credits are paid through that budget under the reason `quest_reward`. The economy may
refuse a payout before anything moves — its daily budget for quests is spent, the shared budget or
your own daily cap is reached, its reserve is short. You are told why in chat, and the reward is
kept as **owed**: it is retried when you next join and every five minutes while you are online, and
paid as soon as the budget allows. Each retry reuses the original request, so a reward can never be
paid twice. Other currencies, or no such economy, use Core's currency contract; a deposit it reports
as failed is kept as owed in the same way.

One payout is capped by the economy (1,000 credits by default); a larger reward is refused as
invalid, logged once by quest id, and kept as owed. Split or lower it in the data.

**`once_per_world`** is the safer shape for large amounts on quests that one item set could complete
on every alternate account: the first completion in the world is paid, later ones complete without
the credits (and are told so). The claim is world data and names nobody.

### `neroquests:reputation`

```json
{ "type": "neroquests:reputation", "faction": "nerofactions:miners_union", "amount": 25 }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `faction` | faction id | yes | — | Whose opinion of you changes. |
| `amount` | integer | yes | — | The **change**, not the new value. **May be negative** — siding with one faction can legitimately cost you standing with its rival. |

Reputation is keyed by player UUID, so this reward also lands for an offline recipient. An `amount`
of `0` does nothing. Like `gate`, it is only allowed in quests of the `neroquests` namespace.

### `neroquests:mod_hook`

```json
{ "type": "neroquests:mod_hook", "gate": "mymod:hook/star_chart" }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `gate` | gate id | yes | — | A record gate the pack's mod ships: same namespace as the quest, path starting with `hook/`. |

How a mod gives its own reward without either mod depending on the other. The mod ships a
player-scope record gate with no `requires` (`data/<modid>/neroland_gates/hook/<id>.json`); on
completion NeroQuests opens it for the recipient through Neroland Core — also for an offline
recipient. The mod listens on Core's gate events and applies its effect, and may close the gate to
re-arm it. See [Quest format](Quest-Format.md#neroquestsmod_hook--your-mods-own-reward).

- A gate no definition declares (the mod is not installed), or one that is not a player-scope gate
  without `requires`, grants nothing and is logged once.
- Hook gates gate nothing, so they are written even with `gateWritesEnabled=false`. A mod must never
  use one to allow or deny access.
- Validation drops a quest whose hook gate is in another namespace or lacks the `hook/` prefix.
  Quests made in game cannot use it.

### `neroquests:loot_table`

```json
{ "type": "neroquests:loot_table", "table": "minecraft:chests/simple_dungeon" }
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `table` | loot table id | yes | — | A loot table loaded on the server |

Rolls the table once per completion for the completing player (chest rules, at the player's position)
and hands over what it produced. Whatever does not fit — or everything, for a player who is offline or
dead — is kept and delivered later, like `item`. A table that is not loaded grants nothing and is
logged once. Available to every source. Custom (editor-made) quests are held tighter: the table must
start with one of the server's `customLootTablePrefixes` (default `minecraft:chests/` and
`minecraft:gameplay/fishing`), each loot-table reward takes one of the quest's `customRewardMaxStacks`
slots, and while the server escrows custom rewards a custom quest's loot table pays nothing — also on a
quest saved before escrow was switched on. See [Editor permissions](Editor-Permissions.md).

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
| `gate` naming an id no gate definition declares | Nothing is written; logged once. |
| `item` naming an unregistered item id | Nothing is granted for that entry; logged once by resource id. |
| The recipient is offline, dead or disconnecting | `item` and `xp` are kept (see [Kept rewards](#kept-rewards)); a player-scope `gate` is opened through Core's UUID API when its requirements hold; `currency` and `reputation` land — they are UUID-keyed. Only the chat line is lost. |
| A reward throws unexpectedly | Logged with the quest id and reward type; the remaining rewards still run and the completion stands. |

`gateWritesEnabled` is a **server-authoritative** config key in `neroquests.properties`, hot-reloadable
via Neroland Core's `/neroland config reload`. Turning it off also stops the built-in *Age of
Industry* quest opening **Industrial Power**, which the whole Space Race chapter is hidden behind —
see [Story chapters](Story-Chapters.md#turning-the-built-in-line-off).

## Kept rewards

A reward a player cannot take right now is **kept** in their owed list instead of being lost:

| What | When it is kept | When it is handed over |
| --- | --- | --- |
| Items | Recipient offline, dead or disconnecting; inventory overflow (unless `owedItemsDropWhenFull=true`) | On join, on respawn, every five minutes while online — oldest first, partly if only some fit |
| Experience | Recipient offline, dead or disconnecting | On join or respawn |
| Credits | The economy refused the payout | Retried on join and every five minutes with the original request |

The player is told in chat when something is kept and when it is handed over. Each entry is handed
over at most once. A player holds at most **64** kept entries; beyond that the oldest is dropped and
the player is told. Kept entries are part of `/neroquests export` and are erased with the player's
data (see [`../PRIVACY.md`](../PRIVACY.md)).

## Server-scope semantics

A `scope: server` quest completes **once for the whole world**, not once per player. Its rewards go
to a defined set of **recipients**:

- **Recipients** are the triggering player (whoever's action tipped the shared quest over) plus
  **every online player who has the quest available** at that moment — its `visible_gate` is open for
  them and its prerequisites are met. Each recipient gets the completion chat line.
- **Player-targeted rewards** — `item`, `xp`, `currency`, `reputation`, and a `gate` whose declared
  scope is player or team — are granted to **each recipient**, each at most once per quest (every
  recipient is entered in their own paid ledger). Currency is paid to each recipient separately, so an
  economy budget may refuse some of them; those are kept as owed. Anything a recipient cannot take now
  is kept for them (see [Kept rewards](#kept-rewards)).
- **World-level rewards** — a `gate` declared `server` scope, on a server-scope quest — are granted
  **once**: the server gate is set for the whole world (when its `requires` hold), so everybody
  advances together.
- `once_per_world` currency on a server-scope quest is paid to the first recipient only.

Players who are offline when the quest completes are not recipients. Revoking a server-scope quest
with `--repay` lets the world and every former recipient be paid again.

**Alternate accounts (by design).** Recipients are everyone online with the quest available, whether or
not they helped, so a player who logs in idle alternate accounts collects the player-targeted rewards on
each of them. That is the price of "a shared milestone pays the whole server". Pack authors who do not
want it should keep server-scope rewards to world-level gates (as suggested below), or small, and put
valuable rewards on player-scope follow-on quests. Custom (editor-made) quests are always player scope.

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
