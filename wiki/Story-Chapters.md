# Story chapters

NeroQuests ships a built-in quest line: three chapters that walk a world from its first stone
pickaxe to a settled frontier. It is the **onboarding spine** of the Neroland ecosystem: Groundwork
and Industrial Age are playable with only Neroland Core installed, and the Space Race follows the
other Neroland mods — its quests stay hidden until a mod that can drive them has opened the right
milestone, so a pack never shows a quest it cannot finish.

Open it with the [quest book](Quest-Book.md). Chapters appear as tabs, in story order.

| Chapter | Tab icon | Quests | What it covers |
| --- | --- | --- | --- |
| **Groundwork** | Crafting table | 11 | What the book is, then vanilla survival: tools, light, iron, redstone, the first automation, diamonds, obsidian |
| **Industrial Age** | Blast furnace | 7 (+3 market) | Bulk smelting, processed materials, a machine shop, the parts list of a power plant |
| **The Space Race** | End crystal | 7 | Orbit, other worlds, the first colony, deep space, and one world-wide finale |

Every quest is an ordinary datapack file — see [Quest format](Quest-Format.md). Nothing here is
hard-coded, so a pack can extend, rebalance, or replace the whole line by shipping quests with the
same ids.

## Chapter 1 — Groundwork

It opens with **What This Book Is**: hold a crafting table and the book explains itself — view mode
for browsing and claiming, edit mode for players with editor access who author chapters of their own,
and chapters from other Neroland mods that appear as you install them. It is not a prerequisite of
anything else.

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
[Objectives](Objectives.md) on missing content). One optional side quest, *Alloy Ambitions*, asks for
**Nero Alloy**, the ecosystem's working metal. Neroland Core defines the metal but only a mod that
grinds meteor material makes its dust, so the quest stays hidden until you have reached orbit — a
detour for worlds that can already make it, never a roadblock.

The finale, *Age of Industry*, is the chapter's whole point: finishing it opens Neroland Core's
**Industrial Power** progression gate, and every Neroland mod on the server starts treating you as an
engineer.

Two quests here are **hand-ins**: *Pressed and Powdered* and *Alloy Ambitions* take the plates,
dust, redstone block and alloy ingots when they complete (shown as "Hand in" in the book), so one
stack cannot complete them on every account.

**Market row (economy servers only).** Three extra quests sit on a row of their own below the
chapter — *Open for Business*, *Iron Trader* and *Seasoned Trader* — about selling, buying and
earning on the server economy. They are hidden behind the economy's first-sale milestone gate, so on
a server without an economy mod (NeroEconomy) they never appear.

## Chapter 3 — The Space Race

The chapter that belongs to the ecosystem rather than to NeroQuests. Its milestones are Core's
progression gates — **Industrial Power**, **Reached Orbit**, **First Colony**, **Deep Space** — in the
same order Core defines them, so whichever mod actually flies you (Nerospace, in the Neroland lineup)
drives these quests forward without NeroQuests depending on it at all.

**What a pack without those mods sees.** Every Space Race quest carries a `visible_gate`, so it only
appears once the milestone that makes it finishable is open:

| Quests | Appear when | Opened by |
| --- | --- | --- |
| *Eyes on the Sky*, *Launch Materials* | Industrial Power | *Age of Industry* (NeroQuests itself), or a Neroland tech or space mod |
| *Reach for Orbit*, *Green Horizon*, *First Colony* | Reached Orbit | A mod that flies you to orbit (Nerospace) |
| *Deep Space*, *A United Frontier* | First Colony | A mod that founds off-world colonies or stations |

With only Neroland Core installed the chapter therefore ends after *Launch Materials*; nothing after it
is shown as stuck. *Reach for Orbit* appears and completes on the same step — your first launch.

Two quests also ask you to set foot on another world. If the mod that opened the milestone does not
provide that world, the step degrades instead of blocking and the milestone alone finishes the quest.

Colony life — life support coming back up, a settlement climbing out of famine, a third building
going up — is recorded by NeroColonies' own chapter, which appears in the book when that mod is
installed (see [Shipping a quest pack](Shipping-a-Quest-Pack.md)). Worlds that completed those quests
before 0.4.0 keep them: the progress moves to NeroColonies' quest ids on the first load after updating.

Its finale, **A United Frontier**, is a `scope: server` quest too: a world goal, completed once, for
everybody. See [Quest format](Quest-Format.md) for what server scope means, and
[Rewards](Rewards.md) for how server-scope rewards reach players.

## Turning the built-in line off

The shipped chapters are a datapack like any other, so a pack can:

- **Replace a quest** — ship a file with the same id; yours wins.
- **Remove a quest** — override it and its chapter entry, or drop the chapter that lists it.
- **Leave progression alone** — set the server config key `gateWritesEnabled` to `false` and the
  quests stop touching Core's gates entirely, while still completing and paying out. Side effect:
  *Age of Industry* no longer opens **Industrial Power**, and the whole Space Race waits behind that
  gate. Unless another mod opens Industrial Power (a Neroland tech or space mod does), the Space Race
  never appears on that server.

## See also

- [Quest book](Quest-Book.md)
- [Quest format](Quest-Format.md)
- [Objectives](Objectives.md)
- [Rewards](Rewards.md)
- [Home](Home.md)
