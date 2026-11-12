package it.poemeta.analysis;

import java.util.List;

/**
 * Le informazioni di un personaggio che servono all'analisi, estratte dal JSON di poe.ninja.
 *
 * @param skillGroups gruppi di gemme collegate (nomi delle gemme, esclusi i supporti "built-in")
 * @param passives    id dei nodi dell'albero passivo allocati
 */
public record CharacterProfile(
        String account,
        String name,
        int level,
        String ascendancy,
        String bloodline,
        int life,
        int energyShield,
        String pobCode,
        List<List<String>> skillGroups,
        List<Integer> passives,
        List<String> keystones,
        List<String> masteries,
        List<ItemRef> uniques,
        List<RareItem> rares) {

    public record ItemRef(String name, String icon) {
    }

    /**
     * Item giallo equipaggiato.
     *
     * @param slot slot normalizzato (Helm, BodyArmour, Gloves, Boots, Amulet, Ring, Belt, Weapon, Offhand)
     * @param mods mod espliciti (inclusi fractured e crafted) con i valori reali
     */
    public record RareItem(String slot, String baseType, String name, String icon, List<String> mods) {
    }

    /**
     * Gemme di supporto collegate alla skill principale. La skill può essere nel gruppo
     * anche come versione Vaal (es. "Vaal Righteous Fire" concede anche "Righteous Fire").
     * Se compare in più gruppi si sceglie quello con più supporti.
     */
    public List<String> supportsOf(String skill) {
        List<String> best = List.of();
        for (List<String> group : skillGroups) {
            boolean match = group.stream().anyMatch(g -> isSameSkill(g, skill));
            if (match) {
                List<String> supports = group.stream().filter(CharacterProfile::isSupport).toList();
                if (supports.size() > best.size()) {
                    best = supports;
                }
            }
        }
        return best;
    }

    public static boolean isSameSkill(String gem, String skill) {
        return gem.equalsIgnoreCase(skill) || gem.equalsIgnoreCase("Vaal " + skill);
    }

    public static boolean isSupport(String gem) {
        return gem.endsWith(" Support");
    }
}
