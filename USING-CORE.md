# Using Neroland Core

Every Neroland Core API NeroQuests consumes, what it is used for, and where.

**Core floor: `1.9.0`** (`nerolandcore_version` in `gradle.properties`). The manifest dependency
range floors at the compiled Core version on purpose, so the loader refuses a Core too old to have
the APIs this mod compiles against rather than failing later with a `NoSuchMethodError`.

Neroland Core is NeroQuests' **only** dependency — hard or soft. No other Nero mod and no
third-party mod is depended on at all: the ecosystem is reached entirely through Core's progression
gates, event bus, economy contracts and link registry, so NeroQuests runs standalone with nothing
but Core installed and gains capability as siblings appear.

## The table

| Core package / class | Used for | Where |
| --- | --- | --- |
| `config.ConfigSchema`, `config.ConfigValue`, `config.ConfigManager` | The whole config schema — `config/neroquests.properties`, hot-reloadable via `/neroland config reload`, with server-authoritative flags per key. `telemetryEnabled` is deliberately *not* server-authoritative | `common/src/main/java/za/co/neroland/neroquests/config/NeroQuestsConfig.java` |
| `registry.RegistrationProvider`, `RegistrationProvider.RegistryEntry` | Loader-agnostic registration of the quest book item. Core's seam takes the mod id as a parameter and ships one `ServiceLoader` impl per loader inside Core's own jars, so NeroQuests duplicates nothing. On Fabric it registers eagerly; on NeoForge/Forge it builds a `DeferredRegister` that each entry point attaches with `RegistrationProvider.attach(...)` | `common/.../registry/ModItems.java`; `forge/.../forge/NeroQuestsForge.java`; `neoforge/.../neoforge/NeroQuestsNeoForge.java` |
| `registry.CoreCreativeTab` | `CoreCreativeTab.add(QUEST_BOOK::get)` — the quest book joins Core's shared **Neroland** tab. NeroQuests has no tab of its own | `common/.../registry/ModItems.java` |
| `data.PlayerDataErasure` | Registering NeroQuests' eraser so one POPIA/GDPR request purges a player's quest rows and retention stamp across every Nero mod at once. Registered **early** in common init, ahead of the store it purges, and it never logs identity | `common/.../data/QuestData.java` |
| `progression.ProgressionGates` | Three uses. `isOpen(player, gate)` measures a `neroquests:gate_open` objective; `resolvedOpenGates(player)` builds the visibility set a quest's `visible_gate` is filtered against; `tryOpen(player, gate)` / `setServerGate(server, gate, true)` pay out a `neroquests:gate` reward. **`tryOpen`, never `open`** — a refusal (requirements unmet, or already open) is the gate graph's decision and is logged at debug, not forced. All writes obey `gateWritesEnabled` | `common/.../quest/objective/GateOpenObjective.java`, `common/.../quest/engine/QuestEngine.java`, `common/.../quest/reward/GateReward.java` |
| `progression.ProgressionState` | Rebuilding an **offline** player's gate set for the link module — their own player-scope gates plus the server-scope ones — because `resolvedOpenGates` needs a live `ServerPlayer`. Team-scope gates are treated as closed while offline, the conservative direction (a gate read as closed only ever hides a quest) | `common/.../link/QuestLinkAccess.java` |
| `progression.ClientGates` | The client-side gate mirror the quest book reads, so a quest hidden behind an unopened `visible_gate` is hidden in the book too, with no NeroQuests packet to carry gate state | `common/.../client/screen/QuestBookScreen.java` |
| `progression.GateEvents.GateChange`, `progression.GateScope` | The payload of the gate-change subscription: only openings matter (`gate_open` objectives are sticky, so a closing gate can never complete anything), and `GateScope.PLAYER` re-evaluates one player while a server-scope change re-evaluates everyone. `QuestScope` mirrors `GateScope` so scope reads the same way in both mods' JSON | `common/.../quest/engine/QuestTriggers.java` |
| `event.CoreEvents` | Both cross-mod objective triggers, in two lines. `onProgression(...)` feeds `gate_open`; `onThreshold(...)` feeds `custom_event`. Plain server-side Java, so a single subscription each in common init covers all three loaders, and another mod's milestone (Nerospace opening `nerolandcore:reached_orbit`) or another mod's world state (NeroColonies reporting life support restored) moves a quest with **zero dependency in either direction** | `common/.../quest/engine/QuestTriggers.java` |
| `event.ThresholdEvents`, `event.ThresholdEvents.ThresholdCrossing` | The payload of the threshold subscription and the whole match surface of the `neroquests:custom_event` objective: `channel`, `scope`, `value`, `threshold` and `rising`. **A crossing names a place or system and never a player** (Core's POPIA/GDPR contract), so there is nobody to credit directly — the trigger fans out over every online player and the objective's `audience` field decides whether the news lands on the quest's shared counter once (`world`, the default) or on every online player's own row (`everyone`). A channel's `<modid>:` namespace is also what `contentPresent` tests, so an absent publisher degrades under `missingModObjectivePolicy` instead of blocking the quest | `common/.../quest/objective/CustomEventObjective.java`, `common/.../quest/engine/QuestTriggers.java`, `common/.../quest/ObjectiveSpec.java` |
| `economy.CurrencyApi`, `economy.CoreCurrencies`, `economy.Currency` | The `neroquests:currency` reward. `CurrencyApi.hasRealProvider()` guards the payout, because Core's fallback provider is in-memory and its balances vanish on restart, so paying into it would be a lie; with no real provider the reward logs once at debug and grants nothing while the quest still completes. `CoreCurrencies.CREDITS` is the default when a reward names no currency. Balances are UUID-keyed, so the reward lands whether or not the recipient is online | `common/.../quest/reward/CurrencyReward.java` |
| `reputation.ReputationApi` | The `neroquests:reputation` reward, under exactly the same `hasRealProvider()` guard and the same UUID-keyed offline behaviour. The amount is a signed delta — this is the one reward type allowed to take something away | `common/.../quest/reward/ReputationReward.java` |
| `link.NeroLinkRegistry`, `link.LinkModuleInfo`, `link.LinkSnapshotProvider`, `link.LinkActionHandler`, `link.LinkActionResult`, `link.LinkEvent` | The whole companion-app surface at schema version 1: two read sections (`quests`, `chapters`), one action (`claim_reward`) and two live topics (`quest_completed`, `progress`). Registered **last** in common init so the completion listener runs behind reward execution. NeroQuests ships no server of its own — a separate bridge mod reads Core's registry | `common/.../link/QuestLinkModule.java`, `QuestLinkSnapshots.java`, `QuestLinkActions.java`, `QuestLinkEvents.java`, `QuestLinkAccess.java` |
| `platform.Services.PLATFORM`, `platform.IPlatformHelper` | One call, `isModLoaded(...)`, asked of a `custom_event` channel's namespace to decide whether its publisher is installed. This is the one place Core's platform seam is genuinely the right tool: NeroQuests' own `PlatformInfo` reports the *loaded mod list* for crash triage, whereas this is a hot-path yes/no per objective evaluation | `common/.../quest/objective/CustomEventObjective.java` |
| `neroland:highlight/tools` item tag | Not an API but a Core datapack contract: the quest book is tagged so Core's item-highlight feature paints its slot border like every other Nero tool | `common/src/main/resources/data/neroland/tags/item/highlight/tools.json` |

## Things NeroQuests deliberately does *not* take from Core

- **Not `platform.NetworkPlatform`.** Core's seam is public and stable, but Core drains its payload
  lists during its own bootstrap — on Forge the channel is `build()`-sealed inside Core's
  constructor — so a NeroQuests payload registered there would be silently dropped. NeroQuests
  therefore reproduces Core's `CoreNetwork` architecture on **its own channel**, with its own
  `platform/NetworkPlatform` + `PlatformInfo` + `Services` seams and one impl per loader behind
  `META-INF/services`.
- **No reload seam.** Core has none to copy (its own gate definitions document one as future work),
  and the three loaders each expose datapack reload differently. `QuestDefinitions` instead detects
  `/reload` in pure common code by watching the server's `ResourceManager` **instance**, which
  `MinecraftServer.reloadResources` replaces wholesale — one implementation, identical on every
  loader.
- **No machine, side-config, energy, gas or upgrade APIs.** NeroQuests registers one item and one
  screen; it has no blocks, no block entities and no menus, so none of Core's machine framework is
  reachable from here.
- **No `link.LinkAlert` / `link.LinkAlerts`.** A quest becoming available is not something to
  interrupt a player's phone for; the link module publishes events, not alerts.
- **No creative tab of its own.** The quest book joins Core's shared tab.
- **No HTTP or outbound networking**, apart from the opt-out Sentry crash reporter. Core ships no
  server and neither does this mod; a companion app is served by a separate bridge mod.
- **No saved-data helper.** Core has none, so `data/SavedDataRecovery` is NeroQuests' own minimal
  port of Nerospace's crash-proof guard, and every NeroQuests `SavedData` accessor goes through it.

## Lifecycle notes

- **Core's event buses are add-only.** Neither `GateEvents` nor `ThresholdEvents` offers a way to
  remove a listener, so the only correct lifecycle is to subscribe exactly once per JVM. Common init
  gives that for free: it runs during mod construction, not per world, so nothing is leaked across an
  integrated-server world switch and there is nothing to unsubscribe on server stop. Per-*server*
  state is dropped rather than unsubscribed — `QuestTriggers.serverTick` resets it the moment a
  different `MinecraftServer` instance appears, and both handlers are inert while there is no current
  server.
- **Nothing is deferred any more.** Every Core API NeroQuests intends to use is in the table above;
  `event.ThresholdEvents` was the last outstanding one and is consumed as of the `custom_event`
  objective type.

## See also

- [`wiki/Quest-Format.md`](wiki/Quest-Format.md) — the datapack schema
- [`wiki/Objectives.md`](wiki/Objectives.md) — `gate_open` and the rest in practice
- [`wiki/Rewards.md`](wiki/Rewards.md) — the currency, reputation and gate rewards in practice
- [`wiki/Link-Module.md`](wiki/Link-Module.md) — the link surfaces in detail
- [`wiki/Data-Storage.md`](wiki/Data-Storage.md) — the erasure hook and retention in practice
- [`PRIVACY.md`](PRIVACY.md)
