# Commands

NeroQuests ships one command tree, `/neroquests`, mostly for server operators. Everything under it
is **server-authoritative**. `list` and `reload-check` require **permission level 2** (game-master /
op) — the same level Neroland Core's `/neroland` admin branches use. The commands that change or
reveal one player's data — `grant`, `revoke`, `reset` and `export` — require **permission level 3**
*and* must be run by a player or from the server console (or RCON): **command blocks, command-block
minecarts and functions cannot run them**. `manual complete` is for quest authors (the
[editor permission](Editor-Permissions.md) rule), or the console. The editor and custom-quest commands
follow the same player-or-console rule at level 3 (`editor allow|deny|check|list`, `custom export`,
`custom delete`) or level 4 (`editor lock|unlock`, `custom import`). A player without permission sees
only `contracts`.

The one player-facing branch is `/neroquests contracts` (see [Daily contracts](Daily-Contracts.md));
the [quest book](Quest-Book.md) is how players read their own quests.

## At a glance

| Command | What it does |
| --- | --- |
| `/neroquests grant <player> <quest>` | Completes a quest for a player, firing its rewards |
| `/neroquests revoke <player> <quest> [--repay]` | Clears a player's completion and counters for one quest |
| `/neroquests reset <player>` | Wipes all of a player's stored quest progress |
| `/neroquests reset <player> <quest> [--repay]` | Same as `revoke` — one behaviour, two spellings |
| `/neroquests list` | Lists every loaded quest with its chapter and scope |
| `/neroquests list <player>` | Per-quest status for one player |
| `/neroquests reload-check` | Per-source quest counts and what the last datapack load rejected |
| `/neroquests export <player>` | Prints one player's own progress as JSON (data-access requests) |
| `/neroquests manual complete <quest> <player>` | Ticks a quest's `manual` objectives for an online player |
| `/neroquests editor allow\|deny\|check <player>` | Manage or query the quest-editor allow-list |
| `/neroquests editor list` | Allow-list size, lock state, and whether you are on the list |
| `/neroquests editor lock\|unlock` | Freeze or reopen quest authoring for everyone below level 4 |
| `/neroquests custom export [<chapter>]` | Write custom quests as a datapack folder (the route to a real pack) |
| `/neroquests custom import <folder> [--dry-run]` | Copy a quest-pack folder from `datapacks/` into the custom store |
| `/neroquests custom delete <id> [confirm]` | Delete a custom chapter or quest (asks for `confirm` within 30 s) |
| `/neroquests contracts` | **Any player:** today's daily contracts and their status |
| `/neroquests contracts submit <contract>` | **Any player:** hand in one of today's contracts |

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
- **A revoked quest stays revoked until the player's progress changes.** The objective counters are
  snapshotted at revoke time; the engine will not re-complete the quest while every counter still
  matches that snapshot (a player still holding the items, a gate still open). Once something
  actually changes, the quest can complete normally again. `grant` overrides this.
- **Rewards are paid at most once.** A paid ledger records every quest whose rewards were paid, and a
  revoke leaves the quest in it: a later re-completion (or `grant`) completes it without paying. Add
  `--repay` at the end to remove it from the ledger so its next completion pays again:
  `/neroquests revoke Steve neroquests:intro/first_steps --repay`.
- Quests that depend on the revoked one (prerequisite or `quest_complete`) are **not** revoked with
  it; the command tells you how many completed dependents stay complete.

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

Because it forgets the paid-reward record too, quests that are measured rather than counted (holding
items, a dimension or structure, an open gate, completed quests) can pay that player again once they are
back. To undo one quest without re-paying it, use `revoke <player> <quest>` instead, which keeps the
record unless you add `--repay`.

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

Reports on the quest, chapter and contract definitions the server is **currently running** — those
of the last world start or `/reload` — without re-reading any pack file:

```text
Loaded quest definitions: 42 quest(s) in 5 chapter(s).
By source: spine 30 / pack mymod 9 / pack myserver 3 / custom 0
Pack nerospace: mod not installed — 0 quest(s) shown with a badge, 16 not loaded.
2 validation problem(s):
  [dropped] mymod:start/cheat - reward type neroquests:gate is only allowed in quests of the 'neroquests' namespace (...)
  [ignored] neroquests:intro - lists unknown quest neroquests:intro/missing
```

