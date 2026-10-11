# NeroQuests Wiki

Player- and contributor-facing documentation for **NeroQuests**, part of the Neroland
sci-fi Minecraft mod ecosystem. Built on **Neroland Core**.

> **Status:** beta (version `0.4.0-beta.1`). The quest engine,
> rewards, sync, the quest book and the built-in three-chapter story line are in; more is still
> landing, so pages will grow as features arrive. Keep this wiki updated alongside code changes
> (see [`../AGENTS.md`](../AGENTS.md) / [`../CLAUDE.md`](../CLAUDE.md)).

## Contents

- [Story chapters](Story-Chapters.md) — the built-in quest line: Groundwork, Industrial Age, The Space Race
- [Quest book](Quest-Book.md) — the in-game book: opening it, reading the graph, and the state legend
- [Commands](Commands.md) — the `/neroquests` operator tree: grant, revoke, reset, list, reload-check, export, manual complete, editor, custom
- [Quest editor](Quest-Editor.md) — making your own quests in game: what you can build and what to expect
- [Editor permissions](Editor-Permissions.md) — for server admins: who may author, locking, limits, escrow, export/import and privacy
- [Quest format](Quest-Format.md) — the contract: every field, objective and reward, sources, completion signals and validation rules
- [Shipping a quest pack](Shipping-a-Quest-Pack.md) — how a mod ships its own quests with data files only, and a copyable validator test
- [Objectives](Objectives.md) — every objective type, how progress is tracked, and missing-mod behaviour
- [Rewards](Rewards.md) — every reward type, how payouts run, and how they degrade without a sibling mod
- [Daily contracts](Daily-Contracts.md) — daily hand-in contracts paid from the economy's budget
- [Data storage](Data-Storage.md) — how quest progress is saved, kept, erased and exported
- [Server → client sync](Sync.md) — what the server sends your client, when, and why only your own progress
- [Link module](Link-Module.md) — the companion-app surface: sections, the claim action, events and privacy
- [Telemetry](Telemetry.md) — anonymous crash reporting: what is sent, and how to opt out

_Feature pages follow._ Add one page per block, item, machine, or system as it is built, and
link it here. Keep this page as the index.

## See also

- [Build & contributor context](../AGENTS.md)
- [Privacy & data protection](../PRIVACY.md)
