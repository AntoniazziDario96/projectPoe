package it.poemeta.compare;

import it.poemeta.analysis.CharacterProfile;
import it.poemeta.analysis.CharacterProfile.Defence;
import it.poemeta.analysis.CharacterProfile.ItemRef;
import it.poemeta.analysis.CharacterProfile.RareItem;
import it.poemeta.compare.ImportedCharacter.EquippedItem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legge l'XML di Path of Building: item equipaggiati, gemme, albero passivo, pantheon
 * e statistiche calcolate da PoB (resistenze, vita, ES, suppression).
 */
public class PobParser {

    /** Slot di PoB -> slot usati nelle analisi. */
    private static final Map<String, String> SLOTS = Map.ofEntries(
            Map.entry("Weapon 1", "Weapon"), Map.entry("Weapon 2", "Offhand"),
            Map.entry("Helmet", "Helm"), Map.entry("Body Armour", "BodyArmour"),
            Map.entry("Gloves", "Gloves"), Map.entry("Boots", "Boots"), Map.entry("Amulet", "Amulet"),
            Map.entry("Ring 1", "Ring"), Map.entry("Ring 2", "Ring"), Map.entry("Belt", "Belt"),
            Map.entry("Flask 1", "Flask"), Map.entry("Flask 2", "Flask"), Map.entry("Flask 3", "Flask"),
            Map.entry("Flask 4", "Flask"), Map.entry("Flask 5", "Flask"));

    /** Righe dopo i mod che non sono mod. */
    private static final Set<String> NOT_MODS = Set.of("Corrupted", "Mirrored", "Split", "Unidentified",
            "Shaper Item", "Elder Item", "Crusader Item", "Redeemer Item", "Hunter Item", "Warlord Item",
            "Fractured Item", "Synthesised Item", "Searing Exarch Item", "Eater of Worlds Item");
    private static final Pattern TAGS = Pattern.compile("^(\\{[^}]*})+");
    private static final Pattern RANGE = Pattern.compile("\\((-?\\d+(?:\\.\\d+)?)-(-?\\d+(?:\\.\\d+)?)\\)");
    private static final Pattern MASTERY = Pattern.compile("\\{(\\d+),(\\d+)}");

    private final TreeData tree;

    public PobParser(TreeData tree) {
        this.tree = tree;
    }

    public ImportedCharacter parse(String xml) {
        Document doc = parseXml(xml);
        Element root = doc.getDocumentElement();
        Element build = first(root, "Build");
        if (build == null) {
            throw new ImportException("import.error.invalidPob");
        }

        Map<String, Double> stats = new HashMap<>();
        for (Element ps : children(build, "PlayerStat")) {
            try {
                stats.put(ps.getAttribute("stat"), Double.parseDouble(ps.getAttribute("value")));
            } catch (NumberFormatException ignored) {
                // statistiche non numeriche
            }
        }

        SkillInfo skills = skills(root, intAttr(build, "mainSocketGroup", 1));
        TreeInfo treeInfo = tree(root);
        List<EquippedItem> items = items(root, treeInfo.socketedJewels());

        List<ItemRef> uniques = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<RareItem> rares = new ArrayList<>();
        for (EquippedItem it : items) {
            if (it.isUnique() && seen.add(it.name())) {
                uniques.add(new ItemRef(it.name(), null, it.slot()));
            } else if (it.isRare() && !"Flask".equals(it.slot()) && !"Jewel".equals(it.slot())) {
                rares.add(new RareItem(it.slot(), it.baseType(), it.name(), null, it.mods()));
            }
        }

        int life = (int) Math.round(stats.getOrDefault("Life", 0.0));
        int es = (int) Math.round(stats.getOrDefault("EnergyShield", 0.0));
        Defence defence = stats.containsKey("FireResist") ? new Defence(
                res(stats, "FireResist"), res(stats, "ColdResist"), res(stats, "LightningResist"),
                res(stats, "ChaosResist"),
                max(stats, "FireResist"), max(stats, "ColdResist"), max(stats, "LightningResist"),
                (int) Math.round(stats.getOrDefault("EffectiveSpellSuppressionChance",
                        stats.getOrDefault("SpellSuppressionChance", 0.0))))
                : null;

        String ascendancy = build.getAttribute("ascendClassName");
        String className = build.getAttribute("className");
        if (ascendancy.isBlank() || "None".equals(ascendancy)) {
            ascendancy = className;
        }
        CharacterProfile profile = new CharacterProfile(null, null, intAttr(build, "level", 0), ascendancy,
                blankToNull(build.getAttribute("secondaryAscendClassName")),
                life, es, null, skills.groups(), treeInfo.nodes(),
                tree.keystones(treeInfo.nodes()), tree.masteries(treeInfo.masteryEffects()),
                uniques, rares, defence,
                god(build.getAttribute("pantheonMajorGod")), god(build.getAttribute("pantheonMinorGod")));
        return new ImportedCharacter("pob", skills.mainSkill(), className, profile, items);
    }

