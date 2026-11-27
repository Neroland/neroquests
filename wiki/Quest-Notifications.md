# Quest notifications

When a quest becomes available to you, a small **Quest Unlocked** card slides in at the top-left
corner of the screen. It shows the quest's icon and title, stays for a few seconds and slides back
out.

## When you see one

A card appears when a quest you can see moves from locked to available, for example because:

- you completed a quest it depends on,
- a progression gate opened and the quest became visible,
- a server-wide quest it depends on was completed,
- the server reloaded its datapacks and added a quest you can start straight away, or
- someone made a new quest in the in-game editor.

Each quest is announced once per session. A repeatable quest that resets, or a quest that is
locked again and later re-opens, does not show a second card until you rejoin.

When you join a world, the quests you could already start are not announced again. A player who
has no quest progress yet is shown their opening quests.

Completing a quest still announces itself in chat, as before.

## Several at once

- Up to three quests unlocked together get one card each, shown one after another.
- More than three unlocked together get a single card naming the first quest, with **+N more**.
- At most four cards wait at a time. Anything past that is added to the last card's **+N more**.

Open the [quest book](Quest-Book.md) to see every quest you can start.

## Where it is drawn

The card sits in the top-left corner, which vanilla leaves empty. Status effects stay top-right and
the boss bar stays top-centre: on narrow screens the card gets narrower so it does not run into the
boss bar. Long titles are cut to fit.

The card is hidden, and its timer paused, while the debug screen (F3) is open or the HUD is hidden
(F1).

## Turning it off

The setting is client-side, in `neroquests.properties`:

| Key | Default | Meaning |
| --- | --- | --- |
| `questUnlockToasts` | `true` | Show the top-left Quest Unlocked card. Set `false` to turn it off. |

A server cannot change this setting for you.

## Privacy

The server sends each player only the ids of the quests that just unlocked for that player. No
player name, UUID or other player's data is sent. The server keeps the list of quests it has
already announced to you in memory only. It is never saved or logged, and it is dropped when you
leave or the server stops. See [Server → client sync](Sync.md).
