# Changelog

All notable changes to **NeroQuests** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

- No Core pin bump: `ThresholdEvents` shipped in Core **1.7.0** and NeroQuests already pins **1.9.0**.
- Core exposes no `removeCrossingListener`. That is fine for a once-per-JVM subscription, but it does
  mean a listener cannot be withdrawn — worth an upstream look if Core ever grows unloadable modules.
- Runtime verification of the new objective needs a second mod to fire a crossing; it is not covered
  by the compile-verify pass.

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
