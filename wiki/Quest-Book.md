# Quest Book

The **Quest Book** is how you read your quests: which chapters exist, how the quests in each chapter
depend on one another, how far along you are, and what each one pays out.

The book is a **view only**. Opening it, panning it and clicking quests changes nothing on the
server — it never completes a quest, never claims a reward, and never sends anything back. Progress
is tracked by the server as you play (see [Objectives](Objectives.md)) and pushed to your client;
the book simply draws what has already arrived (see [Server → client sync](Sync.md)).

## Opening it

There are two ways in, and they open exactly the same screen.

| How | Detail |
| --- | --- |
| The item | `neroquests:quest_book` — right-click while holding it |
| The key | **G** by default; rebind it under Options → Controls → **NeroQuests** |

The quest book item is available in creative from **Neroland Core's shared creative tab** — NeroQuests
adds no tab of its own.

Pressing the same key again while the book is open closes it.

## Reading the screen

- **Chapter tabs** run down the left, each showing its icon and title. Click one to switch chapters.
  If there are more chapters than fit, scroll the tab strip with the mouse wheel.
- **The quest graph** fills the rest of the page. Each quest sits at the position its chapter file
  gave it, and **dotted lines** join a quest to its prerequisites. A line is drawn only when both
  quests are laid out in the chapter you are looking at; a green line means that prerequisite is
  done, a grey one means it is not.
- **Drag anywhere in the graph to pan** it. Panning is clamped so the graph cannot be dragged out of
  sight. There is no zoom.
- **Hover a quest** for a summary: its title, its state, every objective with its `current / target`
  count, and its rewards.
- **Click a quest** to open its detail page — full description, an objective list with progress bars,
  and the reward list. Scroll it with the mouse wheel. **Esc** goes back to the graph; **Esc** again
  closes the book.

## Quest states

Each quest's state is worked out on your own client, purely to decide how to draw it.

| State | Look | Means |
| --- | --- | --- |
| Completed | Green frame, green corner pip | You (or the server, for a `scope: server` quest) finished it |
| In progress | Amber frame | At least one objective has progress on it |
| Available | Violet frame | Every prerequisite is complete and its visibility gate is open |
| Locked | Grey frame, dark fill | A prerequisite is still outstanding |
| Hidden | Not drawn at all | Its `visible_gate` is still closed |

A **hidden** quest is genuinely absent — no node, no line, no tooltip — so a quest gated behind a
Neroland Core progression gate cannot be read ahead of time. It appears the moment that gate opens.
Quests with `scope: server` show the whole world's shared progress rather than your own; see
[Quest format](Quest-Format.md) for what scope means.

## If the book looks empty

The book says *"No quests have been loaded on this server"* when your client has no synced
definitions. That is normal when the server has no quest datapack installed, and it is also what you
see before the first sync arrives on joining. The caches are cleared when you leave a world or
server, so one session's quests are never shown in the next.

## See also

- [Quest format](Quest-Format.md) — chapter layouts (`x` / `y`), icons, titles and `visible_gate`
- [Objectives](Objectives.md) — what each objective counts, and how it is measured
- [Rewards](Rewards.md) — what each reward line in the book means
- [Server → client sync](Sync.md) — what your client receives and when
