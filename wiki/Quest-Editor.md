# Quest editor (players)

Make your own quests in the quest book: name a quest, pick what has to be done, choose a reward. Your
quests show up for everyone on the server in their own chapters, below NeroQuests' story and the
chapters your mods bring.

> The in-book editing screens are still being built. This page describes what the editor can do; the
> step-by-step screens will be documented here once they land.

## Who can edit

Your server decides. Usually Creative mode is enough; operators may also add you to the editor
allow-list so you can edit in Survival, or lock the editor while a quest set is finished. If you can
edit, the quest book shows an edit-mode switch. Details for admins: [Editor permissions](Editor-Permissions.md).

## What you can make

- **Chapters** — a title (up to 64 characters) and an icon. Up to 20 per world by default.
- **Quests** — a title, an optional description (up to 512 characters), an icon and a position on the
  chapter page. A quest can require other quests first: yours, NeroQuests' own, or a mod pack's.
- **Objectives** (1 to 12): collect or craft items, kill mobs, reach a dimension, have a progression gate
  open, complete another quest, a mod's threshold event (from the ones seen on this server), visit a
  structure, or a **manual** step that an editor ticks for a player ("build the tower"). Every item,
  mob, dimension and so on must exist on the server.
- **Rewards** (up to 9): item stacks, a loot table, experience and credits, within the server's limits.
  Progression gates and faction standing cannot be rewards of player-made quests.
- **Repeatable** quests (off by default): after a cooldown of at least a minute the quest re-arms for
  each player and pays again, at most once per cooldown.

NeroQuests' own quests and the quests your mods ship cannot be edited.

## Good to know

- Text is shown exactly as typed, minus colour/formatting codes and control characters, which are
  removed. It is never treated as a command, a link or a translation key.
- The book shows who made a quest only as **you** or **another player** — never a name.
- Changing a quest's objectives resets unfinished progress on it for everyone (finished stays
  finished). Deleting a quest removes everyone's progress on it.
- If the server uses escrow, item rewards come out of your own inventory as a deposit, and what is left
  comes back to you when you change or delete the quest.
- Your quests are part of your data: an operator's data export for you includes them, and if your data
  is erased your quests stay but no longer name you as the author.
