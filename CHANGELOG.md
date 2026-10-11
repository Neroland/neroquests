# Changelog

All notable changes to **NeroQuests** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0-beta.1] - 2026-10-11

Optional NeroEconomy integration: budgeted quest payouts, daily contracts and an economy objective.
NeroEconomy stays optional — NeroQuests builds, loads and runs without it.

### Added

- **Budgeted quest payouts.** With NeroEconomy 0.3.0 or later installed, `neroquests:currency` credit
  rewards are paid through its budgeted payout API (reason `quest_reward`) with a request key of
  `nq:<sequence>:<quest id>` (never a player id or name). A refused payout is explained to the player
  in chat and kept as **owed** in the quest data, then retried on their next join and every five
  minutes while online with the same key, so it can never pay twice. Other currencies, or no
  NeroEconomy, keep Core's `CurrencyApi.deposit`, whose result is now checked (a failed deposit is
  also kept as owed).
- **Daily contracts** (`/neroquests contracts`, `/neroquests contracts submit <contract>`, open to
  every player): a few data-driven hand-in contracts drawn per UTC day from
  `data/<namespace>/neroquests/contracts/`, each doable once per player per day, paid under the reason
  `daily_contract`. A contract is offered only while the economy's budget can still pay it; without a
  budgeted economy nothing is offered. Eight vanilla-item contracts ship. New settings
  `dailyContractsEnabled` (default `true`) and `dailyContractsPerDay` (default `3`).
- **`neroquests:economy` objective** — `event` `sold` | `bought` | `earned`, optional `item` or
  `tag`, `count`. Fed by NeroEconomy's trade events; `earned` excludes NeroQuests' own payouts.
  Without an economy mod it loads and never advances.
- **Market quests** — *Open for Business*, *Iron Trader* and *Seasoned Trader* on a new row of the
  Industrial Age chapter, using economy objectives and NeroEconomy's milestone gates. Hidden behind
  the `neroeconomy:first_sale` gate, so they never appear without NeroEconomy.
- **Hand-in objectives:** `collect_item` takes `"consume": true` to take the items when the quest
  completes (all hand-ins of a quest are checked first, then taken together).
- **`once_per_world`** on `neroquests:currency`: pay only the first player in the world who completes
  the quest.
- **Quest sources.** Every quest and chapter now has a source resolved from its namespace: `spine`
  (`neroquests:`), `pack` (any other datapack namespace — a mod's own quest pack or a server datapack)
  or `custom` (reserved for quests made in game). Spine and pack definitions are read-only at runtime.
  A datapack file in the reserved `custom` namespace is dropped with a reason in `reload-check`.
- **Quest-pack rules.** A pack quest is dropped with a named reason (never a crash) when it carries a
  `gate` or `reputation` reward, uses `"scope": "server"` without a chapter of its own namespace that
  sets `"allow_server_scope": true`, depends (prerequisite or `quest_complete`) on a quest outside its
  own namespace and the spine, or uses literal text instead of translation keys for its title or
  description. A pack chapter's title must be a key too, and a pack chapter may list only its own
  namespace's quests.
- **Packs follow their mod.** A pack whose mod is not installed loads nothing. A chapter can set
  `"show_when_absent": true` to stay visible anyway: its quests show a "needs a mod that is not
  installed" badge in the book and can never be completed. A server datapack's own namespace always
  loads. Pack quests never use `missingModObjectivePolicy=autocomplete`.
- **`neroquests:manual` objective** — `{ "type": "neroquests:manual", "label": "<lang key>" }` for
  "build a thing" quests. It never advances on its own; a quest author ticks it for an online player
  with `/neroquests manual complete <quest> <player>` (a player who may author quests — see the editor
  permissions below — or the console). Each use is counted in the log, never named.
- **`neroquests:visit_structure` objective** — stand inside a generated structure, by `structure` id
  or structure `tag` (exactly one). Checked on the once-a-second sweep against the structure pieces of
  the chunk the player stands in; once visited it stays done. No position is stored.
- **Completion signals for other mods.** Every quest completion publishes an anonymous crossing on
  Neroland Core's event bus: `neroquests:quest_completed` (scope = quest id, value = how many times
  that quest has been completed in this world), and `neroquests:chapter_completed` (scope = chapter
  id) when it finishes a chapter for that player. They never say who. The world counts are stored with
  no player identifier.
- **`neroquests:mod_hook` reward** — `{ "type": "neroquests:mod_hook", "gate": "<ns>:hook/<id>" }`
  opens a player-scope record gate that the pack's own mod ships, for the completing player (also when
  offline), so that mod can apply its own effect by listening on Core's gate events. The gate must be
  in the quest's own namespace under `hook/`; undefined gates grant nothing and are logged once. Hook
  gates gate nothing and are written even with `gateWritesEnabled=false`.
- **`/neroquests reload-check` reports per source** — `spine N / pack <namespace> N … / custom N` —
  lists packs skipped because their mod is not installed, and keeps naming every dropped definition
  with the rule it broke.
- **Wiki:** [Quest format](wiki/Quest-Format.md) is now the complete contract for quest packs, and the
  new [Shipping a quest pack](wiki/Shipping-a-Quest-Pack.md) page walks a mod author through shipping
  quests with data files only, including a copyable validator test.
