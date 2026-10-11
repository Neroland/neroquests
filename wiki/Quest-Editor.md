# Quest editor (players)

Make your own quests in the quest book: name a quest, pick what has to be done, choose a reward. Your
quests show up for everyone on the server in their own chapters, below NeroQuests' story and the
chapters your mods bring.

## Who can edit

Your server decides. Usually Creative mode is enough; operators may also add you to the editor
allow-list so you can edit in Survival, or lock the editor while a quest set is finished. If you can
edit, the quest book shows an **Edit** button in its top-right corner. Details for admins:
[Editor permissions](Editor-Permissions.md).

The button only appears when the server says you may edit, and the server checks again on every
change you send. If your permission is taken away while the book is open, the book returns to reading
mode and tells you so.

## Your first quest in under a minute

1. Open the quest book and press **Edit**.
2. If there is no custom chapter yet, press **+** in the toolbar, type a chapter title and press Enter.
3. With your chapter selected, press **+ Quest**. A three-step guide opens:
   - **Name it** — type a short title and press Enter.
   - **Add an objective** — the type button cycles through the objective types (it starts on *Collect
     items*). Type an id such as `minecraft:oak_log`, or press **Pick** to search for one, set the count
     and press **Create quest**. The quest now exists on the server.
   - **Add a reward** — the type button cycles through *Experience*, *Credits*, *Loot table* and *Item*.
     Fill it in and press **Finish**, or press **Skip** to add rewards later.
4. Your quest appears on the chapter page. Drag it wherever you like.

**More** (steps 2 and 3) shows the advanced options: queue several objectives before creating the
quest, make it repeatable with a cooldown, and pick a quest it requires.

## The edit-mode book

- **Chapter column.** NeroQuests' own chapters come first, then the chapters your mods bring (grouped
  by mod), then custom chapters. In edit mode a padlock marks chapters you cannot change (hover for
  "Provided by …") and a pencil marks custom chapters. An exclamation mark means the chapter belongs to
  a mod that is not installed.
- **Toolbar** (custom chapter selected): **+** adds a chapter, **✎** renames it, **▲**/**▼** move it
  up or down among the custom chapters, **✕** deletes it (you are asked to confirm; deleting a chapter
  deletes its quests and everyone's progress on them), **+ Quest** starts the three-step guide.
- **Chapter page.**
  - Drag a quest to move it; it snaps to the grid.
  - Each quest has a small handle on its right edge. Drag from the handle onto another quest to make the
    first quest a requirement of the second. The server refuses a link that would go round in a circle
    and says why.
  - Right-click a link line to remove it.
  - Click a quest to open its editor.
  - Chapters you cannot edit still show their quests and links, but refuse drags with a short message.

## The quest editor

Four tabs, plus **←** (or Esc) to go back to the chapter:

- **Details** — title, description, icon (type an item id, **Pick** one, or hold an item and press
  **Held**), **Repeatable** with a cooldown in seconds (shown as days, hours and minutes underneath),
  the quests this one requires (remove with **✕**, add with **+ Requirement**, which also works across
  chapters), who wrote it ("you" or "another player"), **Save** and **Delete**. Line breaks in the
  description are joined into one paragraph.
- **Objectives** — the quest's objectives with **✎** (load into the form) and **✕** (remove), and a form
  to add or update one. Each type has its own small form:
  - *Collect items* / *Craft items*: an item or an item tag (press **#** to switch, or pick a tag in the
    picker), a count, and for collecting whether the items are handed in.
  - *Defeat creatures*: a creature or a creature tag, and a count.
  - *Reach a dimension*: pick one of the dimensions on this server.
  - *Visit a structure*: type a structure id or `#tag`.
  - *Gate opens*: type a progression gate id.
  - *Mod event*: pick one of the event channels the server has seen this session, and a count.
  - *Complete a quest*: pick any quest you can see.
  - *Confirmed by an author*: a label; an editor ticks it for a player with `/neroquests manual complete`.

  Counts go from 1 to 1,000,000. A quest needs at least one objective and can have up to 12.
- **Rewards** — what a completing player receives, the running totals against your server's limits
  (rewards, item stacks, experience, credits) and a form to add one. On a server that escrows item
  rewards, the tab explains it, shows how many copies are held and lets you deposit more.
- **Preview** — the quest exactly as players see it in reading mode.

## Keyboard

Tab moves between fields and buttons, Enter confirms the form you are typing in (next step, save, add),
and Esc steps back: closes the picker or dialog, goes back a step in the guide, leaves the quest
editor, and finally closes the book. Every button explains itself when you hover over it.

## What you can make

- **Chapters** — a title (up to 64 characters) and an icon. Up to 20 per world by default.
- **Quests** — a title, an optional description (up to 512 characters), an icon and a position on the
  chapter page. A quest can require other quests first: yours, NeroQuests' own, or a mod pack's.
- **Objectives** (1 to 12): see the list above. Every item, creature, dimension and so on must exist on
  the server — the server checks when you save.
- **Rewards** (up to 9): item stacks (one stack each), a loot table, experience and credits, within the
  server's limits. Progression gates and faction standing cannot be rewards of player-made quests.
- **Repeatable** quests (off by default): after a cooldown of at least a minute the quest re-arms for
  each player and pays again, at most once per cooldown.

Player-made quests are always per player, and cannot be hidden until they become available. NeroQuests'
own quests and the quests your mods ship cannot be edited.

## Good to know

- Every change is sent to the server, which checks it and answers with a short message at the bottom
  of the book ("Saved." or the reason it was refused).
- Text is shown exactly as typed, minus colour/formatting codes and control characters, which are
  removed. It is never treated as a command, a link or a translation key.
- The book shows who made a quest only as **you** or **another player** — never a name.
- Changing a quest's objectives resets unfinished progress on it for everyone (finished stays
  finished). Deleting a quest removes everyone's progress on it.
- If the server uses escrow, item rewards come out of your own inventory as a deposit, and what is left
  comes back to you when you change or delete the quest.
- Your quests are part of your data: an operator's data export for you includes them, and if your data
  is erased your quests stay but no longer name you as the author.
