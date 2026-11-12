package it.poemeta.compare;

import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Tier;
import it.poemeta.compare.ImportedCharacter.EquippedItem;

import java.util.List;

/** Risultato del confronto tra il personaggio del giocatore e un archetipo analizzato. */
public record CompareResult(
        ImportedCharacter character,
        AnalysisResult analysis,
        Archetype archetype,
        List<ArchetypeMatch> matches,
        Tier tier,
        boolean oldAnalysis,
        List<Notice> defence,
        List<ItemSuggestion> items,
        List<GemSuggestion> gems,
        List<Notice> gemNotices,
        List<String> missingKeystones,
        boolean keystonesKnown,
        Pantheon pantheon,
        Summary summary) {

    /**
     * Quanto il personaggio somiglia a ciascun archetipo (0-1).
     *
     * @param items somiglianza dei soli item
     */
    public record ArchetypeMatch(int index, String label, double score, double items, boolean minor) {
    }

    /**
     * Un avviso: {@code key} è la chiave del testo tradotto, a0..a2 i parametri già formattati.
     *
     * @param level "bad" (da sistemare), "warn" (da controllare) oppure "ok"
     */
    public record Notice(String level, String key, String a0, String a1, String a2) {

        public static Notice of(String level, String key, Object... args) {
            String[] a = new String[3];
            for (int i = 0; i < 3; i++) {
                a[i] = i < args.length && args[i] != null ? String.valueOf(args[i]) : "";
            }
            return new Notice(level, key, a[0], a[1], a[2]);
        }
    }

    /** Controllo di un mod del profilo sull'item attuale del giocatore. */
    public record ModCheck(String mod, Double min, Double current, boolean ok, boolean resistance) {
    }

    /**
     * Un item da cambiare.
     *
     * @param kind       "unique" (comprare un unique dell'archetipo) oppure "rare" (un giallo migliore)
     * @param reason     chiave del motivo (es. "compare.reason.missingUnique")
     * @param current    item attuale del giocatore in quello slot, può essere null
     * @param target     nome dell'unique da comprare, oppure la base consigliata del giallo
     * @param diff       quanto l'item attuale è diverso da quello dei giocatori analizzati (0-1)
     * @param usage      quota dei giocatori dell'archetipo che usano l'item consigliato
     * @param priceChaos prezzo stimato in chaos (null se non ancora noto)
     * @param priceToken id per leggere il prezzo dal trade in un secondo momento (gialli), null se già noto
     * @param minEleRes  resistenze elementali minime richieste al giallo, null se nessuna
     */
    public record ItemSuggestion(String slot, String kind, String reason, EquippedItem current, String target,
                                 String icon, double diff, double usage, Double priceChaos, String priceToken,
                                 String tradeUrl, List<ModCheck> mods, Double minEleRes, Double minChaosRes,
                                 List<String> missingMods) {
    }

    /** Gemma di supporto "core" che manca nel gruppo della skill principale. */
    public record GemSuggestion(String name, double pct, Double priceChaos) {
    }

    /** Pantheon del giocatore e quello più usato dall'archetipo (null se diverso non serve). */
    public record Pantheon(String playerMajor, String playerMinor, String major, double majorPct,
                           String minor, double minorPct) {

        public boolean majorDifferent() {
            return major != null && !major.equalsIgnoreCase(playerMajor == null ? "" : playerMajor);
        }

        public boolean minorDifferent() {
            return minor != null && !minor.equalsIgnoreCase(playerMinor == null ? "" : playerMinor);
        }
    }

    /** Semaforo per area: "ok", "warn" o "bad" (oppure "na" se non valutabile). */
    public record Summary(String defence, String gems, String items, String tree) {
    }
}