    // ------------------------------------------------------------ gemme

    private record SkillInfo(List<List<String>> groups, String mainSkill) {
    }

    private static SkillInfo skills(Element root, int mainSocketGroup) {
        Element skillsEl = first(root, "Skills");
        if (skillsEl == null) {
            return new SkillInfo(List.of(), null);
        }
        List<Element> sets = children(skillsEl, "SkillSet");
        Element container = skillsEl;
        if (!sets.isEmpty()) {
            String active = skillsEl.getAttribute("activeSkillSet");
            container = sets.stream().filter(s -> s.getAttribute("id").equals(active)).findFirst().orElse(sets.get(0));
        }
        List<List<String>> groups = new ArrayList<>();
        String main = null;
        List<Element> skillEls = children(container, "Skill");
        for (int i = 0; i < skillEls.size(); i++) {
            Element sk = skillEls.get(i);
            if ("false".equals(sk.getAttribute("enabled"))) {
                continue;
            }
            List<String> gems = new ArrayList<>();
            String firstActive = null;
            for (Element g : children(sk, "Gem")) {
                if ("false".equals(g.getAttribute("enabled"))) {
                    continue;
                }
                String name = gemName(g);
                if (name == null) {
                    continue;
                }
                gems.add(name);
                if (firstActive == null && !CharacterProfile.isSupport(name)) {
                    firstActive = name;
                }
            }
            if (!gems.isEmpty()) {
                groups.add(gems);
            }
            if (i + 1 == mainSocketGroup) {
                main = firstActive;
            }
        }
        return new SkillInfo(groups, main == null ? null : main.replaceFirst("^Vaal ", ""));
    }

    /** Nome della gemma come lo scrive poe.ninja: i supporti finiscono con " Support". */
    static String gemName(Element g) {
        String name = g.getAttribute("nameSpec");
        if (name.isBlank()) {
            name = g.getAttribute("skillId");
        }
        if (name.isBlank()) {
            return null;
        }
        boolean support = g.getAttribute("gemId").contains("SupportGem")
                || g.getAttribute("skillId").startsWith("Support");
        if (support && !name.endsWith(" Support")) {
            name = name + " Support";
        }
        return name;
    }

    // ------------------------------------------------------------ albero

    /**
     * @param socketedJewels id degli item incastonati nell'albero
     */
    private record TreeInfo(List<Integer> nodes, List<Integer> masteryEffects, List<String> socketedJewels) {
    }

    private static TreeInfo tree(Element root) {
        Element treeEl = first(root, "Tree");
        if (treeEl == null) {
            return new TreeInfo(List.of(), List.of(), List.of());
        }
        List<Element> specs = children(treeEl, "Spec");
        if (specs.isEmpty()) {
            return new TreeInfo(List.of(), List.of(), List.of());
        }
        int active = intAttr(treeEl, "activeSpec", 1);
        Element spec = specs.get(Math.min(Math.max(active, 1), specs.size()) - 1);
        List<Integer> nodes = new ArrayList<>();
        for (String s : spec.getAttribute("nodes").split(",")) {
            try {
                if (!s.isBlank()) {
                    nodes.add(Integer.parseInt(s.trim()));
                }
            } catch (NumberFormatException ignored) {
                // nodo non numerico
            }
        }
        List<Integer> effects = new ArrayList<>();
        Matcher m = MASTERY.matcher(spec.getAttribute("masteryEffects"));
        while (m.find()) {
            effects.add(Integer.parseInt(m.group(2)));
        }
        List<String> jewels = new ArrayList<>();
        Element sockets = first(spec, "Sockets");
        if (sockets != null) {
            for (Element s : children(sockets, "Socket")) {
                String id = s.getAttribute("itemId");
                if (!id.isBlank() && !"0".equals(id)) {
                    jewels.add(id);
                }
            }
        }
        return new TreeInfo(nodes, effects, jewels);
    }

    // ------------------------------------------------------------ item

