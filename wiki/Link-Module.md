# Link module (companion app)

NeroQuests can show your quest progress in a **Neroland companion app**. It does that through
**Neroland Core's link API**: NeroQuests registers what it can show and what it can do, and a
separate bridge mod serves that to your paired app over your own network.

NeroQuests itself ships **no server, no HTTP, no accounts and no outbound connection**. It only
fills in a registry entry inside Core. With no bridge mod installed, the link module does nothing
at all.

## What it exposes

| Kind | Name | What it is |
| --- | --- | --- |
| Section | `quests` | Every quest **you** can see, with your own progress |
| Section | `chapters` | The quest-book chapters and the quest ids in each |
| Action | `claim_reward` | A reward claim (currently a safe no-op — see below) |
| Event | `quest_completed` | Fires when a quest finishes |
| Event | `progress` | Fires when objective counters move |

Module id `neroquests`, **schema version 1**. The schema version is bumped whenever the shape of a
section changes, so an app can tell what it is parsing.

## Section: `quests`

```json
{
  "schema_version": 1,
  "player_online": true,
  "counts": { "visible": 4, "completed": 1, "in_progress": 1, "available": 1, "locked": 1 },
  "quests": [
    {
      "id": "mypack:chapter1/first_steps",
      "title": "quest.mypack.first_steps",
      "description": "quest.mypack.first_steps.desc",
      "icon": "minecraft:book",
      "scope": "player",
      "chapter": "mypack:chapter1",
      "state": "in_progress",
      "objectives": [
        { "index": 0, "type": "neroquests:collect_item", "current": 3, "target": 10,
          "content": "minecraft:iron_ingot" }
      ],
      "prerequisites": [],
      "rewards": [ { "type": "neroquests:item", "item": "minecraft:iron_ingot", "count": 8 } ],
      "completed_at": 0
    }
  ]
}
```

- `title` / `description` are **translation keys**, exactly as the datapack wrote them — the app
  decides how to render them.
- `state` is one of `completed`, `in_progress`, `available` (unlocked, not started) or `locked`
  (a prerequisite is still outstanding).
- `completed_at` (epoch millis) is present only for a completed quest.
- Rewards are a **summary**: the reward type id plus its plain fields (`item` + `count`, `amount`,
  `gate`, `currency`, `faction`). No item stack is serialised, and a reward type this build does not
  know is reported by id alone.
- `player_online` tells the app whether your player-scope gates could be fully resolved (see
  [Offline](#offline-players)).

Quests are listed in the pack's dependency order — prerequisites always before the quests that
need them.

## Section: `chapters`

```json
{
  "schema_version": 1,
  "chapters": [
    { "id": "mypack:chapter1", "title": "chapter.mypack.chapter1",
      "icon": "minecraft:written_book", "quests": ["mypack:chapter1/first_steps"] }
  ]
}
```

Each chapter lists only the quests **you** may see. A chapter whose every quest is hidden from you
is left out entirely.

Any section name other than these two returns an empty object.

## Action: `claim_reward`

```json
{ "quest": "mypack:chapter1/first_steps" }
```

In this version quest rewards are **granted automatically the moment a quest completes** — there is
nothing left to claim. A successful call therefore grants nothing and answers:

```json
{ "quest": "mypack:chapter1/first_steps", "scope": "player", "claimed": true,
  "granted_now": false, "completed_at": 1750000000000,
  "note": "rewards are granted automatically on completion" }
```

The action exists anyway so an app has a **stable id to code against from day one**: a claim button
written today keeps working unchanged when the deferred-reward queue lands (rewards that could not
be handed to an offline player being parked for next login), at which point this action starts
actually handing them over. It can never double-pay, and pressing it twice is harmless.

Refusals use Core's shared action error codes:

| Code | When |
| --- | --- |
| `VALIDATION` | `quest` missing/malformed, unknown quest, or the quest is not complete |
| `PLAYER_OFFLINE_REQUIRED` | You are not online (see below) |
| `INTERNAL` | No world is running, or something went wrong server-side |

A quest hidden from you answers **exactly like a quest that does not exist**, so an app can never
probe for hidden content.

`claim_reward` is **online-only** for now. Core allows a reward claim to run offline, and this one
will once rewards are queued — but today an item or experience reward needs a live player to land
in, so an offline claim would report success while doing nothing. Refusing it is the honest answer.

## Events

| Topic | Scope | Payload |
| --- | --- | --- |
| `quest_completed` | Your session (or a world broadcast for a `scope: server` quest) | `quest`, `title`, `scope`, `timestamp` |
| `progress` | Your session (or a world broadcast for shared progress) | `quests[] → { quest, title, objectives[] → { index, current, target } }` |

`quest_completed` is published from the same channel rewards hang off, so it fires exactly once per
completion — whether the quest was played out, cascaded from another quest, or granted by an
operator.

`progress` is published at the end of an engine evaluation pass that **actually changed something**,
in the same place the server sends your client its progress update. That means at most **one event
per player per pass**, and none at all while nothing is moving — the once-a-second re-measure is
silent on a settled world.

## Privacy

*(See also [`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md).)*

- **Your own data only.** A snapshot contains your quest ids, your objective counters and your
  completion timestamps, plus the shared `scope: server` rows — which belong to the world and carry
  no identifiers. Never another player's rows, never names, never coordinates.
- **Hidden stays hidden.** Quests behind an unopened `visible_gate` are filtered out server-side,
  so the link shows exactly what you would see in the quest book — no more.
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
- Quest data reaches an app read-only apart from the single `claim_reward` action, which grants
  nothing today. There is no way to complete, reset or grant a quest through the link — those stay
  operator commands (see [Commands](Commands.md)).

## See also

- [Server → client sync](Sync.md) — the in-game equivalent, over the game's own connection
- [Data storage](Data-Storage.md) — how progress is saved, kept, erased and exported
- [Rewards](Rewards.md) — what the reward summary refers to, and when payouts run
