package it.poemeta.analysis;

import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.ModStat;
import it.poemeta.analysis.AnalysisResult.RareSlot;
import it.poemeta.persistence.BuildAnalysisEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Item richiesti": profili di item gialli (slot + base + mod principali) usati dagli archetipi
 * delle coppie analizzate, ordinati per numero stimato di giocatori che li usano.
 */
@Service
public class DemandService {

    /** Un mod entra nel profilo se lo ha almeno questa quota degli item dello slot. */
    public static final double MOD_THRESHOLD = 0.40;
    /** Un item ha al massimo 6 mod (3 prefissi + 3 suffissi). */
    public static final int MAX_MODS = 6;

    /** I mod che definiscono il profilo di uno slot: quelli comuni ad almeno il 40% degli item. */
    public static List<String> profileMods(RareSlot slot) {
        return slot.mods().stream()
                .filter(m -> m.pct() >= MOD_THRESHOLD)
                .limit(MAX_MODS)
                .map(ModStat::text)
                .toList();
    }

    private final AnalysisService analyses;

    public DemandService(AnalysisService analyses) {
        this.analyses = analyses;
    }

    /** Una build (archetipo) che usa il profilo. */
    public record Usage(String ascendancy, String skill, String archetype, long analysisId, double players) {
    }

    public record DemandItem(String slot, String baseType, List<String> mods, double players,
                             Double trendPct, List<Usage> usages) {

        public String key() {
            return slot + "|" + baseType + "|" + String.join("|", new TreeSet<>(mods));
        }
    }

    public List<DemandItem> demand(String league) {
        Map<String, Mutable> byKey = new LinkedHashMap<>();
        Set<String> seenCombos = new HashSet<>();
        for (BuildAnalysisEntity a : analyses.doneIn(league)) {
            // solo l'analisi più recente di ogni coppia
            if (!seenCombos.add(a.getAscendancy() + "|" + a.getSkill())) {
                continue;
            }
            Optional<AnalysisResult> res = analyses.result(a);
            if (res.isEmpty()) {
                continue;
            }
            Double trend = trend(a);
            AnalysisResult r = res.get();
            for (Archetype arch : r.archetypes()) {
                if (arch.minor()) {
                    continue;
                }
                double archetypePlayers = r.comboTotal() * arch.share();
                for (RareSlot slot : arch.rareSlots()) {
                    if (slot.bases().isEmpty()) {
                        continue;
                    }
                    List<String> mods = profileMods(slot);
                    if (mods.isEmpty()) {
                        continue;
                    }
                    String base = slot.bases().get(0).name();
                    double players = archetypePlayers * Math.min(1, slot.pctOfArchetype())
                            * slot.bases().get(0).pct();
                    DemandItem probe = new DemandItem(slot.slot(), base, mods, 0, null, List.of());
                    Mutable m = byKey.computeIfAbsent(probe.key(), k -> new Mutable(slot.slot(), base, mods));
                    m.players += players;
                    m.usages.add(new Usage(a.getAscendancy(), a.getSkill(), arch.label(), a.getId(), players));
                    if (trend != null) {
                        m.trendWeighted += trend * players;
                        m.trendWeight += players;
                    }
                }
            }
        }
        List<DemandItem> out = new ArrayList<>();
        for (Mutable m : byKey.values()) {
            m.usages.sort(Comparator.comparingDouble(Usage::players).reversed());
            out.add(new DemandItem(m.slot, m.base, m.mods, m.players,
                    m.trendWeight > 0 ? m.trendWeighted / m.trendWeight : null, List.copyOf(m.usages)));
        }
        out.sort(Comparator.comparingDouble(DemandItem::players).reversed());
        return out;
    }

    /** Variazione % dei giocatori della coppia rispetto all'analisi precedente. */
    public Double trend(BuildAnalysisEntity latest) {
        return analyses.previousDone(latest.getLeague(), latest.getAscendancy(), latest.getSkill())
                .filter(p -> p.getComboTotal() > 0)
                .map(p -> (latest.getComboTotal() - p.getComboTotal()) * 100.0 / p.getComboTotal())
                .orElse(null);
    }

    private static final class Mutable {
        final String slot;
        final String base;
        final List<String> mods;
        final List<Usage> usages = new ArrayList<>();
        double players;
        double trendWeighted;
        double trendWeight;

        Mutable(String slot, String base, List<String> mods) {
            this.slot = slot;
            this.base = base;
            this.mods = mods;
        }
    }
}
