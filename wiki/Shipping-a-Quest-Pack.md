# Shipping a quest pack

Your mod can bring its own quests to the NeroQuests quest book with **data files only**. When
NeroQuests is installed your quests appear in the book; when it is not, the files are inert and
nothing happens. **Nothing else is needed — no dependency either way:** your mod does not depend on
NeroQuests, and NeroQuests never loads a class from your mod.

The full format is on [Quest format](Quest-Format.md). This page is the checklist.

## 1. Put the files here

Inside your mod's resources (for a multiloader project usually `common/src/main/resources/`), using
your **mod id** as the namespace:

```text
data/<modid>/neroquests/chapters/<chapter>.json
data/<modid>/neroquests/quests/<chapter>/<quest>.json
assets/<modid>/lang/en_us.json
```

The quest id is `<modid>:<chapter>/<quest>`. A chapter lists its quests with their grid positions;
prerequisites draw the lines.

## 2. Use translation keys

Titles and descriptions must be translation keys, never literal text. Use this shape so keys never
collide with anyone else's:

| What | Key |
| --- | --- |
| Chapter title | `chapter.<modid>.<chapter>.title` |
| Quest title | `quest.<modid>.<chapter>.<quest>.title` |
| Quest description | `quest.<modid>.<chapter>.<quest>.desc` |
| `manual` objective label | `quest.<modid>.<chapter>.<quest>.manual` |
| `mod_hook` name in the book (optional) | `hook.<modid>.<id>` |

Put every key in `assets/<modid>/lang/en_us.json`.

## 3. Follow the pack rules

- Depend only on **your own** quests or NeroQuests' spine (`neroquests:…`), never another mod's pack.
- No `neroquests:gate` or `neroquests:reputation` rewards. Packs record what a player did; they never
  unlock or block content. Use `item`, `xp`, `currency` and `mod_hook`.
- A personal milestone your mod already marks with a Core gate is a `neroquests:gate_open` objective.
  Threshold channels never name a player, so they suit **world-log** quests: `"scope": "server"`,
  listed in a chapter with `"allow_server_scope": true`.
- Want the chapter visible (greyed, with a badge) when your mod is missing? Set
  `"show_when_absent": true` on it. Otherwise nothing of your pack shows without your mod.
- Never use the `neroquests` namespace. Only NeroQuests' own mod files may define quests there: a
  **datapack** file in that namespace is dropped, and a datapack copy of one of NeroQuests' own quests is
  ignored (the original loads), both reported in `/neroquests reload-check`.

## 4. Your own reward: `mod_hook`

To do something only your mod can (unlock a page, refill a tank), ship a record gate and name it in a
`neroquests:mod_hook` reward. Ship the quest inside **your mod** — a `mod_hook` reward in a quest that
comes from a world or server datapack is dropped (with a `reload-check` line), because a hook fires your
mod's own effect and only your mod should be able to name it:

```text
data/<modid>/neroland_gates/hook/<id>.json      { "scope": "player", "title": "…" }
```

```json
{ "type": "neroquests:mod_hook", "gate": "<modid>:hook/<id>" }
```

When the quest completes NeroQuests opens that gate for the player (online or not). In your mod,
listen with Neroland Core — no NeroQuests classes involved:

```java
CoreEvents.onProgression(change -> {
    if (change.open() && change.gate().equals(MY_HOOK)) {
        UUID player = UUID.fromString(change.target());
        // apply your effect; optionally ProgressionGates.close(server, player, MY_HOOK) to re-arm
    }
});
```

Never use a hook gate to allow or deny anything — it is a record, not a lock.

For world-wide reactions, listen on Core's `ThresholdEvents` for `neroquests:quest_completed`
(scope = quest id) and `neroquests:chapter_completed` (scope = chapter id). They never say who.

## 5. Test it

In game, as an operator: `/reload`, then **`/neroquests reload-check`**. It prints the quest count per
source (`spine 30 / pack <modid> 12 / custom 0`), every dropped quest with the rule it broke, and any
pack skipped because its mod is not installed.

In your build, copy this test (JUnit 5 and Gson; Gson already ships with Minecraft). It checks the
same rules NeroQuests applies at load, without NeroQuests on the classpath. Change `NAMESPACE`.

