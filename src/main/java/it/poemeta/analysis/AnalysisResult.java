package it.poemeta.analysis;

import java.time.Instant;
import java.util.List;

/**
 * Risultato di un'analisi skill + ascendancy, salvato come JSON nel database.
 */
public record AnalysisResult(
        String league,
        String ascendancy,
        String skill,
        long comboTotal,
        int sampleSize,
        int failed,
        Instant analyzedAt,
        Overall overall,
        List<Archetype> archetypes) {

    /**
     * Statistiche aggregate su TUTTI i personaggi della coppia (non solo il campione),
     * fornite direttamente dalla ricerca di poe.ninja.
     *
     * @param rareSlots quota di personaggi con un item giallo in quello slot (es. "Helmet")
     */
    public record Overall(List<Stat> uniques, List<Stat> gems, List<Stat> keystones, List<Stat> masteries,
                          List<Stat> anoints, List<Stat> bloodlines, List<Stat> rareSlots) {
    }

    /** Un elemento (gemma, unique, keystone, ...) con la quota di personaggi che lo usano. */
    public record Stat(String name, double pct, String icon) {
    }

    public record ModStat(String text, double pct, Double avgValue) {
    }

    /** Item giallo tipico di uno slot per l'archetipo. */
    public record RareSlot(String slot, int count, double pctOfArchetype,
                           List<Stat> bases, List<ModStat> mods) {
    }

    public record Representative(String account, String name, int level, int life, int energyShield,
                                 String pobCode, double avgSimilarity) {
    }

    public record Archetype(
            int index,
            String label,
            int size,
            double share,
            boolean minor,
            double cohesion,
            Representative representative,
            double avgLevel,
            double avgLife,
            double avgEnergyShield,
            List<Stat> supports,
            List<Stat> gems,
            List<Stat> uniques,
            List<Stat> keystones,
            List<Stat> masteries,
            List<Stat> bloodlines,
            List<RareSlot> rareSlots) {
    }
}
