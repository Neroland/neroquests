# NeroQuests

> Part of the Neroland sci-fi Minecraft mod ecosystem, built on **Neroland Core**.

**Status:** beta — version `0.4.0-beta.1`.

A native, datapack-driven quest engine for the Neroland ecosystem:

- **Quests and chapters as datapack JSON**, read by an in-game **quest book** with a chapter graph.
- **A built-in story line** — three chapters, 28 quests (Groundwork, Industrial Age with a market row,
  The Space Race) — that a pack can extend, rebalance or replace.
- **Quest packs from other mods:** a mod ships its own quests as data files in its jar — no dependency
  either way — and they appear in the book only while that mod is installed. See
  [Shipping a quest pack](wiki/Shipping-a-Quest-Pack.md).
- **Ten objective types:** `collect_item` (optionally a hand-in), `craft_item`, `kill_entity`,
  `reach_dimension`, `visit_structure`, `quest_complete`, `gate_open`, `custom_event`, `economy` and
  `manual`.
- **Six reward types:** `item`, `xp`, `currency`, `mod_hook`, `reputation` and `gate`, each paid at most
  once per player, with undeliverable rewards kept until the player can take them.
- **Daily contracts** paid from the server economy's budget, and optional **NeroEconomy** payouts.
- `/neroquests` operator commands, per-player and world-wide progress, and a NeroLink module for the
  companion app.

## Build targets

- **Minecraft:** 26.1.2, 26.2 and 26.3
- **Loaders:** NeoForge, MinecraftForge/Forge, Fabric (the "9 cells")
- **Java:** 25
- **Requires:** Neroland Core 1.15.0 or later (below 2.0.0)
- Mod id: `neroquests` · package `za.co.neroland.neroquests`

## Layout

The build is the repo root, with a flattened cross-loader structure driven by Stonecutter:

- `common/` — shared, loader-agnostic source spliced into every loader node
- `fabric/` — Fabric Loom
- `forge/` — ForgeGradle
- `neoforge/` — ModDevGradle
- `stonecutter.gradle` — the real root build script; `build.gradle` is intentionally inert

## Building

```sh
./gradlew :fabric:26.2:build          # one cell
./gradlew :neoforge:26.1.2:build :neoforge:26.2:build :neoforge:26.3:build \
          :forge:26.1.2:build :forge:26.2:build :forge:26.3:build \
          :fabric:26.1.2:build :fabric:26.2:build :fabric:26.3:build   # all nine
```

See [`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md) for agent and contributor context.

## Privacy

NeroQuests keeps quest progress minimal and erasable through Neroland Core's shared erasure hook.
It also ships anonymous, NeroQuests-only crash reporting via Sentry (EU servers) that is
**on by default** and **opt-out**: set `telemetryEnabled=false` in `config/neroquests.properties`
to switch it off. Reports never contain IPs, usernames, UUIDs, quest progress or world data. Full
disclosure: [`PRIVACY.md`](PRIVACY.md).

## Docs

- [`wiki/Home.md`](wiki/Home.md) — player- and contributor-facing documentation
- [`PRIVACY.md`](PRIVACY.md) — data protection (POPIA / GDPR), including crash telemetry

Ecosystem-wide design, feature and dependency docs live in the Neroland planning
repository, outside this mod.