- **Custom quests (server side of the in-game editor).** A new world store (`neroquests:custom_quests`,
  with recovery backup) holds chapters and quests made in game under random `custom:` ids. They load
  through the same definitions pipeline as datapacks and follow extra rules: player scope only, 1–12
  objectives of the standard types, at most 9 rewards of `item` (one stack each), `loot_table`, `xp` and
  `currency`, never gate, reputation or mod_hook; spine and pack quests may never depend on a custom
  quest. Every edit re-checks the author's permission on the server, refuses spine/pack ids, validates
  every id against the running game, rejects prerequisite cycles, previews the whole pipeline before
  applying, then re-syncs everyone like a `/reload` (unfinished progress on changed objectives resets).
  New settings: `customQuestMaxTotal` (500), `customChapterMax` (20), `customRewardMaxStacks` (9),
  `customRewardMaxXp` (500), `customRewardMaxCurrency` (1000).
- **Editor permissions.** Who may author (and tick `manual` objectives): Creative mode
  (`editorRequiresCreative`, default `true`), permission level `editorMinOpLevel` (default 2) or the
  editor allow-list; never spectators or dead players; while locked, only level 4. Per-author rate
  limits on edits (20/s, 200/min) and on manual completions (10/min). The definitions sync tells each
  player whether they may author (`can_author`), re-sent when their game mode, level, the lock or the
  allow-list changes.
- **Author text is inert.** Titles (64), descriptions (512) and manual labels (64) are stripped of
  formatting codes, control/format characters and braces, stored as plain strings and shown only with
  literal text components — never as translation keys, never in logs or crash reports.
- **`neroquests:loot_table` reward** — `{ "type": "neroquests:loot_table", "table": "<id>" }` rolls a
  loot table per completion for the player; unfit or offline items are kept like item rewards.
- **Repeatable custom quests** — off by default; with a cooldown of 60 s to 30 days a completed quest
  re-arms per player and pays again at most once per cooldown (a per-player last-paid time in the quest
  data, exported and erased; `revoke --repay` clears it).
- **Escrow mode** (`customRewardsEscrowed`, default `false`) — item rewards of custom quests are
  deposited from the author's inventory (1–64 copies), paid from the deposit, and refunded to the author
  (kept for them if offline) when changed or deleted; loot-table rewards are refused while it is on.
- **Commands:** `/neroquests editor allow|deny|check|list` (level 3, shows counts, never names),
  `/neroquests editor lock|unlock` (level 4), `/neroquests custom export [<chapter>]` (level 3; writes
  `datapacks/neroquests_custom_export/` with translation keys and an `en_us.json`, no author ids),
  `/neroquests custom import <folder> [--dry-run]` (level 4; validated, all-or-nothing, within the caps)
  and `/neroquests custom delete <id> [confirm]` (level 3; confirm within 30 s; prunes every player's
  progress and paid record for it). Player or console only, never command blocks. `reload-check` shows
  the editor lock, allow-list size, escrow mode and custom counts.
- **Data export** now includes the custom quests a player authored and their allow-list membership;
  **erasure** clears `created_by` on those quests (they stay) and removes them from the allow-list.
- **Wiki:** new [Editor permissions](wiki/Editor-Permissions.md) (admins) and
  [Quest editor](wiki/Quest-Editor.md) (players) pages.

### Changed

- *Pressed and Powdered* and *Alloy Ambitions* are now hand-ins, so one item set no longer completes
  them on every alternate account.
- `/neroquests` subcommands carry the operator check individually so `contracts` can be open to
  players; `list` and `reload-check` still require permission level 2 (`grant`, `revoke`, `reset`
  and `export` now need level 3 — see below).
- Owed rewards and daily-contract records are stored with the player's quest data, exported by
  `/neroquests export` and erased with it (see `PRIVACY.md`).
- The jar ships a `pack.mcmeta` for its bundled resources and data (`pack_format` 84, accepted up to
  121), so every supported Minecraft version reads them as a valid pack.
- The `telemetryEnabled` setting's comment now says crash reporting is on by default and that turning
  it off takes effect on restart. The README and the Telemetry wiki page state the opt-out plainly,
  and `PRIVACY.md` carries the telemetry notice the store pages show.
- An `economy` objective's `earned` exclusion of NeroQuests' own payouts is one shared check, covered
  by a test.
- **The built-in story line is 28 quests** (was 30). *Breathing Room*, *Full Larder* and *Settled
  Ground* left The Space Race: NeroColonies now ships them in its own quest pack, so they appear only
  where that mod is installed. A new first Groundwork quest, *What This Book Is*, explains view and
  edit mode, chapters arriving with other Neroland mods, and player-authored chapters (it is not a
  prerequisite of anything, so existing progress is untouched).
- **Companion-app link schema 2.** Quest and chapter rows carry `source` (`spine`/`pack`/`custom`),
  `namespace`, `mod_present` and English `title_text`/`description_text` (custom quests send their
  player-written text only there); chapters carry `order`. New sections `custom_authoring` (a boolean),
  `owed` (the claim list) and `progress` (a full snapshot instead of an empty body); new events
  `reward_owed` and `reward_claimed`; `claim_reward` accepts an owed entry `key` and answers a repeated
  key as already settled. Two app alerts with fixed text: "A reward is waiting for you" and "A new
  chapter appeared". Schema-1 fields stay for this release. See [Link module](wiki/Link-Module.md).

### Migration

- On the first load of a world's quest progress after updating (store format 2), progress, the paid
  ledger, revoke markers, repeat timestamps, owed rewards, once-per-world slots and the world completion
  counter recorded for `neroquests:space_race/breathing_room`, `full_larder` and `settled_ground` move to
  `nerocolonies:colony_chronicle/breathing_room`, `full_larder` and `settled_ground`. Completed quests stay
  completed and paid; running it again changes nothing. Without NeroColonies installed the moved rows
  are kept like any other row for a quest that is not loaded.

### Fixed (audit)

