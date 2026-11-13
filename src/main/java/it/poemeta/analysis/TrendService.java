package it.poemeta.analysis;

import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.economy.EconomyService;
import it.poemeta.economy.PriceLine;
import it.poemeta.persistence.BuildAnalysisEntity;
import it.poemeta.persistence.PriceSnapshotEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tendenze: unique che i giocatori usano sempre di più (o di meno) mentre il prezzo non si è
 * ancora mosso. Confronta, per ogni build analizzata almeno due volte, quanti giocatori usano
 * ogni unique nell'ultima analisi e in quella precedente, e lo storico dei prezzi nello stesso periodo.
 */
@Service
public class TrendService {

    /** Sotto questo numero di giocatori (prima + dopo) un unique non viene considerato. */
    static final double MIN_PLAYERS = 30;
    /** Variazione minima di giocatori (%) per segnalare un unique. */
    static final double MIN_USAGE_CHANGE = 15;
    /** Variazione di prezzo (%) oltre la quale il mercato "si è già mosso". */
    static final double PRICE_MOVED = 10;
    static final int LIMIT = 30;

    private final AnalysisService analyses;
    private final EconomyService economy;

    public TrendService(AnalysisService analyses, EconomyService economy) {
        this.analyses = analyses;
        this.economy = economy;
    }

    /** Giocatori di una coppia che usano ogni unique, in due analisi successive. */
    public record PairChange(String ascendancy, String skill, Map<String, Double> before,
                             Map<String, Double> now, Instant since) {
    }

    /**
     * @param usageChange variazione % dei giocatori che lo usano
     * @param priceChange variazione % del prezzo nello stesso periodo (null se non c'è lo storico)
     * @param approxPrice true se il prezzo "prima" è stimato dalla variazione a 7 giorni di poe.ninja
     * @param builds      coppie skill + ascendancy in cui si vede il cambiamento (le più importanti)
     */
    public record UniqueTrend(String name, String icon, double playersBefore, double playersNow,
                              double usageChange, Double priceNow, Double priceChange, boolean approxPrice,
                              List<String> builds) {
    }

    /**
     * @param buy           più usati, prezzo fermo: possibili rialzi
     * @param sell          meno usati, prezzo ancora alto: possibili ribassi
     * @param pairsCompared coppie con almeno due analisi
     * @param pairsAnalysed coppie analizzate in tutto
     * @param since         analisi precedente più vecchia usata nel confronto
     */
    public record Trends(List<UniqueTrend> buy, List<UniqueTrend> sell, int pairsCompared, int pairsAnalysed,
                         Instant since) {
    }

    public record PriceInfo(String icon, Double now, Double before, boolean approx) {
    }

