package it.poemeta.compare;

import it.poemeta.analysis.CharacterProfile;

import java.util.List;

/**
 * Personaggio del giocatore importato da un codice Path of Building o da poe.ninja.
 *
 * @param source    "pob" oppure "ninja"
 * @param mainSkill skill principale (gemma attiva del gruppo principale), può essere null
 * @param className classe base (es. "Witch")
 * @param profile   stesso formato dei personaggi analizzati, per confrontarli
 * @param items     item equipaggiati, slot per slot
 */
public record ImportedCharacter(String source, String mainSkill, String className,
                                CharacterProfile profile, List<EquippedItem> items) {

    /**
     * Un item equipaggiato.
     *
     * @param slot   Weapon, Offhand, Helm, BodyArmour, Gloves, Boots, Amulet, Ring, Belt, Flask, Jewel
     * @param rarity UNIQUE, RARE, MAGIC o NORMAL
     * @param mods   mod espliciti con i valori reali
     */
    public record EquippedItem(String slot, String rarity, String name, String baseType, String icon,
                               List<String> mods) {

        public boolean isUnique() {
            return "UNIQUE".equals(rarity) || "RELIC".equals(rarity);
        }

        public boolean isRare() {
            return "RARE".equals(rarity);
        }

        /** Nome da mostrare: il nome dell'unique o del giallo, altrimenti la base. */
        public String displayName() {
            return name == null || name.isBlank() ? baseType : name;
        }
    }
}
