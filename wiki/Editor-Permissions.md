# Editor permissions (server admins)

Players can make their own quests in game: custom chapters and quests that live in the world save,
next to NeroQuests' own story and the quest packs your mods ship. This page is for server operators —
who may author, how to lock it down, the limits, and what is stored. The player side is
[Quest editor](Quest-Editor.md).

Custom quests use the namespace `custom` (ids such as `custom:k3m7q2ab/x9dc4tfe`, both parts random —
never taken from a title or a name). Only custom content can be edited: NeroQuests' own quests and every
quest pack are **read-only**, and no custom quest can change, unlock or re-reward them.

## Who may author

Decided on the server, from the player's real state, for every single edit — nothing the game client
says is trusted. A player may author when **any** of these is true:

| Route | Setting |
| --- | --- |
| They are in **Creative** mode | `editorRequiresCreative` (default `true`). Set it `false` and Creative alone no longer qualifies. |
| They have operator **permission level** `editorMinOpLevel` or above, in any game mode | `editorMinOpLevel` (default `2`, range 1–4) |
| They are on the **editor allow-list**, in any game mode | `/neroquests editor allow <player>` |

…and none of these is true:

- they are a **spectator**, or **dead**;
- the editor is **locked** (`/neroquests editor lock`) — then only permission level 4 may author.

### What Creative mode alone does not unlock

Creative players can already spawn any item, so letting them put items and experience on a quest adds
nothing new. Credits are different: Creative mode cannot normally create money. So a stricter rule — the
player passes everything above **and** has permission level `editorMinOpLevel` or is on the allow-list —
applies to:

- **credit rewards**: setting a `currency` reward, or changing a custom quest that pays credits in any way
  (objectives, text, position, deletion). Creative-only authors are told to ask an operator;
- **`/neroquests manual complete`** on a custom quest: Creative mode alone cannot tick `manual`
  objectives.

The editor lock still beats this rule for everyone below level 4.

Losing the right mid-edit (leaving Creative, being de-opped, removed from the list, a lock) takes effect
at once: the next edit is refused, and within a second the player's book is told they may no longer edit.

## Commands

All of them need a player or the server console (or RCON) as the source — **never** a command block,
a command-block minecart or a function. Output goes to you only and never names anyone.

| Command | Level | What it does |
| --- | --- | --- |
| `/neroquests editor allow <player>` | 3 | Add a player (online name, or UUID) to the allow-list |
| `/neroquests editor deny <player>` | 3 | Remove them |
| `/neroquests editor check <player>` | 3 | Say whether that player is on the list |
| `/neroquests editor list` | 3 | How many players are on the list, whether the editor is locked, and whether *you* are on it |
| `/neroquests editor lock` | 4 | Freeze authoring for everyone below level 4 (for a finished quest set) — also `custom delete` and `manual complete` on custom quests by level-3 players; the console keeps working |
| `/neroquests editor unlock` | 4 | Reopen it |
| `/neroquests custom export [<chapter>]` | 3 | Write custom quests as a pack template outside `datapacks/` (see below) |
| `/neroquests custom import <folder> [--dry-run]` | 4 | Copy a quest-pack folder into the custom store |
| `/neroquests custom delete <id> [confirm]` | 3 | Delete a custom chapter (with all its quests) or one quest |

`editor list` deliberately shows a count, not the entries; use `editor check <player>` for one player.
The allow-list and the lock are stored in the world and survive restarts.

`custom delete` asks first: run it, read what it will remove, then run it again with `confirm` within
30 seconds. Deleting a quest removes every player's progress and paid-reward record for it; rewards
already handed out stay with the players, and rewards still owed to someone are still delivered.

## Limits

| Setting | Default | Range | Meaning |
| --- | --- | --- | --- |
| `customQuestMaxTotal` | 500 | 0–5000 | Custom quests per world |
| `customChapterMax` | 20 | 0–200 | Custom chapters per world |
| `customRewardMaxStacks` | 9 | 0–9 | Item-stack and loot-table rewards per quest together (each item reward at most one full stack; each loot table one slot) |
| `customRewardMaxXp` | 500 | 0–100000 | Experience per completion |
| `customRewardMaxCurrency` | 1000 | 0–1000000 | Credits per completion (through the economy's budgeted payouts with NeroEconomy) |
| `customRewardsEscrowed` | `false` | — | Escrow mode, below |
| `customLootTablePrefixes` | `minecraft:chests/,minecraft:gameplay/fishing` | — | Loot tables a custom quest may roll: ids starting with one of these comma-separated prefixes. Empty = none. Checked when the reward is set and again at payout |
| `customCurrencyDailyCap` | 2000 | 0–1000000000 | Credits all custom quests together may pay in this world per UTC day (0 = no cap). Past it a payout is reduced or withheld and the player is told |
| `customRewardsPerPlayerDaily` | 20 | 0–10000 | Paid custom-quest completions one player may receive per UTC day (0 = no limit). Later completions that day still complete, without rewards, and the player is told |

Fixed limits: at most 12 objectives and 9 rewards per quest; objective counts 1 to 1,000,000; titles 64
characters, descriptions 512, manual labels 64; node positions −512 to 512; at most 16 prerequisites;
repeat cooldowns from 60 seconds to 30 days. Each author is also rate-limited (20 edits a second,
200 a minute), and manual completions to 10 a minute per person. Editor requests are checked for
permission and rate limit **before** the server reads their content; past 40 a second per player they are
dropped unread, and refusals are answered at most twice a second. While a player's kept-rewards queue is
full, `manual complete` on custom quests is refused for them, and custom-quest rewards never push a
player's other kept rewards (or an author's escrow refund) out of that queue.