- **A quest pays its rewards at most once.** A per-player paid ledger (and a world-level one for
  `scope: server` quests) is kept with the quest data, exported and erased with it. Revoking or
  resetting a quest no longer lets it pay again; `/neroquests revoke <player> <quest> --repay` (also
  `reset <player> <quest> --repay`) is the explicit way to allow one more payout. A revoked quest
  stays revoked until the player's progress on it actually changes, instead of being re-completed by
  the next sweep. Revoke warns how many completed dependents stay complete. Existing completions are
  entered into the ledger on first load.
- **Erasing an online player** no longer re-completes (and re-pays) their quests a second later: they
  are left alone until they rejoin.
- **Retention** no longer purges players who play daily but have nothing left to progress (joining
  counts as activity), and the sweep now runs once per in-game day as well as at start-up.
- **Missing content can no longer complete a quest for free.** A quest with no objective present in
  the installation is not completable under either `missingModObjectivePolicy`; a completion that
  relied on `autocomplete` pays no rewards unless the new server setting `degradedCompletionsPay`
  (default `false`) is on. An id that does not resolve although its mod is loaded (a typo) now drops
  the quest with a reason in `/neroquests reload-check`. A `gate_open` objective naming a gate nothing
  defines is treated as missing content instead of blocking forever. `custom_event` presence is decided
  by the quest's namespace and by channels actually seen this session.
- **Any decode error drops the whole quest, chapter or contract file** instead of loading a partial
  definition; an unknown `scope` is a decode error rather than an exception.
- **Changed objectives reset stale progress.** Each progress row remembers which objective list it was
  counted against; when a reload changes it, unfinished rows start over (finished ones stay finished
  without paying again) and rows of removed quests expire with `questDataRetentionDays`.
- Owed rewards: one invalid (permanently refused) entry no longer blocks the rest of the queue — it is
  kept but not retried, and the player is told once; every owed entry keeps one stable request key;
  refused retries no longer count as activity; the player is told when the 64-entry cap drops an old
  entry. `once_per_world` claims are per reward rather than per quest.
- **Item and experience rewards are no longer lost.** A recipient who is offline, dead or leaving, or
  whose inventory is full, has the reward kept for them (with the same 64-entry cap as owed credits)
  and handed over on join, on respawn or once there is room, oldest first and possibly in part. The
  server setting `owedItemsDropWhenFull` (default `false`) restores dropping the overflow at the
  player's feet. Kept rewards are included in `/neroquests export` and erased with the player. A
  player-scope gate reward for an offline player is opened directly (when its requirements hold).
- **Server-scope quests reward everyone who could take part**, not the first player in the list: the
  triggering player and every online player who has the quest available are each paid once (their own
  ledger entry), with currency paid per player and anything undeliverable kept. A server gate is still
  set once for the world.
- **NeroLink `claim_reward` hands over kept rewards** (optionally for one quest): owed credits even
  while the player is offline, items and experience while they are in the world. The response lists
  what was delivered and what remains, and keeps its earlier fields.
- **Quest re-evaluation no longer recurses or runs off-thread.** A gate opened by a reward inside an
  engine pass re-evaluates after that pass instead of recursively; gate, threshold and economy events
  from other threads are moved onto the server thread first.
- **Reward amounts are capped in the data format**: experience and currency at 1,000,000 per reward,
  item rewards at 2,304 (a full inventory). A quest beyond a cap is dropped with a reason in
  `/neroquests reload-check`.
- **Gate rewards respect the gate's own scope and requirements.** A server-scope quest only sets a
  server gate when the gate is declared server-scope and its requirements hold; player and team gates
  are opened per player. An undefined gate id is logged once. `gate` and `reputation` rewards are only
  accepted in quests of the `neroquests` namespace (others are dropped at load). United Frontier no
  longer rewards the `deep_space` gate it already requires, and stays hidden until First Colony.
- **A corrupt quest-progress file is kept and recovered from backup.** NeroQuests now uses Neroland
  Core's saved-data recovery (last-known-good backup) and first copies a damaged file aside as
  `quest_progress.corrupt-<time>`. Erasure, `reset <player>` and retention purges also clear the
  player from the backup at once.
- `/neroquests grant`, `revoke`, `reset` and `export` need permission level 3 and a player or the
  server console; command blocks and functions can no longer run them. A console export no longer
  prints the player's UUID into the log.
- `/neroquests reload-check` reports on the definitions actually in use (the last `/reload`) instead
  of re-reading pack files on its own, and resets the missing-content warnings.
- NeroQuests registers with Neroland Core's player-data erasure right after its config, before
  anything else that could fail during start-up.
- **The quest book, the server and NeroLink agree on quest state.** One shared rule decides completed,
  locked, in progress and available everywhere; each prerequisite is checked in its own scope (a
  player quest can wait on a world milestone), and a started quest whose prerequisite is not complete
  shows as locked. A `quest_complete` objective resolves the quest it names by that quest's own scope
  in the same way.
- **Hidden quests stay hidden.** A quest behind a closed `visible_gate` is no longer sent to that
  player's client at all (nor named in a chapter, a NeroLink prerequisite list or another quest's
  objective, which reads "an undiscovered quest"); opening or closing a gate re-syncs the book.
- Quest progress is sent to clients at most once per player per tick; a burst of shared (server-scope)
  progress no longer sends every player one update per evaluation.
- **Large quest packs sync reliably.** Quest definitions are sent in pages of at most 512 KiB and the
  book updates only once the whole set has arrived; a single definition too large to send is skipped
  with a warning instead of breaking the sync. Malformed sync packets are rejected instead of being
  half-read.
