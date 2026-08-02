# Server → client sync

NeroQuests is **server-authoritative**. The server owns every quest decision — what counts, what
advances, what completes — and the client only ever *renders* what the server tells it. There is no
code path in which a client decides that a quest is done.

This page describes what crosses the network, when, and what deliberately does not.

## Why anything is synced at all

Quests and chapters are **datapack content**, and datapacks live on the server. A player connected to
a dedicated server has no copy of them, so without a sync the quest book would have nothing to draw.
Progress has the same problem: it is stored server-side, in the world save.

So the server pushes two things:

| Payload | Contents | Size |
| --- | --- | --- |
| `neroquests:quest_definitions` | Every loaded quest and chapter — titles, icons, objectives, rewards, layout | One snapshot, shared by all players |
| `neroquests:quest_progress` | The receiving player's own objective counters and completion times, plus the shared `scope: server` section | One snapshot per player |

In singleplayer the integrated server sends these to its own client. That is harmless and keeps a
single code path for both singleplayer and multiplayer.

## When each one is sent

| Moment | Definitions | Progress |
| --- | --- | --- |
| A player joins | Yes, to that player | Yes, to that player |
| After a datapack `/reload` | Yes, to everyone online | Yes, to everyone online |
| A player's progress changed | No | Yes, to that player |
| A `scope: server` quest's progress changed | No | Yes, to everyone online |
| Nothing changed | No | No |

The **change** sync is driven by the quest engine itself. An evaluation pass records whether it
actually wrote anything, and pushes at most **one** progress payload per affected player at the end
of the pass — never one per objective. A settled world where nobody is completing anything therefore
produces no quest traffic at all, even though the engine re-measures once a second.

A progress payload is a full snapshot rather than a diff. A player's rows are a handful of quest ids
with a few integers each, so a snapshot costs about as much as a diff would and removes a whole class
of client/server drift bugs.

## Reload handling

`/reload` re-reads every quest and chapter from the datapacks, re-runs the same validation the server
does at startup (dropping quests with unknown objective types, dangling prerequisites and
prerequisite cycles), and then re-syncs definitions and progress to everyone online.

A reload never touches stored progress. If a quest disappears from the pack, the progress recorded
for it is simply kept and becomes inert; if the quest comes back, that progress applies again. If a
pack **appends** objectives to an existing quest, the counters players already have keep their
meaning — which is why packs should append rather than reorder or remove (see
[Quest format](Quest-Format.md)).

## Leaving a world or server

The client drops its synced copy of both definitions and progress when it disconnects. Nothing from
one session can be shown in the next, and joining a server that does not run NeroQuests shows no
quests rather than the last session's.

## Privacy

*(See also [Data storage](Data-Storage.md) and [`../PRIVACY.md`](../PRIVACY.md).)*

- **You only ever receive your own progress.** A progress payload contains the recipient's own quest
  rows and the shared `scope: server` section — which is world state and contains no identifiers at
  all. Another player's counters, completions or UUID are never sent to your client.
- **A server-scope change fans out individually.** When shared progress moves, each online player is
  sent their *own* snapshot; no one is sent anyone else's.
- **No identity travels.** A progress payload does not even carry the recipient's UUID — the snapshot
  is implicitly "yours", and the client already knows who it is.
- **Definitions carry no player data.** They are pack content — titles, icons, objective and reward
  specifications — and are byte-for-byte identical for every recipient.

## Notes for pack and mod authors

- Both payloads are declared **optional** on every loader, so a vanilla or NeroQuests-less client can
  still connect to a NeroQuests server. It simply never receives them.
- Quest definitions travel encoded with the *same* codecs the datapack loader uses, so the JSON you
  write is exactly what the client decodes. A definition that fails to decode client-side is logged
  against its id and skipped, never crashing the packet or the game.
- Objective and reward **types** are code, not data — a client needs the same NeroQuests version (and
  the same add-on mods, if a pack uses their types) to understand every definition. Types the server
  did not recognise were already dropped before sending.

## See also

- [Data storage](Data-Storage.md) — how progress is saved, kept, erased and exported
- [Quest format](Quest-Format.md) — the datapack JSON that gets synced
- [Objectives](Objectives.md) — how progress is measured and credited
