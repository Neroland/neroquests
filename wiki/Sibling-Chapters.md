# Sibling chapters

Other Neroland mods ship their own chapter of quests inside their jar, as plain data files. NeroQuests
reads them like any other quest pack (see [Shipping a quest pack](Shipping-a-Quest-Pack.md)). There is no
dependency either way: each chapter appears in the quest book **only while its mod is installed**, and
each mod works without NeroQuests.

## Which chapter comes with which mod

| Mod | Chapter | Quests | World quests | Shown when |
| --- | --- | ---: | ---: | --- |
| Nerospace | Starfarer's Log | 15 | 0 | Nerospace is installed |
| Nerotech | Industrial Record | 14 | 2 | Nerotech is installed |
| NeroPower | Reactor Log | 10 | 0 | NeroPower is installed |
| NeroLogistics | Supply Lines | 12 | 0 | NeroLogistics is installed |
| NeroEconomy | Trader's Ledger | 12 | 3 | NeroEconomy is installed |
| NeroColonies | Colony Chronicle | 12 | 7 | NeroColonies is installed; quests appear once your first colony is established |
| NeroCreatures | Bestiary | 11 | 1 | NeroCreatures is installed; quests appear once you have reached orbit |
| NeroAgriculture | Field Ledger | 10 | 0 | NeroAgriculture is installed; the four tier quests appear as each tier unlocks |
| NeroDecor | Architect | 11 | 0 | NeroDecor is installed |
| NeroFactions | Standing Among the Stars | 10 | 2 | NeroFactions is installed; each faction's inner-circle quest appears when you reach it |
| NeroNotes | Resonance | 9 | 4 | NeroNotes is installed |

*World quests* (`"scope": "server"`) are shared by everyone on the server, like a world log: they
complete once for the world and reward every player who could take part at that moment (see
[Rewards](Rewards.md)).

## How they behave

- **Read-only.** A mod's chapter cannot be changed with the in-game editor; in edit mode it shows a
  padlock with "Provided by *mod name* — cannot be edited". Server operators can still override or
  replace pack files with a datapack of the same namespace.
- **Own namespace only.** A pack quest depends only on quests of its own mod or on the built-in story
  line, never on another mod's pack, so installing or removing one mod never breaks another chapter.
- **Colony Chronicle** also holds *Breathing Room*, *Full Larder* and *Settled Ground*, which were part
  of The Space Race before 0.4.0. Progress on them is carried over automatically (see the changelog's
  Migration notes).
- A quest that listens for an event its mod does not send yet simply waits; it never completes for
  free.
- `/neroquests reload-check` lists how many quests each installed pack loaded and names any file that
  was dropped, with the rule it broke.

## Removing a mod

Removing a mod removes its chapter from the book. Progress on its quests stays in the world's quest
data (like any quest that is not loaded) and comes back if the mod is installed again; it is pruned by
the normal retention settings (see [Data storage](Data-Storage.md)).