- **Crash reporting is self-contained and sends less.** NeroQuests uses its own Sentry client
  instead of the global one (so it no longer clashes with Neroland Core's or another mod's reporting
  and installs no global crash handler), and never sends exception or log message text — only error
  types and stack frames. PRIVACY.md and the Telemetry wiki page list exactly what is sent.
- Stopping a world drops everything NeroQuests held about it at once, instead of keeping the stopped
  server in memory until the next world started.
- Removed dead code (an unused Gradle file for `common`, unused trigger hooks and accessors) and a
  build comment that described a JEI plugin that does not exist.
- `/neroquests list`, `list <player>`, `reload-check` and `contracts`, and the quest book's fallback
  labels for ids without a display name, are now fully translatable (no hard-coded English words or
  colour codes).
- The NeroEconomy bridge detects the installed NeroEconomy API without reflection.
- **The Space Race no longer shows quests a pack cannot finish.** Each of its quests, and *Alloy
  Ambitions*, stays hidden until the milestone that makes it finishable is open (Industrial Power,
  Reached Orbit, First Colony, or a colony mod's Colony Established), so with only Neroland Core the
  chapter ends after *Launch Materials* instead of showing a permanently stuck quest. The Story
  chapters wiki page no longer claims the whole line is playable without other mods, and documents
  that `gateWritesEnabled=false` hides the Space Race unless another mod opens Industrial Power.
- **Loader metadata is accurate.** Each jar now declares only the Minecraft version it was built for
  (for example `>=26.2 <26.3` / `[26.2,26.3)`), the Fabric manifest requires Neroland Core below 2.0.0
  and Java 25 (it said Java 21 and left Core uncapped), and all three manifests carry a real
  description, the homepage and the issue tracker.
- **Documentation brought up to date.** The README and the wiki home page describe the current beta
  (30 built-in quests, eight objective types, five reward types, nine loader × version builds,
  Neroland Core 1.15.0) instead of an early skeleton; the Core notes cover the optional NeroEconomy
  dependency and Core's saved-data recovery; the store pages list Minecraft 26.3 and describe the
  stored data accurately (they promised a per-player tracking opt-out that does not exist); the
  `quest_complete` objective page states the scope rule.

### Build

- Requires **Neroland Core 1.15.0** (was 1.13.0): its saved-data recovery and the offline gate API
  are used. Every loader's manifest floors at that version and stays below 2.0.0.
- NeroEconomy is a `compileOnly` dependency (`neroeconomy_version=0.3.1-beta.1`), resolved from
  `mavenLocal()` or its GitHub Packages repository when credentials are present. Declared optional in
  every loader's metadata (Fabric `suggests`, `breaks` below 0.3.0-beta.1).
- The Eclipse compiler used by `ecjCheck` is pinned to `3.46.100` instead of the latest release.

## [0.3.0-beta.1] - 2026-09-24

EMI compatibility. No gameplay, id, tag or config change.

### Added

- **EMI support.** NeroQuests adds no recipes of its own, so nothing needed a plugin. The build now
  compiles against the community EMI Unofficial Port (Unstable), the only EMI build for Minecraft 26.x,
  and dev clients load it with `-PwithEmi` (default runs stay JEI-only). EMI stays optional.

## [0.2.0-beta.1] - 2026-09-20

Minecraft **26.3** support, plus the changes previously listed under *Unreleased*.

### Added

- **`neroquests:custom_event`, the seventh objective type** — a quest can now wait on another mod
  reporting that one of its tracked quantities crossed a threshold: a region's pollution, a colony's
  life support, a boss changing phase. The publisher fires a crossing on Neroland Core's
  `ThresholdEvents` bus and NeroQuests listens, so neither mod imports the other and both depend only
  on Core. This closes the last gap in the objective set: `gate_open` already covered *your*
  progress, and this covers *the world's*.
- The objective matches on exactly what a crossing carries and nothing else — `channel`,
  `event_scope` (the crossing's scope key, named so it can never be read as the quest's own `scope`),
  `direction` (`rising` / `falling` / `any`), `min_value` / `max_value` and `count`.
- **Channels follow a `<modid>:<channel>` convention, and that convention is load-bearing.** The
  namespace is how the objective works out whether the publishing mod is installed at all: a channel
  nobody publishes can never fire, so it is treated as missing content and degrades under
  `missingModObjectivePolicy` — `skip` or `autocomplete` — exactly like an unregistered item id or an
  unloaded dimension, instead of blocking the quest forever. A publisher that is installed but
  switched off (NeroColonies' `thresholdEventsEnabled`, Nerotech's `pollutionEventThreshold`) is
  deliberately *not* missing content: the objective waits, because an operator can turn it back on.
- **`audience` decides who a broadcast is credited to, and the default is the safe one.** A crossing
  names a place or a system and never a person — Core's contract, for POPIA/GDPR reasons — so nothing
  in the event says whose quest should move. `audience: world` (the default) moves the quest's
  **shared** counter exactly once per crossing, however many players were online; `audience:
  everyone` is the explicit opt-in that credits every online player working on the quest. Without
  that split, one colony's good news would silently complete a personal quest for a player asleep on
  the other side of the world.
- Shared counters move **once** per crossing even though the trigger fans out over every online
  player: each objective instance is claimed by the first pass that credits it, by identity.
- A `custom_event` left on `audience: world` inside a `scope: player` quest has no shared counter to
  write and could never advance, so `QuestDefinitions` now **drops that quest at load time** with the
  reason named in `/neroquests reload-check`, rather than shipping a counter that never ticks. The
  check runs through a new `ObjectiveSpec.unusableInScope(QuestScope)` seam, so it is a general
  "this objective cannot work here" rule and not a special case for one type.
- `CoreEvents.onThreshold` is now subscribed from `QuestTriggers.init()`, alongside the existing
  progression-gate subscription. Both of Core's buses are **add-only** — there is no way to remove a
  listener — so the only correct lifecycle is one subscription per JVM, which common init gives:
  nothing leaks across an integrated-server world switch and there is nothing to unsubscribe on
  server stop. Per-*server* state is dropped instead, by the existing server-instance check in
  `serverTick`, and the handler does nothing while there is no current server.
- **Three new quests in The Space Race**, branching off *First Colony* and taking the chapter to 10
  (27 across the whole spine): **Breathing Room** (`nerocolonies:oxygen`, `scope: server`), **Full
  Larder** (`nerocolonies:food_stock`, `scope: server`) and **Settled Ground**
  (`nerocolonies:structures` past its third building, `scope: player` with `audience: everyone`).
  They are the `alloy_ambitions` pattern applied to events: an optional branch that fills in when a
  colony mod is present and degrades quietly when it is not, so the spine still plays through on a
  Core-only server.
- Quest-book line for the new type (`gui.neroquests.objective.custom_event`), showing the raw channel
  id — its meaning belongs to the publishing mod, and NeroQuests has no dependency on it to ask for a
  nicer name.

### Documentation

- `wiki/Quest-Format.md` gains a full `custom_event` section: the field table, the
  `<modid>:<channel>` convention, the broadcast-versus-player-scoped semantics, the degradation
  behaviour, and **a table of the six channels that actually exist today** across Nerotech,
  NeroColonies and NeroCreatures — what each scope key and value means, and what `rising` means on
  each (it is *not* a synonym for "good": rising is recovery on `nerocolonies:oxygen` and a
  worsening on `nerotech:pollution`).
- `wiki/Objectives.md` documents the type in full alongside the other six; `wiki/Story-Chapters.md`
  covers the new branch; `USING-CORE.md` moves `event.ThresholdEvents` out of *Deferred* and into the
  consumed-API table.

### Notes

- `ThresholdEvents` shipped in Core **1.7.0**, so the objective itself needed no Core bump; the
  release as a whole requires Core **1.13.0** for the 26.3 port (below).
- Core exposes no `removeCrossingListener`. That is fine for a once-per-JVM subscription, but it does
  mean a listener cannot be withdrawn — worth an upstream look if Core ever grows unloadable modules.
- Runtime verification of the new objective needs a second mod to fire a crossing; it is not covered
  by the compile-verify pass.

### Minecraft 26.3

- **Minecraft 26.3** as a new Stonecutter node on every loader — NeoForge `26.3.0.7-beta`,
  Forge `26.3-66.0.2` and Fabric (fabric-api `0.161.0+26.3`, NeoForm `26.3-1`) — built alongside
  26.1.2 and 26.2, so every release now ships **nine** loader × version jars.
- VS Code run/debug configurations (`.vscode/launch.json`, `.vscode/tasks.json`) gain the three
  26.3 cells; the "Build all" task now builds all nine.
- CI (`multiloader.yml`, `publish.yml`) builds, attaches and publishes the 26.3 jars.
- Requires **Neroland Core 1.13.0** (was `1.9.0`) — the first Core release with a 26.3
  build. The loader range still derives from the pin (`[${nerolandcore_version},2.0)`).
- JEI pins moved to the newest published builds on each Minecraft version: `29.40.0.101` (26.1.2), `30.35.0.223` (26.2) and `31.3.0.18` (26.3). Compile-time API only — JEI remains a soft dependency and the shipped jar gains no hard requirement.

### 26.3 port notes

- 26.3 API differences are handled with Stonecutter blocks: `PoseStack#rotate` (was `mulPose`), the new `Prediction` argument on `drop` / `placeItemBackInInventory`, `setPermanentlyInvulnerable`, and similar renames.
- Build: the shared `common/` Java source is now preprocessed by Stonecutter for every non-active node (`stonecutterProcessCommon`), so common code can carry `//? if >=26.3 {` blocks, and `common/src/main/resources-<mc>` overlay folders are merged over the shared resources for matching nodes (`mergeCommonResources`). The active node still compiles the raw `common/` folder.
- Build plugins aligned with Neroland Core: ModDevGradle `2.0.147` (the older 2.0.141 cannot set up NeoForge 26.3), ForgeGradle `7.0.40`, Stonecutter `0.9.8`.

## [0.1.0-beta.1] - 2026-08-02

Tag `v0.1.0-beta.1`. The first release with gameplay in it: a native quest engine, a datapack
quest format, per-player progress storage, six objective types, five reward types, client sync,
the Quest Book item and its screen, the `/neroquests` admin tree, the NeroLink module and a
24-quest story spine across three chapters. Everything below is compile-verified across all six
cells (`:{fabric,neoforge,forge}:{26.1.2,26.2}:build`); runtime verification is the remaining
stage.

### Added

**Foundation — Core, config, telemetry and the platform seams**

- NeroQuests builds and runs against **Neroland Core 1.9.0**, its only hard dependency and the
  only mod it depends on at all. Every loader manifest declares it required — Forge and NeoForge
  with `ordering = "AFTER"` so Core loads first, Fabric with `>=1.9.0` — and the version floor is
  the **compiled** Core version, so a Core too old to have the APIs this jar was built against is
  refused by the loader instead of failing later with a `NoSuchMethodError`.
- `platform/Services`, `PlatformInfo` and `NetworkPlatform` — NeroQuests' own seams, one
  implementation per loader behind `META-INF/services`. `NetworkPlatform` is deliberately **not**
  Core's: Core drains its payload lists during its own bootstrap (on Forge the channel is
  `build()`-sealed inside Core's constructor), so a downstream registration on Core's channel
  would be silently dropped.
- `config/neroquests.properties`, built on Core's config framework and hot-reloadable with
  `/neroland config reload`. Four keys: `gateWritesEnabled` (whether reward payouts write Core
  progression gates at all), `missingModObjectivePolicy` (`skip` or `autocomplete` for objectives
  naming content from an absent mod), `questDataRetentionDays` and `telemetryEnabled`. The
  gameplay keys are server-authoritative; **`telemetryEnabled` deliberately is not** — crash
  reporting opt-out is a per-client choice a server must never force.
- Opt-out, NeroQuests-only Sentry crash reporting (EU ingest): `sendDefaultPii=false`, no host or
  server name, no user identity, no player names or UUIDs, no world or quest-progress data,
  OS-account names scrubbed from file paths, an event dropped unless its stack trace touches
  `za.co.neroland.neroquests`, per-session de-duplication, a hard cap of 10 events per session
  and at most 300 mod ids attached as context. Development runs report too, tagged
  `environment=development` so they stay out of release metrics.

**The quest data model — datapack-driven**

- `Quest` is an immutable record with a codec, loaded one JSON per quest from
  `data/<namespace>/neroquests/quests/**.json`; the id is the file's namespace plus its path
  without the extension, so a pack adds, overrides or removes a quest simply by shipping the same
  id. Fields: `title`, `description`, `icon`, `prerequisites`, `objectives`, `rewards`, `scope`
  and an optional `visible_gate` that keeps a quest hidden until a Core gate opens.
- `Chapter` definitions load the same way from `.../neroquests/chapters/**.json`, each carrying a
  title key, an icon and its quests with **abstract grid coordinates** (`x` / `y`), not pixels —
  the book turns them into a layout, so a pack author never has to think in screen space.
- `QuestScope` is `player` or `server`: a player quest is tracked and completed independently by
  everyone; a server quest is one shared instance the first finisher closes for the whole world.
  It mirrors Core's `GateScope` so scope reads the same way in both mods' JSON.
- `ObjectiveTypes` and `RewardTypes` are plain `ConcurrentHashMap` registries rather than
  Minecraft registries — the same pattern Core uses for `NeroLinkRegistry` — because type ids are
  pure code contracts that must resolve before any datapack load, on every loader, with no
  registry-freeze timing games. An add-on registers its own types the same way.
- **Bad content is never fatal.** An unregistered objective or reward type decodes to an inert
  `UnknownObjective` / `UnknownReward` rather than throwing, so the loader can drop exactly the
  offending quest with a precise warning and leave the rest of the pack loaded. Malformed JSON,
  dangling prerequisites, prerequisite cycles, duplicate ids and quests with no objectives are all
  logged against their resource id and dropped, and are collected as `ValidationIssue`s so
  `/neroquests reload-check` can show an operator what a pack got wrong without making them read
  the server log.
- `QuestDefinitions` reads lazily from the running server's `ResourceManager` and caches, following
  Core's `GateDefinitions` — and goes one step further by **surviving `/reload` in pure common
  code**: the cache is keyed on the `ResourceManager` _instance_, which `reloadResources` replaces
  wholesale, so a reload is detected by an identity comparison with no per-loader reload-listener
  API to register three different ways. A `generation()` counter lets anything derived from the
  definitions know when to rebuild. Re-reading never touches stored progress.

**Progress storage and privacy**

- `QuestProgressState` — one `SavedData` store (`neroquests:quest_progress`) on the overworld, in
  two sections mirroring `QuestScope`: player rows (`UUID → quest id → QuestProgress`, plus one
  last-updated stamp per player) and server rows (`quest id → QuestProgress`), the latter holding
  no UUIDs at all.
- **Every accessor goes through `data/SavedDataRecovery`.** Vanilla's `computeIfAbsent` reads the
  `.dat` on first access and lets a corrupt or truncated file propagate unchecked; since progress
  is fetched from tick and command paths, one bad file would crash the server repeatedly. The
  guard substitutes a fresh empty store, installs it in the storage cache so the bad file is not
  re-read, and marks it dirty so a clean file is written at the next save — degraded but playable
  instead of unloadable. This is the minimal port of Nerospace's version; NeroQuests keeps no
  side-car backups, so a recovery starts empty. The failure is logged with the saved-data name and
  dimension key only and reported as a handled event through the scrubbed, opt-out telemetry pipe.
- Player rows hold **only** quest ids, integer objective counters and epoch-millis timestamps,
  keyed by the player's existing Minecraft game UUID. No names, IPs, chat or coordinates.

**Objectives — six types**

- `neroquests:collect_item` and `neroquests:craft_item` take an item id **or an item tag** plus a
  count; `neroquests:kill_entity` takes an entity id or tag; `neroquests:reach_dimension` takes a
  dimension id; `neroquests:gate_open` waits on a Neroland Core progression gate; and
  `neroquests:quest_complete` chains one quest onto another. That covers gather, make, fight,
  travel, ecosystem progression and chaining, which is the complete set the engine needs.
- `neroquests:gate_open` is what makes a quest react to **another mod with zero coupling**:
  Nerospace opening `nerolandcore:reached_orbit` completes an objective here, and neither mod
  imports the other — both depend only on Core.
- An objective naming content from an absent mod (unknown id, empty tag) is handled by
  `missingModObjectivePolicy` and warns **once** per definition generation through
  `MissingContent`, rather than once per evaluation.

**Rewards — five types**

- `neroquests:item`, `neroquests:xp`, `neroquests:gate`, `neroquests:currency` and
  `neroquests:reputation`. Rewards fire automatically when a quest completes, in the order the
  file lists them, each inside its own try/catch — the completion is already written by the time
  payout runs, so a reward that throws can never take the completion or the other rewards with it.
  Failures are logged by quest id and reward type only.
- **The two economy payouts degrade rather than lie.** Core defines the contracts and a sibling
  mod supplies the store, so `currency` checks `CurrencyApi.hasRealProvider()` and `reputation`
  checks `ReputationApi.hasRealProvider()`; with only Core's volatile in-memory fallback present
  they log once at debug and grant nothing. The quest still completes and its other rewards still
  pay out. `currency` defaults to `nerolandcore:credits`; `reputation`'s amount is a signed delta,
  because a quest that sides with one faction may legitimately cost you standing with its rival.
- Currency and reputation are UUID-keyed and land whether or not the recipient is online; items,
  experience, the completion chat line and player-scope gate writes need a live player and are
  skipped with a debug line when there is none — reachable only for a `scope: server` quest whose
  triggering player logged off between trigger and payout. `gate` writes obey `gateWritesEnabled`.

**The engine and its triggers**

- `QuestTriggers` is the one loader-agnostic front door: every "something happened" funnels through
  a static method there, so `QuestEngine` never sees a loader type.
- **Progression gates need no per-loader wiring at all** — `CoreEvents.onProgression` is plain
  server-side Java, so one subscription in common init covers all three loaders.
- **Crafting is one shared vanilla mixin**, `ItemStackMixin` on `ItemStack#onCraftedBy`, rather
  than three loader subscriptions. That method is vanilla's single choke point for "this player
  took this many of this item out of a result slot" — crafting grid, furnace output, stonecutter,
  smithing table and villager trade all route through it with the exact count — so one hook is less
  code, identical on every loader, impossible to double-count, and it covers Fabric, which ships no
  crafting event at all. The loader modules deliberately register no craft event.
- **Kills, server tick and player join are per-loader**, because no vanilla seam credits a kill or
  ticks the server without one; each loader entry point registers its own (NeoForge/Forge
  `LivingDeathEvent` / `ServerTickEvent.Post` / `PlayerEvent.PlayerLoggedInEvent`, Fabric
  `ServerLivingEntityEvents.AFTER_DEATH` / `ServerTickEvents.END_SERVER_TICK` /
  `ServerPlayConnectionEvents.JOIN`). A kill credits the direct attacker if that is a player, and
  otherwise whoever the game credits — which covers arrows, pets and other indirect kills.
- **Dimensions and inventories are measured, not evented.** A 1 Hz sweep (20 ticks) re-measures
  every online player and does nothing at all when no quests are loaded. Measuring is idempotent,
  so the interval is a latency knob and not a correctness one — and it keeps behaviour identical on
  all three loaders, which a per-loader "changed world" event would not.

**Client sync**

- `QuestNetwork` on NeroQuests' own channel: a declare-once payload registry (type + stream codec +
  client handler) that each loader iterates and wires to its own API — NeoForge's
  `PayloadRegistrar`, Forge's `ChannelBuilder`, Fabric's `PayloadTypeRegistry` +
  `ClientPlayNetworking`. Two payloads, `QuestDefinitionsPayload` and `QuestProgressPayload`.