    public Trends trends(String league, String economyLeague) {
        List<PairChange> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BuildAnalysisEntity latest : analyses.doneIn(league)) {
            if (!seen.add(latest.getAscendancy() + "|" + latest.getSkill())) {
                continue;
            }
            Optional<BuildAnalysisEntity> previous =
                    analyses.previousDone(league, latest.getAscendancy(), latest.getSkill());
            Optional<AnalysisResult> now = analyses.result(latest);
            Optional<AnalysisResult> before = previous.flatMap(analyses::result);
            if (now.isEmpty() || before.isEmpty()) {
                continue;
            }
            boolean overall = hasOverall(now.get()) && hasOverall(before.get());
            Instant since = previous.get().getFinishedAt() != null ? previous.get().getFinishedAt()
                    : before.get().analyzedAt();
            changes.add(new PairChange(latest.getAscendancy(), latest.getSkill(),
                    uniquePlayers(before.get(), overall), uniquePlayers(now.get(), overall), since));
        }
        Instant oldest = changes.stream().map(PairChange::since).min(Comparator.naturalOrder()).orElse(null);
        Map<String, PriceInfo> priceCache = new HashMap<>();
        List<UniqueTrend> all = compute(changes, name -> priceCache.computeIfAbsent(name,
                n -> price(economyLeague, n, oldest)));
        return new Trends(
                all.stream().filter(TrendService::isBuy)
                        .sorted(Comparator.comparingDouble(TrendService::buyScore).reversed()).limit(LIMIT).toList(),
                all.stream().filter(TrendService::isSell)
                        .sorted(Comparator.comparingDouble(TrendService::sellScore).reversed()).limit(LIMIT).toList(),
                changes.size(), seen.size(), oldest);
    }

    static boolean isBuy(UniqueTrend t) {
        return t.usageChange() >= MIN_USAGE_CHANGE && t.priceChange() != null && t.priceChange() <= PRICE_MOVED;
    }

    static boolean isSell(UniqueTrend t) {
        return t.usageChange() <= -MIN_USAGE_CHANGE && t.priceChange() != null && t.priceChange() >= -PRICE_MOVED;
    }

    private static double buyScore(UniqueTrend t) {
        return t.usageChange() - t.priceChange();
    }

    private static double sellScore(UniqueTrend t) {
        return t.priceChange() - t.usageChange();
    }

    /** Somma i giocatori di tutte le coppie e calcola le variazioni di uso e prezzo. */
    static List<UniqueTrend> compute(List<PairChange> changes, java.util.function.Function<String, PriceInfo> prices) {
        Map<String, double[]> players = new HashMap<>(); // [prima, dopo]
        Map<String, Map<String, Double>> byBuild = new HashMap<>(); // unique -> build -> |differenza|
        for (PairChange c : changes) {
            Set<String> names = new HashSet<>(c.before().keySet());
            names.addAll(c.now().keySet());
            for (String name : names) {
                double b = c.before().getOrDefault(name, 0.0);
                double n = c.now().getOrDefault(name, 0.0);
                double[] acc = players.computeIfAbsent(name, k -> new double[2]);
                acc[0] += b;
                acc[1] += n;
                byBuild.computeIfAbsent(name, k -> new HashMap<>())
                        .merge(c.skill() + " · " + c.ascendancy(), n - b, Double::sum);
            }
        }
        List<UniqueTrend> out = new ArrayList<>();
        players.forEach((name, acc) -> {
            if (acc[0] + acc[1] < MIN_PLAYERS || acc[0] <= 0) {
                return; // pochi dati, oppure unique nuovo (nessun riferimento)
            }
            PriceInfo p = prices.apply(name);
            if (p == null || p.now() == null) {
                return;
            }
            double usage = (acc[1] - acc[0]) * 100 / acc[0];
            Double priceChange = p.before() == null || p.before() <= 0 ? null : (p.now() - p.before()) * 100 / p.before();
            List<String> builds = byBuild.get(name).entrySet().stream()
                    .filter(e -> Math.signum(e.getValue()) == Math.signum(acc[1] - acc[0]) && e.getValue() != 0)
                    .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> -Math.abs(e.getValue())))
                    .limit(3).map(Map.Entry::getKey).toList();
            out.add(new UniqueTrend(name, p.icon(), acc[0], acc[1], usage, p.now(), priceChange, p.approx(), builds));
        });
        return out;
    }

    /** Prezzo attuale e prezzo al momento dell'analisi precedente (dallo storico locale). */
    private PriceInfo price(String economyLeague, String name, Instant since) {
        if (economyLeague == null) {
            return null;
        }
        Optional<PriceLine> line = economy.uniquePrice(economyLeague, name);
        if (line.isEmpty()) {
            return null;
        }
        PriceLine l = line.get();
        Double before = null;
        boolean approx = false;
        if (since != null) {
            Duration window = Duration.between(since, Instant.now()).plusHours(6);
            List<PriceSnapshotEntity> history = economy.history(economyLeague, l, window);
            Instant from = since.minus(Duration.ofHours(6));
            before = history.stream().filter(h -> !h.getTakenAt().isBefore(from))
                    .findFirst()
                    .filter(h -> h.getTakenAt().isBefore(since.plus(Duration.ofHours(12))))
                    .map(PriceSnapshotEntity::getChaos).orElse(null);
            if (before == null && l.change7d() != null && Duration.between(since, Instant.now()).toDays() <= 7) {
                // niente storico locale così vecchio: stimiamo con la variazione a 7 giorni di poe.ninja
                before = l.chaos() / (1 + l.change7d() / 100);
                approx = true;
            }
        }
        return new PriceInfo(l.icon(), l.chaos(), before, approx);
    }

    private static boolean hasOverall(AnalysisResult r) {
        return r.overall() != null && r.overall().uniques() != null && !r.overall().uniques().isEmpty();
    }

    /**
     * Giocatori della coppia che usano ogni unique. Con le statistiche di poe.ninja su tutta la
     * coppia usiamo quelle, altrimenti stimiamo dagli archetipi del campione analizzato.
     */
    static Map<String, Double> uniquePlayers(AnalysisResult r, boolean overall) {
        Map<String, Double> out = new HashMap<>();
        if (overall) {
            for (Stat s : r.overall().uniques()) {
                out.merge(s.name(), s.pct() * r.comboTotal(), Double::sum);
            }
            return out;
        }
        for (Archetype a : r.archetypes()) {
            for (Stat s : a.uniques()) {
                out.merge(s.name(), s.pct() * a.share() * r.comboTotal(), Double::sum);
            }
        }
        return out;
    }
}