```java
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class QuestPackTest {
    static final String NAMESPACE = "mymod";
    static final Path ROOT = Path.of("src/main/resources");
    static final Set<String> OBJECTIVES = Set.of("collect_item", "craft_item", "kill_entity", "reach_dimension",
            "visit_structure", "gate_open", "quest_complete", "custom_event", "economy", "manual");
    static final Set<String> REWARDS = Set.of("item", "xp", "currency", "mod_hook");
    static final String KEY = "[a-z0-9_-]+(\\.[a-z0-9_-]+)+";

    @Test
    void packFollowsTheNeroQuestsFormat() throws IOException {
        Path data = ROOT.resolve("data/" + NAMESPACE + "/neroquests");
        Map<String, JsonObject> quests = read(data.resolve("quests"));
        Map<String, JsonObject> chapters = read(data.resolve("chapters"));
        JsonObject lang = JsonParser.parseString(Files.readString(
                ROOT.resolve("assets/" + NAMESPACE + "/lang/en_us.json"))).getAsJsonObject();
        List<String> problems = new ArrayList<>();
        Set<String> serverOk = new HashSet<>();
        chapters.forEach((id, c) -> {
            key(problems, lang, id, c, "title", true);
            for (JsonElement e : c.getAsJsonArray("quests")) {
                String q = e.getAsJsonObject().get("quest").getAsString();
                if (!quests.containsKey(q)) problems.add(id + " lists unknown/foreign quest " + q);
                if (c.has("allow_server_scope") && c.get("allow_server_scope").getAsBoolean()) serverOk.add(q);
            }
        });
        quests.forEach((id, q) -> {
            key(problems, lang, id, q, "title", true);
            key(problems, lang, id, q, "description", false);
            boolean server = q.has("scope") && "server".equals(q.get("scope").getAsString());
            if (server && !serverOk.contains(id)) problems.add(id + ": scope server needs allow_server_scope");
            if (q.has("prerequisites")) q.getAsJsonArray("prerequisites").forEach(p -> ref(problems, quests, id, p.getAsString()));
            JsonArray objectives = q.getAsJsonArray("objectives");
            if (objectives == null || objectives.isEmpty()) problems.add(id + ": no objectives");
            else for (JsonElement e : objectives) {
                JsonObject o = e.getAsJsonObject();
                String type = o.get("type").getAsString().replace("neroquests:", "");
                if (!OBJECTIVES.contains(type)) problems.add(id + ": unknown objective " + type);
                if (type.equals("quest_complete")) ref(problems, quests, id, o.get("quest").getAsString());
                if (type.equals("custom_event") && !server && !(o.has("audience")
                        && "everyone".equals(o.get("audience").getAsString())))
                    problems.add(id + ": custom_event on a player quest needs \"audience\": \"everyone\"");
            }
            if (q.has("rewards")) for (JsonElement e : q.getAsJsonArray("rewards")) {
                JsonObject r = e.getAsJsonObject();
                String type = r.get("type").getAsString().replace("neroquests:", "");
                if (!REWARDS.contains(type)) problems.add(id + ": reward " + type + " not allowed in a pack");
                if (type.equals("mod_hook") && !r.get("gate").getAsString().startsWith(NAMESPACE + ":hook/"))
                    problems.add(id + ": mod_hook gate must be " + NAMESPACE + ":hook/<id>");
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static void ref(List<String> problems, Map<String, JsonObject> quests, String id, String target) {
        if (target.startsWith("neroquests:")) return;
        if (!target.startsWith(NAMESPACE + ":")) problems.add(id + ": depends on another pack's quest " + target);
        else if (!quests.containsKey(target)) problems.add(id + ": unknown quest " + target);
    }

    static void key(List<String> problems, JsonObject lang, String id, JsonObject o, String field, boolean required) {
        if (!o.has(field)) { if (required) problems.add(id + ": missing " + field); return; }
        String key = o.get(field).getAsString();
        if (!key.matches(KEY)) problems.add(id + ": " + field + " is not a translation key");
        else if (!lang.has(key)) problems.add(id + ": en_us.json has no " + key);
    }

    static Map<String, JsonObject> read(Path dir) throws IOException {
        Map<String, JsonObject> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                out.put(NAMESPACE + ":" + rel.substring(0, rel.length() - 5),
                        JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        }
        return out;
    }
}
```

The copy checks structure and the pack rules. Codec details (a `count` of `0`, a field of the wrong
type, a typo'd item id) are reported by `/neroquests reload-check` on a running server.

## That is all

No code, no dependency, no registration call. Ship the files with your mod; NeroQuests finds them.

## See also

- [Quest format](Quest-Format.md) — every field, objective and reward
- [Objectives](Objectives.md)
- [Rewards](Rewards.md)
- [Commands](Commands.md)
- [Home](Home.md)