- **Server to client only.** The server is authoritative for every quest decision and the client
  renders what it is told, so there is nothing for a client to send; the serverbound half of the
  seam exists and follows the same declare-once pattern for later interactions.
- `QuestSync` is the single push point, fired at three moments: on join, after a datapack reload
  (both halves may have changed meaning), and at the end of an evaluation pass that actually
  mutated something. The definition snapshot is expensive and identical for everyone, so it is
  cached against `QuestDefinitions.generation()`; progress snapshots are per-player and built
  fresh.
- **A progress payload is only ever addressed to the player whose rows it contains.** A
  `scope: server` change fans out one individually built snapshot per player, each carrying that
  player's own rows plus the identifier-free shared section — never another player's progress.
- `ClientQuestDefinitions` and `ClientQuestProgress` are immutable client mirrors holding no
  client-only imports, so the payload classes are safe to load on a dedicated server, where the
  handlers are registered as types but never invoked.

**The Quest Book**

- `neroquests:quest_book`, a single-stack item that opens the book screen when used. It joins
  **Core's shared creative tab** (NeroQuests has no tab of its own) and Core's
  `neroland:highlight/tools` item tag, and ships its own item texture.
- A key binding as well — **G** by default (`key.neroquests.open_quest_book`), in a NeroQuests
  category registered once from the binding class's static initialiser, which is what guarantees
  exactly one `KeyMapping.Category.register` call per JVM on every loader.
