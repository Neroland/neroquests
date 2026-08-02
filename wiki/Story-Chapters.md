# Story chapters

NeroQuests ships a built-in quest line: three chapters that walk a world from its first stone
pickaxe to a settled frontier. It is the **onboarding spine** of the Neroland ecosystem — playable
start to finish with no other mod installed, and quietly wired to unlock the rest of the ecosystem
when those mods are present.

Open it with the [quest book](Quest-Book.md). Chapters appear as tabs, in story order.

| Chapter | Tab icon | Quests | What it covers |
| --- | --- | --- | --- |
| **Groundwork** | Crafting table | 10 | Vanilla survival: tools, light, iron, redstone, the first automation, diamonds, obsidian |
| **Industrial Age** | Blast furnace | 7 | Bulk smelting, processed materials, a machine shop, the parts list of a power plant |
| **The Space Race** | End crystal | 7 | Orbit, other worlds, the first colony, deep space, and one world-wide finale |

Every quest is an ordinary datapack file — see [Quest format](Quest-Format.md). Nothing here is
hard-coded, so a pack can extend, rebalance, or replace the whole line by shipping quests with the
same ids.

## Chapter 1 — Groundwork

The part every world has already lived through, written down. A mostly linear spine (wood → light →
iron → redstone → moving parts → obsidian) with two short side branches for shelter and for clearing
out the night shift. Rewards are modest: a little experience, a few useful items, small currency
amounts.

No mod other than Neroland Core is required, and every objective names vanilla items, vanilla item
tags, or vanilla mobs.

## Chapter 2 — Industrial Age

Visible from the moment you open the book, but locked behind the Groundwork finale. This is where a
world stops crafting one item at a time: a blast furnace, stacks of metal, hoppers feeding machines,
a workshop that keeps working while you sleep.

Two of its quests reach for **processed materials** — plates and dusts — through the shared `c:`
tags every tech mod publishes. Install Create, Mekanism, Energized Power, or a Neroland tech mod and
those steps fill in naturally; install none of them and the steps politely stand down (see
[Objectives](Objectives.md) on missing content). One optional side quest asks for **Nero Alloy**, the
ecosystem's working metal — a detour for worlds that can already make it, never a roadblock.

The finale, *Age of Industry*, is the chapter's whole point: finishing it opens Neroland Core's
**Industrial Power** progression gate, and every Neroland mod on the server starts treating you as an
engineer.

## Chapter 3 — The Space Race

The chapter that belongs to the ecosystem rather than to NeroQuests. Its milestones are Core's
progression gates — **Reached Orbit**, **First Colony**, **Deep Space** — in the same order Core
defines them, so whichever mod actually flies you (Nerospace, in the Neroland lineup) drives these
quests forward without NeroQuests depending on it at all.

Two quests also ask you to set foot on another world. On a server with no such worlds installed,
those steps degrade instead of blocking, and the chapter reads as a story rather than a checklist.

Its finale, **A United Frontier**, is the one `scope: server` quest in the whole line: a world goal,
completed once, for everybody. See [Quest format](Quest-Format.md) for what server scope means, and
[Rewards](Rewards.md) for how its gate reward is written.

## Turning the built-in line off

The shipped chapters are a datapack like any other, so a pack can:

- **Replace a quest** — ship a file with the same id; yours wins.
- **Remove a quest** — override it and its chapter entry, or drop the chapter that lists it.
- **Leave progression alone** — set the server config key `gateWritesEnabled` to `false` and the
  finale quests stop touching Core's gates entirely, while still completing and paying out.

## See also

- [Quest book](Quest-Book.md)
- [Quest format](Quest-Format.md)
- [Objectives](Objectives.md)
- [Rewards](Rewards.md)
- [Home](Home.md)