The daily per-player tally is held in memory only (the player's game UUID and a count for the current UTC
day), never saved, and starts again at UTC midnight, on restart, and after an erasure request.

Custom quests may reward items, a loot table, experience and credits — **never** a progression gate,
faction standing or a mod hook. They may depend on NeroQuests' own quests, on pack quests and on other
custom quests; NeroQuests' quests and packs can never depend on a custom quest.

## Escrow mode

With `customRewardsEscrowed=true` the server stops creating reward items out of nothing — meant for
servers that let non-operators author:

- Setting item rewards takes the items from the author's inventory: every item reward times the number
  of **copies** the author deposits (1–64). Only plain items (no names, enchantments or other data).
- Each paid completion uses up one copy. When the deposit runs out, completions pay no items; the
  player is told, and so is the author if online. Experience and credits are unaffected.
- Changing the item rewards or deleting the quest **refunds** what is left to the author — into their
  inventory, or kept for them (also while offline) and delivered on their next join. If the author's
  data was erased, the deposit is **discarded** (only the item count is logged) — it is never handed to
  whoever happened to change or delete the quest.
- Loot-table rewards are refused in escrow mode (random loot cannot be deposited), and a custom quest
  saved with a loot table before escrow was switched on pays nothing for it while escrow is on.
- A custom quest saved before escrow was switched on pays no items until it is re-saved with a deposit.

## Exporting and importing

`/neroquests custom export [<chapter>]` writes a **template** to `neroquests_exports/<UTC date-time>/` in the
world folder — deliberately *outside* `datapacks/`, so the world never loads it by itself (a loaded copy
would sit next to the custom originals and pay every reward twice). It holds a `pack.mcmeta`, the
chapters and quests as ordinary quest-pack JSON in the namespace `custom_export`, plus
`assets/custom_export/lang/en_us.json` holding every title, description and manual label under
translation keys (`quest.custom_export.<chapter>.<quest>.title` and so on). It contains **no author
information**. Repeatable quests become one-off quests (packs cannot repeat), and a reference to another
chapter's custom quest or to another pack's quest will not load as written; the command tells you how
many there were.

To turn custom quests into a real pack (the promotion path):

1. Rename the namespace `custom_export` to your own (folder names and the ids inside the files).
2. Move `data/` into a datapack or a mod, and `assets/` into a resource pack or the same mod (a datapack
   cannot carry language files).
3. Delete the custom originals (`/neroquests custom delete <chapter>`), so nothing exists twice.
4. `/reload`.

`/neroquests custom import <folder> [--dry-run]` goes the other way: it reads quest-pack JSON from
`datapacks/<folder>/`, validates it exactly as a pack load would, and copies every chapter and the quests
it lists into the custom store with new random ids — titles resolved through the folder's own
`assets/<namespace>/lang/en_us.json` when present. Every custom rule and limit applies, and nothing is
written unless the whole import is clean; `--dry-run` only reports. In escrow mode, item and loot-table
rewards cannot be imported.

## What is stored, and privacy

One saved-data file in the overworld's `data/` folder, `neroquests:custom_quests`, with a recovery backup
like the quest progress file (see [Data storage](Data-Storage.md)):

- the custom chapters (title, icon, order) and quests (title, description, icon, position,
  prerequisites, objectives, rewards, repeat setting, timestamps, escrow counts);
- for each quest, `created_by`: the **author's game UUID**, so the author can be shown as "you" and their
  data exported and erased. It is never shown as a name — everyone else sees "another player";
- the editor allow-list (the **game UUIDs** of allow-listed players — the only other player identifier in
  this file) and the lock.

`/neroquests export <player>` includes the quests that player authored (with their own titles and
descriptions) and whether they are on the allow-list. Erasure (`/neroland data erase`) clears
`created_by` on their quests — the quests stay — and removes them from the allow-list. Author-written
text is never written to the server log and never sent with crash reports. See
[`../PRIVACY.md`](../PRIVACY.md).

## Checking it

`/neroquests reload-check` ends with the editor's state: locked or not, the allow-list size, escrow on or
off, custom chapters and quests against their limits, and how many editor changes were made since the
server started (a count only — never who or what).

Operators and servers running with `online-mode=false` are outside what a mod can defend: an operator can
always change anything, and without online mode player UUIDs are not verified, so anyone can join as
anyone — including as an allow-listed author or an operator. What NeroQuests does for operators is keep
count: every operator action (grant, revoke, reset, export, manual complete, allow/deny, lock/unlock,
custom export/import/delete, editor changes) is counted by kind for the running server, and the counts —
never who or what — are in the log and in `/neroquests reload-check`.