- The screen is chapter tabs down the left, a pannable quest graph on the right and a detail page
  for whichever quest you click, with locked / available / complete states read from the mirrors.
- **It is a pure view.** Every value comes from the two client mirrors the server already pushed
  plus Core's `ClientGates`; opening the book sends nothing, completes nothing and grants nothing,
  and clicking a node only changes what the screen draws.
- **It needs no GUI artwork.** Everything is drawn with `GuiGraphicsExtractor` primitives —
  rectangles, text and item icons — so no sheet exists to break against a resource pack.

**Commands and the privacy surface**

- One `/neroquests` tree, built in shared code and registered identically on all three loaders:
  `grant`, `revoke`, `reset`, `list`, `reload-check` and `export`. Everything is
  server-authoritative and gated at permission level 2 (`Commands.LEVEL_GAMEMASTERS`), the same
  level as `/neroland`.
- **`export` is the data-access path**, returning one player's own rows as JSON. Output goes to the
  invoker alone.
- **`<player>` is an online player's name or a raw UUID, never a profile-cache lookup** — the same
  shape Core's `/neroland data erase <uuid>` takes. The privacy commands have to reach offline
  players, and turning an offline name into a UUID means driving a name/UUID correlation lookup
  from user input. Quest ids are plain strings with live suggestions rather than a registry
  argument type (a quest id is a datapack id, not a registry entry), and an id with no namespace is
  read as `neroquests:`, mirroring Core's `parseGateId`.
