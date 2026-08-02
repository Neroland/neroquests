# NeroQuests Wiki

Player- and contributor-facing documentation for **NeroQuests**, part of the Neroland
sci-fi Minecraft mod ecosystem. Built on **Neroland Core**.

> **Status:** early — this mod is in development (version `0.0.1-alpha.1`). The quest engine,
> rewards, sync, the quest book and the built-in three-chapter story line are in; more is still
> landing, so pages will grow as features arrive. Keep this wiki updated alongside code changes
> (see [`../AGENTS.md`](../AGENTS.md) / [`../CLAUDE.md`](../CLAUDE.md)).

## Contents

- [Story chapters](Story-Chapters.md) — the built-in quest line: Groundwork, Industrial Age, The Space Race
- [Quest book](Quest-Book.md) — the in-game book: opening it, reading the graph, and the state legend
- [Commands](Commands.md) — the `/neroquests` operator tree: grant, revoke, reset, list, reload-check, export
- [Quest format](Quest-Format.md) — the datapack JSON for quests and quest-book chapters
- [Objectives](Objectives.md) — every objective type, how progress is tracked, and missing-mod behaviour
- [Rewards](Rewards.md) — every reward type, how payouts run, and how they degrade without a sibling mod
- [Data storage](Data-Storage.md) — how quest progress is saved, kept, erased and exported
- [Server → client sync](Sync.md) — what the server sends your client, when, and why only your own progress
- [Link module](Link-Module.md) — the companion-app surface: sections, the claim action, events and privacy
- [Telemetry](Telemetry.md) — anonymous crash reporting: what is sent, and how to opt out

_Feature pages follow._ Add one page per block, item, machine, or system as it is built, and
link it here. Keep this page as the index.

## See also

- [Build & contributor context](../AGENTS.md)
- [Privacy & data protection](../PRIVACY.md)
