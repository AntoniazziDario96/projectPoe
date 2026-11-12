package it.poemeta.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import it.poemeta.analysis.CharacterProfile.Defence;
import it.poemeta.analysis.CharacterProfile.ItemRef;
import it.poemeta.analysis.CharacterProfile.RareItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trasforma il JSON di un personaggio di poe.ninja in un {@link CharacterProfile}. */
public final class ProfileExtractor {

    static final int FRAME_RARE = 2;
    static final int FRAME_UNIQUE = 3;
    static final int FRAME_RELIC = 9;

    /** Slot dell'equipaggiamento considerati (il secondo set di armi è escluso). */
    public static final Map<String, String> SLOTS = Map.of(
            "Helm", "Helm", "BodyArmour", "BodyArmour", "Gloves", "Gloves", "Boots", "Boots",
            "Amulet", "Amulet", "Ring", "Ring", "Ring2", "Ring", "Belt", "Belt",
            "Weapon", "Weapon", "Offhand", "Offhand");

    private ProfileExtractor() {
    }

    public static CharacterProfile extract(JsonNode c) {
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode skill : c.path("skills")) {
            List<String> gems = new ArrayList<>();
            for (JsonNode g : skill.path("allGems")) {
                if (!g.path("isBuiltInSupport").asBoolean(false)) {
                    String name = g.path("name").asText("");
                    if (!name.isBlank()) {
                        gems.add(name);
                    }
                }
            }
            if (!gems.isEmpty()) {
                groups.add(gems);
            }
        }

        List<Integer> passives = new ArrayList<>();
        for (JsonNode p : c.path("passiveSelection")) {
            passives.add(p.asInt());
        }

        List<String> keystones = names(c.path("keyStones"));
        List<String> masteries = names(c.path("masteries"));

        Set<String> seenUniques = new LinkedHashSet<>();
        List<ItemRef> uniques = new ArrayList<>();
        List<RareItem> rares = new ArrayList<>();
        for (String section : List.of("items", "flasks", "jewels")) {
            for (JsonNode it : c.path(section)) {
                JsonNode d = it.path("itemData");
                int frame = d.path("frameType").asInt(-1);
                if (frame == FRAME_UNIQUE || frame == FRAME_RELIC) {
                    String name = d.path("name").asText("");
                    if (!name.isBlank() && seenUniques.add(name)) {
                        uniques.add(new ItemRef(name, d.path("icon").asText(null), slotOf(section, d)));
                    }
                } else if (frame == FRAME_RARE && "items".equals(section)) {
                    String slot = SLOTS.get(d.path("inventoryId").asText(""));
                    if (slot != null) {
                        rares.add(new RareItem(slot, d.path("baseType").asText(d.path("typeLine").asText("")),
                                d.path("name").asText(""), d.path("icon").asText(null), explicitMods(d)));
                    }
                }
            }
        }

        JsonNode def = c.path("defensiveStats");
        String bloodline = c.hasNonNull("secondaryAscendancyClassName")
                ? c.path("secondaryAscendancyClassName").asText() : null;
        return new CharacterProfile(
                c.path("account").asText(),
                c.path("name").asText(),
                c.path("level").asInt(0),
                c.path("ascendancyClassName").asText(c.path("class").asText("")),
                bloodline,
                def.path("life").asInt(0),
                def.path("energyShield").asInt(0),
                c.path("pathOfBuildingExport").asText(null),
                groups, passives, keystones, masteries, uniques, rares,
                defence(def),
                text(c, "pantheonMajor"),
                text(c, "pantheonMinor"));
    }

    /** Mod espliciti di un item (fractured, normali e crafted) con i valori reali. */
    public static List<String> explicitMods(JsonNode itemData) {
        List<String> mods = new ArrayList<>();
        for (String kind : List.of("fracturedMods", "explicitMods", "craftedMods")) {
            for (JsonNode m : itemData.path(kind)) {
                mods.add(m.asText());
            }
        }
        return mods;
    }

    /** Slot di un item: quelli dell'equipaggiamento, oppure "Flask" / "Jewel". */
    static String slotOf(String section, JsonNode itemData) {
        return switch (section) {
            case "flasks" -> "Flask";
            case "jewels" -> "Jewel";
            default -> SLOTS.get(itemData.path("inventoryId").asText(""));
        };
    }

    static Defence defence(JsonNode def) {
        if (def.isMissingNode() || def.isNull()) {
            return null;
        }
        return new Defence(
                def.path("fireResistance").asInt(0),
                def.path("coldResistance").asInt(0),
                def.path("lightningResistance").asInt(0),
                def.path("chaosResistance").asInt(0),
                def.path("fireResistanceMax").asInt(75),
                def.path("coldResistanceMax").asInt(75),
                def.path("lightningResistanceMax").asInt(75),
                def.path("spellSuppressionChance").asInt(0));
    }

    private static String text(JsonNode c, String field) {
        String v = c.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static List<String> names(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String v = n.isTextual() ? n.asText() : n.path("name").asText("");
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}