- `reload-check` reports what survived the last definition load and lists every dropped entry with
  its reason.

**The NeroLink module**

- `link/QuestLinkModule` registers three surfaces with Core's link registry, **last** in common init
  so its completion listener runs behind reward execution — a companion client is never told about
  a completion before its payout was attempted. Pure server-side Java against Core's API: no loader
  wiring, no networking, no HTTP. With no bridge mod installed it costs one registry entry and a
  listener.
- Read: the `quests` and `chapters` sections, each scoped to the requesting UUID's own progress
  plus identifier-free shared world progress. Quests behind an unopened `visible_gate` are filtered
  out server-side, exactly as they are in game.
- Write: one action, `claim_reward`, which in this version **pays out nothing and says so** —
  rewards already fire automatically on completion, and claiming again must never double-pay. It
  exists so a companion client has a stable id to code against from day one; a "claim" button
  written today keeps working unchanged when the deferred-reward queue lands. Validation is
  server-authoritative and a hidden quest answers exactly like a quest that does not exist, so its
  existence cannot be probed.
- Live: two topics, `quest_completed` and `progress`, on Core's shared event bus.
- Snapshot **schema version 1**, bumped on any change to a section's shape so a client can tell what
  it is parsing.

**Story content — 24 quests in 3 chapters**