    private static List<EquippedItem> items(Element root, List<String> socketedJewels) {
        Element itemsEl = first(root, "Items");
        if (itemsEl == null) {
            return List.of();
        }
        Map<String, String> texts = new HashMap<>();
        for (Element it : children(itemsEl, "Item")) {
            texts.put(it.getAttribute("id"), ownText(it));
        }
        List<Element> sets = children(itemsEl, "ItemSet");
        Element container = itemsEl;
        if (!sets.isEmpty()) {
            String active = itemsEl.getAttribute("activeItemSet");
            container = sets.stream().filter(s -> s.getAttribute("id").equals(active)).findFirst().orElse(sets.get(0));
        }
        List<EquippedItem> out = new ArrayList<>();
        for (Element slot : children(container, "Slot")) {
            String mapped = SLOTS.get(slot.getAttribute("name"));
            String id = slot.getAttribute("itemId");
            if (mapped == null || id.isBlank() || "0".equals(id) || !texts.containsKey(id)) {
                continue;
            }
            EquippedItem it = parseItem(mapped, texts.get(id));
            if (it != null) {
                out.add(it);
            }
        }
        for (String id : socketedJewels) {
            if (texts.containsKey(id)) {
                EquippedItem it = parseItem("Jewel", texts.get(id));
                if (it != null) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    /** Testo di un item nel formato di PoB (lo stesso del gioco con Ctrl+C, più qualche intestazione). */
    static EquippedItem parseItem(String slot, String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\\R")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        if (lines.isEmpty() || !lines.get(0).startsWith("Rarity:")) {
            return null;
        }
        String rarity = lines.get(0).substring("Rarity:".length()).trim().toUpperCase(Locale.ROOT);
        String name;
        String base;
        int i;
        if (("RARE".equals(rarity) || "UNIQUE".equals(rarity) || "RELIC".equals(rarity)) && lines.size() > 2) {
            name = lines.get(1);
            base = lines.get(2);
            i = 3;
        } else {
            name = null;
            base = lines.size() > 1 ? lines.get(1) : "";
            i = 2;
        }
        // intestazioni fino a "Implicits: N", poi N implicit, poi i mod espliciti
        int implicits = -1;
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("Implicits:")) {
                try {
                    implicits = Integer.parseInt(l.substring("Implicits:".length()).trim());
                } catch (NumberFormatException e) {
                    implicits = 0;
                }
                i++;
                break;
            }
        }
        List<String> mods = new ArrayList<>();
        if (implicits >= 0) {
            i += implicits;
            for (; i < lines.size(); i++) {
                String mod = cleanMod(lines.get(i));
                if (mod != null) {
                    mods.add(mod);
                }
            }
        }
        return new EquippedItem(slot, rarity, name, base, null, mods);
    }

    /** Toglie i marcatori di PoB ({crafted}, {range:...}) e sostituisce gli intervalli con il valore medio. */
    static String cleanMod(String line) {
        String tags = "";
        Matcher tm = TAGS.matcher(line);
        if (tm.find()) {
            tags = tm.group();
        }
        String mod = line.substring(tags.length()).trim();
        if (mod.isEmpty() || NOT_MODS.contains(mod) || tags.contains("{enchant}") || tags.contains("{implicit}")
                || mod.endsWith("(enchant)") || mod.endsWith("(implicit)")) {
            return null;
        }
        mod = mod.replace(" (crafted)", "").replace(" (fractured)", "");
        Matcher rm = RANGE.matcher(mod);
        StringBuilder sb = new StringBuilder();
        while (rm.find()) {
            double a = Double.parseDouble(rm.group(1));
            double b = Double.parseDouble(rm.group(2));
            double v = (a + b) / 2;
            rm.appendReplacement(sb, v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v));
        }
        rm.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------ utilità XML

    static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // niente DTD né entità esterne (protezione da XXE)
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // niente messaggi su stderr
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ImportException("import.error.invalidPob", e);
        }
    }

    private static Element first(Element parent, String tag) {
        List<Element> l = children(parent, tag);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Solo il testo diretto (non quello dei figli come ModRange). */
    private static String ownText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static int intAttr(Element e, String name, int def) {
        try {
            return Integer.parseInt(e.getAttribute(name).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static int res(Map<String, Double> stats, String key) {
        return (int) Math.round(stats.getOrDefault(key, 0.0));
    }

    /** PoB esporta la resistenza già limitata dal massimo: se supera 75 il massimo è almeno quel valore. */
    private static int max(Map<String, Double> stats, String key) {
        return Math.max(75, res(stats, key));
    }

    private static String god(String v) {
        return v == null || v.isBlank() || "None".equalsIgnoreCase(v) ? null : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() || "None".equals(v) ? null : v;
    }
}