The second line counts loaded quests per **source**: NeroQuests' own spine, each mod's or server
datapack's pack namespace, and quests made in game. Packs whose mod is not installed are listed next,
with how many of their quests are shown with a "mod not installed" badge (`show_when_absent`) and how
many are not loaded. Then every dropped or ignored definition with the rule it broke.

It also resets the "missing content" warnings, so the next evaluation logs each missing id again.
To pick up edited pack files, run `/reload` first (the quest book updates for everyone within a
second), then `reload-check` for the report.

Two severities:

| Severity | Meaning |
| --- | --- |
| `dropped` | The whole definition is not loaded |
| `ignored` | The definition loaded, but part of it (an entry, a reference) was skipped |

The loader never crashes on bad content — it drops the offending entry and carries on — so this
command is how you find out that it did. Everything it prints is also in the server log; the report
just saves you reading it. See [Quest format](Quest-Format.md) for what a valid definition looks
like.

`reload-check` covers only NeroQuests. A vanilla `/reload` is picked up automatically (the mod notices
the resource manager changed and re-reads within a second); `reload-check` never loads anything itself.

## `manual complete`

```text
/neroquests manual complete <quest> <player>
```

Ticks every `manual` objective of `<quest>` for an **online** player (see
[Objectives](Objectives.md#neroquestsmanual)). The quest must be available to them — visible, its
prerequisites met — and not already complete. If its other objectives are done too it completes
through the normal path and pays its rewards; otherwise it completes once they are.

Who may run it is decided on the server each time: a player with permission level `editorMinOpLevel`
or on the editor allow-list — **Creative mode alone is not enough** — unless the editor is locked (then
level 4 only; see [Editor permissions](Editor-Permissions.md)), or the server console. Each person may
tick at most 10 a minute. Such players may tick **custom** quests only: on a built-in or quest-pack quest
it takes a trusted operator (permission level 3 as a player, or the console), like `grant`. It is refused
for a target whose kept-rewards queue is full, so it can never push out rewards they are still owed. Each use is counted in the log as an admin action, never with names.
`<quest>` is a full id (`mymod:town/statue`); suggestions list the quests that have a manual objective.

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

The export also holds the player's owed and kept rewards, their paid ledger and repeat timestamps,
and under `custom_quests` the custom quests they authored (ids, titles, descriptions, timestamps) and
whether they are on the editor allow-list.

Run from the server console, the header does not repeat the player's UUID (the console's output is
the server log); the rows themselves never contain a UUID or a name.

This is the POPIA/GDPR data-access surface described in
[`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md). It contains **only** the named
player's rows: no other player's progress, and no shared `scope: server` progress (which holds no
player identifiers at all).

Chat is not a file transfer — an export longer than 32 000 characters is cut off with a note. That is
far beyond any realistic pack.

## `editor` and `custom`

The quest-editor commands — the allow-list, the lock, export, import and delete — are described with
the settings they work with on [Editor permissions](Editor-Permissions.md). In short:

```text
/neroquests editor allow Steve          (level 3)
/neroquests editor check Steve          (level 3) -> on the list or not, no name echoed
/neroquests editor list                 (level 3) -> count, lock state, whether you are on it
/neroquests editor lock                 (level 4)
/neroquests custom export               (level 3) -> neroquests_exports/<date-time>/ (a template, not loaded)
/neroquests custom import mypack --dry-run   (level 4)
/neroquests custom delete custom:k3m7q2ab    (level 3), then again with confirm
```

`reload-check` ends with the editor's lock state, allow-list size, escrow mode and the custom store's
counts against its limits.

## Privacy notes

- Every message goes to the invoker only. Nothing is broadcast to other operators, which also keeps
  results out of `latest.log` under the `logAdminCommands` game rule.
- Nothing here logs player identity, in line with the rest of the mod
  ([Data storage](Data-Storage.md)).
- If a subcommand hits an unexpected error you get a short message instead of a stack trace in chat;
  the trace goes to the log, and the anonymous crash report ([Telemetry](Telemetry.md)) carries the
  subcommand name only — never its arguments.