- **Groundwork (10)** — `first_tools`, `first_shelter`, `coal_and_torches`, `pest_control`,
  `iron_age`, `ironclad`, `diamond_standard`, `redstone_awakening`, `moving_parts`,
  `breaking_ground`.
- **Industrial (7)** — `forge_ahead`, `bulk_smelting`, `pressed_and_powdered`, `machine_shop`,
  `alloy_ambitions`, `power_up`, `age_of_industry`.
- **Space Race (7)** — `eyes_on_the_sky`, `launch_materials`, `reach_for_orbit`, `green_horizon`,
  `first_colony`, `deep_space`, `united_frontier`.
- Every objective and icon uses **vanilla ids or vanilla tags**, so no quest can dangle on a mod
  that is not installed; the ecosystem is reached through `gate_open` objectives on Core gates
  instead. Reward payouts are xp, vanilla items and credits, so the spine plays through with Core
  alone and gains its economy payouts the moment NeroEconomy registers a provider.
- All titles and descriptions are translation keys, shipped in `en_us.json`.

**Documentation**

- `PRIVACY.md` and a full `wiki/`: `Home`, `Quest-Format`, `Objectives`, `Rewards`,
  `Story-Chapters`, `Quest-Book`, `Sync`, `Commands`, `Data-Storage`, `Link-Module` and
  `Telemetry`.

### Privacy

- The per-player data-erasure hook is registered **early** in common init, ahead of the store it
  purges: `QuestData` hands Core's `PlayerDataErasure` a callback that drops every quest row and the
  retention stamp held for that UUID, so one POPIA/GDPR request purges a player across every Nero
  mod at once. Shared `scope: server` progress is untouched — it belongs to the world and holds no
  identifiers.
- Retention: with `questDataRetentionDays` above zero, rows whose last-updated stamp is older are
  pruned on first access per server session. **Only counts are logged, never identity** — the same
  rule erasure follows.
- Nothing in the link module, the command output, the sync payloads or the crash reports carries
  another player's UUID, name or position.

### Notes

- **Runtime verification is still outstanding.** The release is compile-verified on all six cells;
  it has not yet been played through end to end.
- **`neroquests:custom_event` does not exist yet.** Core's `ThresholdEvents` bus is the designed
  coupling point for it — NeroColonies already publishes on `nerocolonies:food_stock`,
  `nerocolonies:oxygen`, `nerocolonies:morale` and `nerocolonies:structures` — but with no objective
  type to feed, `CoreEvents.onThreshold` is left unsubscribed rather than registered as a no-op
  listener. See the TODO on `QuestTriggers.init()`.
- **There is no deferred-reward queue.** Rewards are granted at the moment of completion; a
  player-bound reward with no live player to give it to is skipped rather than parked. The
  `claim_reward` link action is the seam that will hand parked rewards over when the queue lands.
- `QuestTriggers.dimensionEntered` is part of the facade but nothing calls it today —
  `reach_dimension` is measured by the sweep, which registers arrival within a second on every
  loader with no per-loader event to keep in step. It stays because it is the correct hook for a
  teleport command or a rocket landing that wants the change reflected immediately.
- The Fabric access widener ships empty: nothing in NeroQuests needs widened access yet.
- NeroQuests registers **no blocks and no machines** — one item and one screen. The give-only
  `sentry_test` end-to-end telemetry check other Nero mods carry therefore has nowhere to live and
  arrives with the first content registry.

## [0.0.1-alpha.1] - 2026-08-02

Tag `v0.0.1-alpha.1`, an interim tag cut part-way through the same day's work: the multiloader
skeleton (the Stonecutter build across `{fabric,neoforge,forge}` x `{26.1.2,26.2}`, CI, the Core
dependency through GitHub Packages, the publishing and wiki workflows, the store listings) plus the
first half of 0.1.0 — the config schema, the telemetry pipeline, the platform seams, the quest and
chapter data model and the progress store. It was never published; its contents are folded into the
0.1.0-beta.1 entry above, which is the first release with a playable engine on top of them.
