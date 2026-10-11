# Daily contracts

Daily contracts are small, repeatable hand-in jobs: bring a stack of something, get credits. They
are paid from the server economy's **budget**, so they need an economy mod with budgeted payouts
(NeroEconomy 0.3.0 or later). Without one, no contract is ever offered.

## For players

| Command | What it does |
| --- | --- |
| `/neroquests contracts` | Lists today's contracts: what to hand in, the reward, and its status |
| `/neroquests contracts submit <contract>` | Hands in one of today's contracts |

- The same few contracts are drawn for everyone each **UTC day**, and you can hand in each one
  **once per day**.
- Submitting **takes the items** from your inventory (hotbar, main inventory, off-hand and worn
  equipment), then pays the reward.
- A contract is only offered as **open** while the economy's budget can still pay it today. When
  the budget is spent it shows as "budget spent for today"; try again tomorrow.
- If the payout is refused after the items were taken (the budget ran out a moment earlier), the
  reward is kept as **owed** and paid automatically later, exactly like an owed quest reward (see
  [Rewards](Rewards.md#neroquestscurrency)).

## For server owners

| Setting (`neroquests.properties`) | Default | Meaning |
| --- | --- | --- |
| `dailyContractsEnabled` | `true` | Turn daily contracts on or off. |
| `dailyContractsPerDay` | `3` | How many contracts are drawn each day (1 to 10). |

Rewards are paid under the reason `daily_contract` and count against the economy's daily budget
for NeroQuests, its shared budget for all mods, and each player's daily cap from all mods. The
economy's operator settings decide how large those are.

## For pack makers: contract files

Contracts are datapack JSON under `data/<namespace>/neroquests/contracts/<path>.json`; the id is
`<namespace>:<path>`. A few vanilla-item contracts ship with NeroQuests.

```json
{
  "title": "contract.mypack.granary",
  "item": "minecraft:wheat",
  "count": 64,
  "reward": 40,
  "weight": 3
}
```

| Field | Type | Required | Default | Meaning |
| --- | --- | --- | --- | --- |
| `title` | translation key or text | no | `contract.<namespace>.<path>` | The contract's name. |
| `item` | item id | one of | — | The item to hand in. Mutually exclusive with `tag`. |
| `tag` | item tag id | one of | — | Any item in the tag (no `#` prefix). |
| `count` | integer 1 to 4096 | no | `1` | How many to hand in. |
| `reward` | integer 1 to 1000 | yes | — | Credits paid on hand-in. |
| `weight` | integer 1 to 1000 | no | `1` | Relative chance of being drawn on a given day. |

A malformed file is skipped with a warning naming the file. A datapack `/reload` is picked up the
next time contracts are listed.

## See also

- [Rewards](Rewards.md) — budgeted payouts and owed rewards
- [Commands](Commands.md)
- [Data storage](Data-Storage.md) — what is stored about contracts
