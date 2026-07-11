# NeroQuests

**One guided path through the whole ecosystem — story missions, daily contracts, faction quests and space-race milestones that turn a pile of mods into a designed journey.**

NeroQuests is the **progression & questing** layer of the Neroland ecosystem — the connective tissue that makes Neroland an ecosystem rather than a bundle. A data-driven quest engine reads datapack JSON and tracks progress per player, walking a player through the Earth → industrialise → space → colonies arc that spans every mod in the family. It is the thread that tells a new player what to do first and how the mods relate.

Built on **Neroland Core**: NeroQuests is the **primary writer** to Core's **progression-gate API** (other mods read gate flags to unlock recipes, dimensions and features in step), pays rewards through Core's currency/reputation API, references cross-mod content through Core's `c:` tags, and routes quest progress through Core's shared data-erasure hook. *(Planned — in design; not yet released.)*

---

## What you build a journey from

1. **Quest book.** A client screen — opened by item and/or keybind — renders the full quest tree as navigable chapters with a dependency graph. Each node shows locked / available / in-progress / completed state, icon, title and a tooltip of objectives and rewards. Layout is authored in datapack JSON; the book is purely a view over server-authoritative progress and never grants completion itself.
2. **Story missions.** The narrative backbone: curated, dependency-chained quests that walk you through the Earth → industrialise → space → colonies arc, each completion typically writing one or more Core progression-gate flags so the rest of the ecosystem unlocks in step.
3. **Daily contracts.** Repeatable, time-refreshed objectives (collect, craft, kill, deliver) picked by a server-side scheduler from datapack-defined pools — a steady reason to log in and a reliable **NeroEconomy** income stream.
4. **Faction quests.** Quests scoped to a **NeroFactions** faction and completed cooperatively, with progress tracked at the team level and completion paying faction reputation and treasury.
5. **Exploration objectives.** Reach dimensions, structures, biomes or coordinates and interact with lore points — the natural home for **NeroRuins** content, often unlocking lore entries and deeper ruin tiers.
6. **Boss progression.** A gated ladder of **NeroCreatures** encounters where defeating a boss completes a quest and writes a progression-gate flag (e.g. `boss.tier2.cleared`) that later quests and other mods react to.
7. **Space-race milestones.** Launch readiness, first orbit, first landing, first colony — built on **Nerospace** events and deliberately shared-visible, so a server can celebrate firsts. Each milestone sets a high-level gate flag consumed across the ecosystem.
8. **Server events.** Time-boxed, server-wide quests started by **NeroEvents** with shared objectives, broadcasts and optional leaderboards visible to every online player.

## Designed to tie it all together

- 🧵 **Data-driven first** — quests, chapters, objectives, triggers and rewards are datapack JSON, so pack authors and the official modpack edit content without recompiling; `/reload` re-reads everything.
- 🔓 **Drives Core's progression-gate** — completing quests is the canonical way content unlocks across mods; the gate stays the single source of truth, and gate-writing quests can be toggled off so admins can run NeroQuests as pure content.
- 💰 **Economy rewards** — quests, contracts, factions and milestones pay out **NeroEconomy** currency and Core reputation, with magnitudes config- and datapack-tunable.
- 🧩 **Graceful degradation** — cross-mod objectives skip or auto-complete per config when a referenced mod is absent, so a missing sibling never soft-locks the tree.
- 📦 **Modpack spine** — the official modpack ships a curated quest tree as its onboarding and progression spine, so a new player always knows the next step.

## Privacy (POPIA / GDPR)

NeroQuests stores per-player quest progress — and keeps it minimal. It holds **only quest IDs, objective counters and completion timestamps**, keyed by the player's existing game **UUID** (never names, IPs, chat or any personal information beyond what Minecraft already keeps). Progress prunes on a **configurable retention** window for inactive players and clears on player-data deletion, all routed through Core's shared data-erasure hook — so a single erase request clears your NeroQuests data alongside every other Neroland mod. A per-player **opt-out** covers any non-essential tracking (aggregate counters, leaderboards) without breaking gameplay. No telemetry leaves the server by default; any optional crash reporting is opt-out and carries only **version strings** — never names, UUIDs, IPs or world data.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one progression arc, one currency/reputation API, one config framework, shared `c:` tags. NeroQuests ships in its own creative tab.
- 🔌 **Interoperates, never hard-depends** — **NeroFactions**, **NeroEconomy**, **NeroEvents**, **NeroRuins**, **NeroCreatures** and **Nerospace** are all optional soft synergies, discovered through Core APIs and tags. External mods (Create, AE2, Mekanism, Ad Astra, Energized Power) are referenced only via Core's common compat tags, so quests still load when a mod is absent and its tag is simply empty.
- 🧵 **The connective tissue** — as the primary writer of Core's gate flags, NeroQuests authors the intended unlock sequence for the whole family; individual mods depend on Core's gate, not on NeroQuests, but their play order lives here as content.
- 🧱 **Cross-loader** — NeoForge, Forge and Fabric on Minecraft **26.1.2** and **26.2**.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore)** — install it alongside NeroQuests (it loads first).
- Optional soft synergies enrich the tree: **NeroFactions**, **NeroEconomy**, **NeroEvents**, **NeroRuins**, **NeroCreatures** and **Nerospace**. Each is discovered through Core and degrades gracefully when absent.
- Conventional `c:` tags let quest objectives reference Create, AE2, Mekanism, Ad Astra and Energized Power content as the 26.x ecosystem fills in — no hard dependency on any of them.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroQuests by Neroland* with links to this page and the [GitHub repository](https://github.com/Neroland/neroquests). Full terms: [LICENSE](https://github.com/Neroland/neroquests/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/neroquests/wiki)** — every chapter, objective and system documented.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/neroquests/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/neroquests/blob/main/CHANGELOG.md)**
- 🟢 **[Also on Modrinth](https://modrinth.com/mod/neroquests)**

---

*Created by Neroland. The project logo was made with the help of AI image tools; in-game art is generated by the project's own tooling and refined by hand.*
