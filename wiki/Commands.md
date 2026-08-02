# Commands

NeroQuests ships one command tree, `/neroquests`, for server operators. Everything under it is
**server-authoritative** and requires **permission level 2** (game-master / op) — the same level
Neroland Core's `/neroland` admin branches use. A player without it never sees the tree.

There is no player-facing command: the [quest book](Quest-Book.md) is how players read their own
quests.

## At a glance

| Command | What it does |
| --- | --- |
| `/neroquests grant <player> <quest>` | Completes a quest for a player, firing its rewards |
| `/neroquests revoke <player> <quest>` | Clears a player's completion and counters for one quest |
| `/neroquests reset <player>` | Wipes all of a player's stored quest progress |
| `/neroquests reset <player> <quest>` | Same as `revoke` — one behaviour, two spellings |
| `/neroquests list` | Lists every loaded quest with its chapter and scope |
| `/neroquests list <player>` | Per-quest status for one player |
| `/neroquests reload-check` | Re-reads the datapacks and reports what was rejected |
| `/neroquests export <player>` | Prints one player's own progress as JSON (data-access requests) |

## Arguments

| Argument | Accepts | Suggestions |
| --- | --- | --- |
| `<player>` | An online player's name, **or** a raw UUID | Names of everyone online |
| `<quest>` | A loaded quest id; a bare path is read as `neroquests:<path>` | Every loaded quest id |

A UUID is accepted so the privacy commands (`reset`, `export`) still reach a player who has left the
server. `grant` is the one exception that needs its target **online**, because rewards are handed to
a live player.

`<quest>` is always the last argument of its subcommand, so a quest id containing `:` and `/` is read
as written — no quoting needed.

## `grant`

```text
/neroquests grant Steve neroquests:intro/first_steps
```

Completes the quest **through the normal completion path**, not by writing a flag:

1. the progress store is marked complete (in the shared section for a `scope: server` quest);
2. the completion listeners fire, so [rewards](Rewards.md) pay out exactly as if the player had
   finished it themselves — XP, items, Core gates, currency, reputation;
3. the completion cascade re-runs, so any quest waiting on this one (a `quest_complete` objective, or
   a prerequisite) settles too;
4. the client is re-synced.

Objective counters are deliberately left where they are — completion is what the engine reads, and a
completed quest is never re-settled.

If the quest is already complete for that player, nothing happens and you get a message saying so.

## `revoke` / `reset <player> <quest>`

```text
/neroquests revoke Steve neroquests:intro/first_steps
/neroquests reset  Steve neroquests:intro/first_steps
```

Both spellings run the same code: the player's whole row for that quest — completion stamp **and**
every objective counter — is dropped, and they are re-synced if online.

Two things worth knowing:

- **Rewards are not clawed back.** They live in inventories, XP levels, Core gates, currency balances
  and faction reputation, none of which NeroQuests owns. The success message says so.
- **A revoked quest can complete itself again.** If its objectives still measure as satisfied (the
  player is still holding the items a `collect_item` objective counts), the next engine sweep will
  simply re-complete it — and fire its rewards a second time. Revoke is for fixing a mis-`grant`, not
  for taking a finished quest away.

For a `scope: server` quest the shared row is cleared and everyone is re-synced; the named player
only chooses which section of the store you mean.

## `reset <player>`

```text
/neroquests reset Steve
/neroquests reset 069a79f4-44e9-4726-a5be-fca90e38aaf5
```

Drops **everything** NeroQuests stores for that player: every quest row and their retention stamp.
Shared `scope: server` progress is untouched — it belongs to the world and carries no player
identifiers.

This is the same erasure `/neroland data erase <uuid>` performs across every Nero mod; use the Core
command when the request covers the whole ecosystem, and this one when it is only about quests.

## `list`

```text
/neroquests list
```

Prints a count, then one line per loaded quest with the chapter that owns it and its scope:

```text
3 quest(s) loaded:
  neroquests:intro/first_steps [neroquests:intro] player
  neroquests:intro/gear_up     [neroquests:intro] player
  neroquests:world/beacon      [no chapter] server
```

Quests appear in dependency order — a quest's prerequisites always precede it.

## `list <player>`

```text
/neroquests list Steve
```

Same set, with that player's status against each one:

| Status | Meaning |
| --- | --- |
| `complete` | Finished (or granted) |
| `in progress 2/10, 0/1` | Started; one `stored/target` pair per objective, in definition order |
| `available` | Unlocked and visible, but untouched |
| `locked` | Prerequisites unmet, or its `visible_gate` is closed |

Output goes to you alone — it is never broadcast to other operators.

For an **offline** player, Core's progression gates cannot be resolved, so a quest behind a
`visible_gate` reads as `locked` rather than being guessed at. Run it while they are online for the
exact verdict.

## `reload-check`

```text
/neroquests reload-check
```

Re-reads every quest and chapter from the currently-enabled datapacks, then reports:

```text
Reloaded quest definitions: 3 quest(s) in 1 chapter(s).
2 validation problem(s):
  [dropped] neroquests:world/broken - unregistered objective/reward type othermod:smelt_item
  [ignored] neroquests:intro - lists unknown quest neroquests:intro/missing
```

Finally it re-sends definitions and progress to every online player, so the quest book updates
without anyone reconnecting.

Two severities:

| Severity | Meaning |
| --- | --- |
| `dropped` | The whole definition is not loaded |
| `ignored` | The definition loaded, but part of it (an entry, a reference) was skipped |

The loader never crashes on bad content — it drops the offending entry and carries on — so this
command is how you find out that it did. Everything it prints is also in the server log; the report
just saves you reading it. See [Quest format](Quest-Format.md) for what a valid definition looks
like.

`reload-check` covers only NeroQuests. A plain vanilla `/reload` is also picked up automatically (the
mod notices the resource manager changed and re-reads within a second), so use this command when you
want the *report*, not just the reload.

## `export`

```text
/neroquests export 069a79f4-44e9-4726-a5be-fca90e38aaf5
```

Prints one player's own quest progress as pretty JSON, to you alone:

```json
{
  "last_updated": 1754131200000,
  "quests": {
    "neroquests:intro/first_steps": {
      "counters": [ 10 ],
      "completed_at": 1754131200000,
      "complete": true
    }
  }
}
```

This is the POPIA/GDPR data-access surface described in
[`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md). It contains **only** the named
player's rows: no other player's progress, and no shared `scope: server` progress (which holds no
player identifiers at all).

Chat is not a file transfer — an export longer than 32 000 characters is cut off with a note. That is
far beyond any realistic pack.

## Privacy notes

- Every message goes to the invoker only. Nothing is broadcast to other operators, which also keeps
  results out of `latest.log` under the `logAdminCommands` game rule.
- Nothing here logs player identity, in line with the rest of the mod
  ([Data storage](Data-Storage.md)).
- If a subcommand hits an unexpected error you get a short message instead of a stack trace in chat;
  the trace goes to the log, and the anonymous crash report ([Telemetry](Telemetry.md)) carries the
  subcommand name only — never its arguments.
