# Link module (companion app)

NeroQuests can show your quest progress in a **Neroland companion app**. It does that through
**Neroland Core's link API**: NeroQuests registers what it can show and what it can do, and a
separate bridge mod serves that to your paired app over your own network.

NeroQuests itself ships **no server, no HTTP, no accounts and no outbound connection**. It only
fills in a registry entry inside Core. With no bridge mod installed, the link module does nothing
at all.

## Schema 2 contract

Module id `neroquests`, **schema version 2** (NeroQuests 0.4.0). Every section body and event payload
carries `"schema_version": 2`. Schema-1 fields are still present for one release, with one exception:
a **custom** quest or chapter has no `title` / `description` key field (see [Text](#text)).

| Kind | Name | Advertised | What it is |
| --- | --- | --- | --- |
| Section | `quests` | yes | Every quest you can see, with your own progress |
| Section | `chapters` | yes | The quest-book chapters you can see and the quest ids in each |
| Section | `custom_authoring` | yes | Whether you may author custom quests (a boolean) |
| Section | `owed` | yes | Rewards kept for you — the claim list |
| Section | `progress` | yes | Every visible quest's counters, in the `progress` event's shape |
| Action | `claim_reward` | yes | Hands over kept rewards (credits, items, experience) |
| Event | `quest_completed` | — | A quest finished |
| Event | `progress` | — | Objective counters moved |
| Event | `reward_owed` | — | A reward was kept for you |
| Event | `reward_claimed` | — | A kept reward was handed over (wholly or in part) |
| Alert | `neroquests:reward_waiting` | — | "A reward is waiting for you" |
| Alert | `neroquests:new_chapter` | — | "A new chapter appeared" |

A topic snapshot (the bridge's consistent-start frame for topic `neroquests.<topic>`) also answers for
`quest_completed`, `reward_owed` and `reward_claimed` — see [Topic snapshots](#topic-snapshots). Any
other section name returns `{}`.

### Text

- **Spine and pack** quests and chapters: `title` / `description` hold the **translation key** as the
  datapack wrote it (schema 1), and `title_text` / `description_text` hold the **English** text resolved
  on the server from the owning mod's bundled `en_us.json` (falling back to the namespace the key names).
  When the key cannot be resolved the `_text` field is **omitted** — show the key.
- **Custom** quests and chapters (`source: "custom"`): the player-written text is sent **only** as
  `title_text` / `description_text`. There is no `title` / `description` field — never try to translate
  custom text.
- `description` / `description_text` are omitted when the quest has no description.

### Section: `quests`

```json
{
  "schema_version": 2,
  "player_online": true,
  "counts": { "visible": 2, "completed": 0, "in_progress": 1, "available": 1, "locked": 0 },
  "quests": [
    {
      "id": "nerospace:starfarers_log/first_light",
      "source": "pack",
      "namespace": "nerospace",
      "chapter": "nerospace:starfarers_log",
      "mod_present": true,
      "title": "quest.nerospace.starfarers_log.first_light.title",
      "title_text": "First Light",
      "description": "quest.nerospace.starfarers_log.first_light.desc",
      "description_text": "Your first launch ...",
      "icon": "minecraft:firework_rocket",
      "scope": "player",
      "objectives": [
        { "index": 0, "type": "neroquests:collect_item", "current": 3, "target": 16,
          "content": "#minecraft:logs", "text": "Collect 16 × #minecraft:logs" }
      ],
      "prerequisites": [],
      "rewards": [ { "type": "neroquests:xp", "amount": 50 } ],
      "state": "in_progress"
    },
    {
      "id": "custom:k3x9q2ab/m7wq1zte",
      "source": "custom",
      "namespace": "custom",
      "chapter": "custom:k3x9q2ab",
      "mod_present": true,
      "title_text": "Build the town hall",
      "description_text": "Ask an editor to tick this off when it stands.",
      "icon": "minecraft:book",
      "scope": "player",
      "objectives": [
        { "index": 0, "type": "neroquests:manual", "current": 0, "target": 1,
          "content": "neroquests:manual", "text": "Confirmed by an editor" }
      ],
      "prerequisites": [],
      "rewards": [ { "type": "neroquests:item", "item": "minecraft:emerald", "count": 4 } ],
      "state": "available"
    }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Quest id |
| `source` | `"spine"` \| `"pack"` \| `"custom"` | `spine` = namespace `neroquests`, `custom` = namespace `custom`, anything else `pack` |
| `namespace` | string | The id's namespace (for a pack: the mod id) |
| `chapter` | string? | The chapter that lists the quest; absent when none does |
| `mod_present` | bool | `false` only for a pack quest shown with the "mod not installed" badge (`show_when_absent`); such a quest is always `locked` |
| `title`, `description` | string? | Translation keys — spine/pack only |
| `title_text`, `description_text` | string? | English (spine/pack, when resolvable) or the custom text |
| `icon` | string | Item id |
| `scope` | `"player"` \| `"server"` | |
| `objectives[]` | object | `index`, `type`, `current` (clamped to 0..`target`), `target` (≥ 1), `content` (resource id / `#tag` / channel label — never player text), `text` (short English summary built from type + target + content) |
| `prerequisites[]` | string | Only prerequisites you can see |
| `rewards[]` | object | `type` plus plain fields: `item` + `count`, `amount` (xp), `gate`, `currency` + `amount`, `faction` + `amount`; other types by `type` alone |
| `state` | `"completed"` \| `"in_progress"` \| `"available"` \| `"locked"` | |
| `completed_at` | long? | Epoch millis; only when completed |

`player_online` is `false` when the requester is offline (player-scope gates then resolve from storage,
team gates read as closed — see [Offline players](#offline-players)). Quests come in dependency order.

### Section: `chapters`

```json
{
  "schema_version": 2,
  "chapters": [
    { "id": "nerospace:starfarers_log", "title": "chapter.nerospace.starfarers_log.title",
      "title_text": "Starfarer's Log", "icon": "minecraft:end_crystal", "source": "pack",
      "namespace": "nerospace", "mod_present": true, "order": 2,
      "quests": ["nerospace:starfarers_log/first_light"] },
    { "id": "custom:k3x9q2ab", "title_text": "Town projects", "icon": "minecraft:written_book",
      "source": "custom", "namespace": "custom", "mod_present": true, "order": 0,
      "quests": ["custom:k3x9q2ab/m7wq1zte"] }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `id`, `icon` | string | |
| `title` / `title_text` | string? | As in [Text](#text) |
| `source`, `namespace`, `mod_present` | | As for quests (`mod_present: false` = shown with the badge) |
| `order` | int | The chapter's index among **all** loaded chapters sorted by id — the quest book's tab order. Sort by it; gaps are normal (hidden chapters are omitted) |
| `quests[]` | string | Only quests you can see, in the chapter's layout order |

A chapter with no quest visible to you is omitted.

### Section: `custom_authoring`

```json
{ "schema_version": 2, "can_author": false }
```

`can_author` is `true` only when you are online and pass the editor check right now (creative, permission
level, or the server's allow-list, and the editor not locked). Nothing else — no lock state, no list, no
names. Authoring itself happens in game.

### Section: `owed`

```json
{
  "schema_version": 2,
  "count": 2,
  "owed": [
    { "key": "nq:41:neroquests:groundwork/breaking_ground", "type": "currency",
      "currency": "neroeconomy:credits", "amount": 100,
      "quest": "neroquests:groundwork/breaking_ground", "reason": "quest_reward",
      "created_at": 1760000000000, "permanent": false },
    { "key": "nq:i:42:neroquests:groundwork/breaking_ground", "type": "item",
      "item": "minecraft:diamond", "count": 2,
      "quest": "neroquests:groundwork/breaking_ground", "reason": "quest_reward",
      "created_at": 1760000000000, "permanent": false }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `key` | string | The entry's stable request key — pass it to `claim_reward` as `key`. Contains no player identifier |
| `type` | `"currency"` \| `"item"` \| `"xp"` | |
| `currency` + `amount` | string, long | Currency entries |
| `item` + `count` | string, long | Item entries |
| `amount` | long | XP entries (points) |
| `quest` | string? | The quest it came from; absent for a daily-contract payout |
| `reason` | `"quest_reward"` \| `"daily_contract"` | |
| `created_at` | long | Epoch millis of the first attempt |
| `permanent` | bool | The economy refused it as invalid; kept and claimable, never retried automatically |

Oldest first, at most 64 entries.

### Section: `progress`

```json
{
  "schema_version": 2,
  "scope": "snapshot",
  "quests": [
    { "quest": "neroquests:groundwork/first_tools", "scope": "player",
      "title": "quest.neroquests.groundwork.first_tools.title", "title_text": "First Tools",
      "objectives": [ { "index": 0, "current": 16, "target": 16 }, { "index": 1, "current": 1, "target": 1 } ],
      "completed_at": 1760000000000 }
  ]
}
```

Every quest you can see — your own rows for `scope: player`, the world's rows for `scope: server` — so a
client can **replace** its whole progress view from it. Rows carry `title` / `title_text` as in
[Text](#text); `completed_at` only when complete.

### Topic snapshots

| Topic | Snapshot body |
| --- | --- |
| `progress` | The `progress` section above |
| `reward_owed` | The `owed` section above |
| `quest_completed` | `{ "schema_version": 2, "completed": [ { "quest", "scope", "source", "namespace", "title"?, "title_text"?, "completed_at" } ] }` — your 50 most recent visible completions (own + world), newest first |
| `reward_claimed` | `{ "schema_version": 2, "claimed": [] }` — claims are not kept as history |

### Action: `claim_reward`

Params (all optional):

```json
{ "quest": "neroquests:groundwork/breaking_ground", "key": "nq:41:neroquests:groundwork/breaking_ground" }
```

- `quest` — settle only entries from that quest. It must be a quest you can see and have completed
  (else `VALIDATION`).
- `key` — settle only the owed entry with that key: the **idempotency key** of the request. If no entry
  with that key exists (already settled, or never yours) the call **succeeds** with nothing delivered and
  `"already_settled": true`.
- Neither — settle everything deliverable.

Offline claims are allowed but only settle **currency**; items and XP stay owed until you are in the
world, alive.

```json
{
  "delivered": [ { "type": "currency", "currency": "neroeconomy:credits", "amount": 100 } ],
  "remaining": 1,
  "owed": [ { "key": "nq:i:42:neroquests:groundwork/breaking_ground", "type": "item",
              "item": "minecraft:diamond", "count": 2, "quest": "neroquests:groundwork/breaking_ground",
              "reason": "quest_reward", "created_at": 1760000000000, "permanent": false,
              "source": "neroquests:groundwork/breaking_ground" } ],
  "key": "nq:41:neroquests:groundwork/breaking_ground",
  "already_settled": false,
  "claimed": true,
  "granted_now": true,
  "note": "some rewards are still kept; items and experience are handed over in-game",
  "quest": "neroquests:groundwork/breaking_ground",
  "scope": "player",
  "completed_at": 1759990000000
}
```

- `delivered[]`: `{type:"currency", currency, amount}` | `{type:"item", item, count}` | `{type:"xp", amount}`.
- `owed[]` / `remaining`: what is still kept (filtered by `quest` / `key` when given); rows are `owed`
  section rows plus the schema-1 `source`.
- `key` / `already_settled` only when `key` was given; `quest` / `scope` / `completed_at` only when `quest`
  was given. `claimed`, `granted_now`, `note` are schema-1 fields.
- Errors: `VALIDATION` (malformed `quest` or `key`, unknown/hidden/incomplete quest), `INTERNAL` (no world,
  server-side failure). A hidden quest answers exactly like a missing one.

### Events

| Topic | Routed to | Payload |
| --- | --- | --- |
| `quest_completed` | Your sessions; a `scope: server` quest is broadcast **without** a UUID (never when it has a `visible_gate`) | `{ schema_version, quest, title?, title_text?, scope, source, namespace, timestamp }` |
| `progress` | Your sessions (`scope: "player"`); world broadcast without a UUID (`scope: "server"`, gated quests left out) | `{ schema_version, scope, quests: [ { quest, title?, title_text?, scope?, objectives: [ { index, current, target? } ] } ] }` |
| `reward_owed` | Your sessions only | An `owed` row plus `schema_version` |
| `reward_claimed` | Your sessions only | `{ schema_version, key, type, currency?, amount \| item+count, quest?, reason, remaining }` — `amount`/`count` is the part just handed over, `remaining` what is left on that entry |

- `quest_completed` fires once per completion (played, cascaded or granted by an operator).
- `progress` fires at most once per player per engine pass that changed something. In a `progress`
  event, `title*`, `scope` and `target` are omitted for a quest that is no longer loaded.
- `reward_owed` fires whenever a reward is kept (economy refusal, offline/dead/full inventory).
- `reward_claimed` fires whenever a kept entry is settled — by `claim_reward`, on join/respawn, or by the
  periodic retry.

### Alerts

Raised through Neroland Core's alert store (the bridge's `core/alerts` section, module `neroquests`,
severity `INFO`), so the app can notify while closed:

| Alert id | Text (fixed) | When |
| --- | --- | --- |
| `neroquests:reward_waiting` | `A reward is waiting for you` | A reward was kept for you |
| `neroquests:new_chapter` | `A new chapter appeared` | A pack or custom chapter with at least one quest you can see entered your quest book while you were online (not on the first view after joining) |

The text is always exactly one of these two English constants — never a quest or chapter title, id,
amount or name. Each id is raised at most **once per hour** per player (re-raising replaces the stored
alert), so NeroQuests holds at most two alerts per player, well inside Core's per-module and per-player
caps.

## Privacy

*(See also [`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md).)*

- **Your own data only.** A snapshot contains your quest ids, your objective counters and your
  completion timestamps, plus the shared `scope: server` rows — which belong to the world and carry
  no identifiers. Never another player's rows, never names, never coordinates.
- **Hidden stays hidden.** Quests behind an unopened `visible_gate` are filtered out server-side,
  so the link shows exactly what you would see in the quest book — no more.
- **No player text in alerts, no identifiers anywhere.** Alerts carry one of two fixed English
  sentences. Owed keys are `nq:<sequence>:<source>` — no UUID. Custom quest text (written by players)
  reaches only the sessions of players who can see that quest, never an alert or a broadcast.
- **Broadcasts carry no identity.** A `scope: server` event goes out without a UUID, so nothing says
  who tipped it over. A gated server-scope quest is not broadcast at all; you see it once your own
  snapshot includes it.
- **Erasure needs no extra step.** Every read goes to the live progress store, so a player erased
  through Core's shared erasure hook immediately reads as having no progress.
- **Consent lives with the bridge.** Whether an app may connect at all, and which of your data it
  receives, is governed by the bridge mod's pairing — NeroQuests only answers questions the bridge
  is already authorised to ask.

### Offline players

An app may ask while you are logged off. Your own stored progress reads back fine, but your
player-scope progression gates can only be fully resolved for a live player, so while offline the
gate set falls back to your stored player gates plus the world's — team-scope gates read as closed.
A gate read as closed only ever *hides* a quest, never reveals one, so the offline view is a subset
of the online one. `player_online` in the `quests` section says which view you are looking at.

## Notes for server admins

- The link module is server-side only and starts with the world. Nothing is exposed until a bridge
  mod is installed and an app has been paired with it.
- Quest data reaches an app read-only apart from the single `claim_reward` action, which only hands
  over rewards already kept for that player. There is no way to complete, reset or grant a quest
  through the link — those stay operator commands (see [Commands](Commands.md)). Custom quests are
  authored in game only; `custom_authoring` just tells the app whether you could.

## See also

- [Server → client sync](Sync.md) — the in-game equivalent, over the game's own connection
- [Data storage](Data-Storage.md) — how progress is saved, kept, erased and exported
- [Rewards](Rewards.md) — what the reward summary refers to, and when payouts run
