package it.poemeta.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Risultato di un'analisi skill + ascendancy, salvato come JSON nel database.
 * I campi aggiunti nelle versioni successive sono null nelle analisi salvate prima.
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

    /**
     * Livello di qualità richiesto ai mod degli item: basso, medio o alto, calcolato
     * sui valori che hanno davvero i giocatori analizzati (25°, 50° e 75° percentile).
     */
    public enum Tier {
        LOW, MID, HIGH;

        public static Tier parse(String s) {
            if (s == null) {
                return MID;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "low" -> LOW;
                case "high" -> HIGH;
                default -> MID;
            };
        }

        public String param() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Valori di un mod (o delle resistenze) tra i giocatori analizzati: 25°, 50° e 75° percentile. */
    public record Range(double low, double mid, double high) {

        public double pick(Tier tier) {
            return switch (tier) {
                case LOW -> low;
                case MID -> mid;
                case HIGH -> high;
            };
        }

        public static Range of(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return new Range(percentile(v, 0.25), percentile(v, 0.50), percentile(v, 0.75));
        }

        private static double percentile(double[] sorted, double p) {
            if (sorted.length == 1) {
                return sorted[0];
            }
            double pos = p * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
        }
    }

    /**
     * @param avgValue media del primo numero del mod (versioni precedenti)
     * @param values   percentili del valore del mod (null nelle analisi vecchie)
     */
    public record ModStat(String text, double pct, Double avgValue, Range values) {
    }

    /**
     * Item giallo tipico di uno slot per l'archetipo.
     *
     * @param eleRes   resistenze elementali totali sull'item (percentili, contando 0 per chi non ne ha)
     * @param chaosRes resistenza al chaos sull'item (percentili)
     */
    public record RareSlot(String slot, int count, double pctOfArchetype,
                           List<Stat> bases, List<ModStat> mods, Range eleRes, Range chaosRes) {
    }

    public record Representative(String account, String name, int level, int life, int energyShield,
                                 String pobCode, double avgSimilarity) {
    }

    /** Difese medie dei personaggi dell'archetipo (null nelle analisi vecchie). */
    public record Defence(double fireRes, double coldRes, double lightningRes, double chaosRes,
                          double suppression, int count) {
    }

    /**
     * @param uniqueSlots slot più comune di ogni unique (es. "Helm", "Flask", "Jewel")
     */
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
            List<RareSlot> rareSlots,
            Defence defence,
            List<Stat> pantheonMajor,
            List<Stat> pantheonMinor,
            Map<String, String> uniqueSlots) {
    }
}
