package it.poemeta.analysis;

import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Defence;
import it.poemeta.analysis.AnalysisResult.Range;
import it.poemeta.analysis.AnalysisResult.ModStat;
import it.poemeta.analysis.AnalysisResult.RareSlot;
import it.poemeta.analysis.AnalysisResult.Representative;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.analysis.CharacterProfile.ItemRef;
import it.poemeta.analysis.CharacterProfile.RareItem;
import it.poemeta.config.AppProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Raggruppa i personaggi di una coppia skill + ascendancy in archetipi.
 *
 * <p>La somiglianza tra due personaggi è una media pesata di indici di Jaccard
 * (elementi in comune / elementi totali) su:
 * <ul>
 *   <li>gemme di supporto della skill principale;</li>
 *   <li>nodi dell'albero passivo;</li>
 *   <li>unique, keystone, mastery e bloodline.</li>
 * </ul>
 * Il raggruppamento è gerarchico "average linkage": si uniscono via via i due gruppi
 * più simili finché la loro somiglianza media supera la soglia.
 */
public class ArchetypeClusterer {

    /** Gruppi più piccoli di così finiscono in "Varianti sperimentali". */
    static final int MIN_CLUSTER = 3;
    private static final List<String> SLOT_ORDER =
            List.of("Weapon", "Offhand", "Helm", "BodyArmour", "Gloves", "Boots", "Amulet", "Ring", "Belt");

    private final AppProperties.Archetypes cfg;

    public ArchetypeClusterer(AppProperties.Archetypes cfg) {
        this.cfg = cfg;
    }

    private record Features(Set<String> gems, Set<Integer> tree, Set<String> items) {
    }

    public List<Archetype> cluster(List<CharacterProfile> profiles, String skill) {
        int n = profiles.size();
        if (n == 0) {
            return List.of();
        }
        List<Features> features = profiles.stream().map(p -> features(p, skill)).toList();
        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            sim[i][i] = 1;
            for (int j = i + 1; j < n; j++) {
                double s = similarity(features.get(i), features.get(j));
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }

        List<List<Integer>> clusters = agglomerate(sim, cfg.similarityThreshold());
        clusters.sort(Comparator.comparingInt((List<Integer> c) -> c.size()).reversed());

        List<List<Integer>> main = new ArrayList<>();
        List<Integer> leftovers = new ArrayList<>();
        for (List<Integer> c : clusters) {
            if (c.size() >= MIN_CLUSTER) {
                main.add(c);
            } else {
                leftovers.addAll(c);
            }
        }

        Map<String, Double> globalLabelFreq = frequencies(allIndexes(n), i -> labelPool(profiles.get(i), skill));
        List<Archetype> out = new ArrayList<>();
        for (List<Integer> c : main) {
            out.add(describe(out.size() + 1, c, profiles, sim, skill, globalLabelFreq, false));
        }
        if (!leftovers.isEmpty()) {
            out.add(describe(out.size() + 1, leftovers, profiles, sim, skill, globalLabelFreq, true));
        }
        return out;
    }

    // ------------------------------------------------------------ somiglianza

    private Features features(CharacterProfile p, String skill) {
        Set<String> items = new HashSet<>();
        p.uniques().forEach(u -> items.add("U:" + u.name()));
        p.keystones().forEach(k -> items.add("K:" + k));
        p.masteries().forEach(m -> items.add("M:" + m));
        if (p.bloodline() != null) {
            items.add("B:" + p.bloodline());
        }
        return new Features(new HashSet<>(p.supportsOf(skill)), new HashSet<>(p.passives()), items);
    }

    double similarity(Features a, Features b) {
        double wg = cfg.weightGems();
        double wt = cfg.weightTree();
        double wi = cfg.weightItems();
        double total = wg + wt + wi;
        if (total <= 0) {
            return 0;
        }
        return (wg * jaccard(a.gems(), b.gems()) + wt * jaccard(a.tree(), b.tree())
                + wi * jaccard(a.items(), b.items())) / total;
    }

    static <T> double jaccard(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        int inter = 0;
        Set<T> small = a.size() <= b.size() ? a : b;
        Set<T> large = small == a ? b : a;
        for (T x : small) {
            if (large.contains(x)) {
                inter++;
            }
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    /** Clustering gerarchico con average linkage (aggiornamento di Lance-Williams). */
    static List<List<Integer>> agglomerate(double[][] sim, double threshold) {
        int n = sim.length;
        double[][] s = new double[n][];
        for (int i = 0; i < n; i++) {
            s[i] = sim[i].clone();
        }
        List<List<Integer>> members = new ArrayList<>();
        boolean[] active = new boolean[n];
        for (int i = 0; i < n; i++) {
            List<Integer> l = new ArrayList<>();
            l.add(i);
            members.add(l);
            active[i] = true;
        }
        while (true) {
            double best = -1;
            int bi = -1;
            int bj = -1;
            for (int i = 0; i < n; i++) {
                if (!active[i]) {
                    continue;
                }
                for (int j = i + 1; j < n; j++) {
                    if (active[j] && s[i][j] > best) {
                        best = s[i][j];
                        bi = i;
                        bj = j;
                    }
                }
            }
            if (bi < 0 || best < threshold) {
                break;
            }
            int ni = members.get(bi).size();
            int nj = members.get(bj).size();
            for (int k = 0; k < n; k++) {
                if (active[k] && k != bi && k != bj) {
                    double v = (ni * s[bi][k] + nj * s[bj][k]) / (ni + nj);
                    s[bi][k] = v;
                    s[k][bi] = v;
                }
            }
            members.get(bi).addAll(members.get(bj));
            active[bj] = false;
        }
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (active[i]) {
                out.add(members.get(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ descrizione

    private Archetype describe(int index, List<Integer> c, List<CharacterProfile> profiles, double[][] sim,
                               String skill, Map<String, Double> globalLabelFreq, boolean leftovers) {
        int size = c.size();
        int total = profiles.size();

        // build rappresentativa: somiglianza media più alta verso gli altri del gruppo
        int rep = c.get(0);
        double bestAvg = -1;
        double sumAll = 0;
        for (int i : c) {
            double sum = 0;
            for (int j : c) {
                if (i != j) {
                    sum += sim[i][j];
                }
            }
            double avg = size > 1 ? sum / (size - 1) : 1;
            sumAll += avg;
            if (avg > bestAvg) {
                bestAvg = avg;
                rep = i;
            }
        }
        double cohesion = sumAll / size;
        CharacterProfile r = profiles.get(rep);
        Representative representative = new Representative(r.account(), r.name(), r.level(), r.life(),
                r.energyShield(), r.pobCode(), bestAvg);

        Map<String, String> icons = new HashMap<>();
        for (int i : c) {
            for (ItemRef u : profiles.get(i).uniques()) {
                icons.putIfAbsent(u.name(), u.icon());
            }
        }

        List<Stat> supports = stats(c, i -> new HashSet<>(profiles.get(i).supportsOf(skill)), Map.of(), 15);
        List<Stat> gems = stats(c, i -> allGems(profiles.get(i), skill), Map.of(), 25);
        List<Stat> uniques = stats(c, i -> uniqueNames(profiles.get(i)), icons, 20);
        List<Stat> keystones = stats(c, i -> new HashSet<>(profiles.get(i).keystones()), Map.of(), 10);
        List<Stat> masteries = stats(c, i -> new HashSet<>(profiles.get(i).masteries()), Map.of(), 12);
        List<Stat> bloodlines = stats(c, i -> profiles.get(i).bloodline() == null ? Set.of()
                : Set.of(profiles.get(i).bloodline()), Map.of(), 5);

        List<Stat> pantheonMajor = stats(c, i -> profiles.get(i).pantheonMajor() == null ? Set.of()
                : Set.of(profiles.get(i).pantheonMajor()), Map.of(), 4);
        List<Stat> pantheonMinor = stats(c, i -> profiles.get(i).pantheonMinor() == null ? Set.of()
                : Set.of(profiles.get(i).pantheonMinor()), Map.of(), 4);

        String label = leftovers ? "Varianti sperimentali" : label(c, profiles, skill, globalLabelFreq);
        double share = (double) size / total;
        return new Archetype(index, label, size, share, leftovers || share < cfg.minorShare(), cohesion,
                representative,
                c.stream().mapToInt(i -> profiles.get(i).level()).average().orElse(0),
                c.stream().mapToInt(i -> profiles.get(i).life()).average().orElse(0),
                c.stream().mapToInt(i -> profiles.get(i).energyShield()).average().orElse(0),
                supports, gems, uniques, keystones, masteries, bloodlines,
                rareSlots(c, profiles), defence(c, profiles), pantheonMajor, pantheonMinor,
                uniqueSlots(c, profiles));
    }

    /** Resistenze e suppression medie (solo i personaggi che hanno questi dati). */
    private static Defence defence(List<Integer> c, List<CharacterProfile> profiles) {
        double fire = 0, cold = 0, light = 0, chaos = 0, supp = 0;
        int n = 0;
        for (int i : c) {
            CharacterProfile.Defence d = profiles.get(i).defence();
            if (d == null) {
                continue;
            }
            fire += d.fireRes();
            cold += d.coldRes();
            light += d.lightningRes();
            chaos += d.chaosRes();
            supp += d.suppression();
            n++;
        }
        return n == 0 ? null : new Defence(fire / n, cold / n, light / n, chaos / n, supp / n, n);
    }

    /** Slot più comune di ogni unique, per sapere quale item del giocatore sostituisce. */
    private static Map<String, String> uniqueSlots(List<Integer> c, List<CharacterProfile> profiles) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (int i : c) {
            for (ItemRef u : profiles.get(i).uniques()) {
                if (u.slot() != null) {
                    counts.computeIfAbsent(u.name(), k -> new HashMap<>()).merge(u.slot(), 1, Integer::sum);
                }
            }
        }
        Map<String, String> out = new HashMap<>();
        counts.forEach((name, bySlot) -> out.put(name, bySlot.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null)));
        return out;
    }

    private List<Stat> stats(List<Integer> c, Function<Integer, Set<String>> extractor,
                             Map<String, String> icons, int limit) {
        return frequencies(c, extractor).entrySet().stream()
                .filter(e -> e.getValue() >= cfg.flexThreshold())
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new Stat(e.getKey(), e.getValue(), icons.get(e.getKey())))
                .toList();
    }

    private static Map<String, Double> frequencies(List<Integer> c, Function<Integer, Set<String>> extractor) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i : c) {
            for (String v : extractor.apply(i)) {
                counts.merge(v, 1, Integer::sum);
            }
        }
        Map<String, Double> out = new HashMap<>();
        counts.forEach((k, v) -> out.put(k, (double) v / c.size()));
        return out;
    }

    private static Set<String> allGems(CharacterProfile p, String skill) {
        Set<String> out = new HashSet<>();
        p.skillGroups().forEach(out::addAll);
        out.removeIf(g -> CharacterProfile.isSameSkill(g, skill));
        return out;
    }

    private static Set<String> uniqueNames(CharacterProfile p) {
        Set<String> out = new HashSet<>();
        p.uniques().forEach(u -> out.add(u.name()));
        return out;
    }

    /** Elementi tra cui scegliere il nome dell'archetipo. */
    private static Set<String> labelPool(CharacterProfile p, String skill) {
        Set<String> out = new HashSet<>();
        p.supportsOf(skill).forEach(s -> out.add(s.replace(" Support", "")));
        p.uniques().forEach(u -> out.add(u.name()));
        out.addAll(p.keystones());
        if (p.bloodline() != null) {
            out.add(p.bloodline());
        }
        return out;
    }

    /** Nome dell'archetipo: i due elementi più caratteristici rispetto al resto della coppia. */
    private String label(List<Integer> c, List<CharacterProfile> profiles, String skill,
                         Map<String, Double> global) {
        Map<String, Double> local = frequencies(c, i -> labelPool(profiles.get(i), skill));
        List<String> picked = local.entrySet().stream()
                .filter(e -> e.getValue() >= 0.5)
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) ->
                        e.getValue() - global.getOrDefault(e.getKey(), 0.0)).reversed()
                        .thenComparing(Map.Entry.<String, Double>comparingByValue().reversed()))
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();
        return picked.isEmpty() ? "Archetipo" : String.join(" · ", picked);
    }

    private List<RareSlot> rareSlots(List<Integer> c, List<CharacterProfile> profiles) {
        Map<String, List<RareItem>> bySlot = new LinkedHashMap<>();
        SLOT_ORDER.forEach(s -> bySlot.put(s, new ArrayList<>()));
        for (int i : c) {
            for (RareItem r : profiles.get(i).rares()) {
                bySlot.computeIfAbsent(r.slot(), k -> new ArrayList<>()).add(r);
            }
        }
        List<RareSlot> out = new ArrayList<>();
        bySlot.forEach((slot, items) -> {
            if (items.isEmpty()) {
                return;
            }
            int capacity = "Ring".equals(slot) ? 2 * c.size() : c.size();
            double pct = (double) items.size() / capacity;
            if (pct < cfg.flexThreshold()) {
                return;
            }
            Map<String, Integer> bases = new HashMap<>();
            Map<String, Integer> modCount = new HashMap<>();
            Map<String, double[]> modValues = new HashMap<>();
            Map<String, List<Double>> modSamples = new HashMap<>();
            List<Double> eleRes = new ArrayList<>();
            List<Double> chaosRes = new ArrayList<>();
            for (RareItem r : items) {
                ModText.Resists res = ModText.resists(r.mods());
                eleRes.add(res.elemental());
                chaosRes.add(res.chaos());
                bases.merge(r.baseType(), 1, Integer::sum);
                Set<String> seen = new HashSet<>();
                for (String mod : r.mods()) {
                    String key = ModText.normalize(mod);
                    if (!seen.add(key)) {
                        continue;
                    }
                    modCount.merge(key, 1, Integer::sum);
                    Double v = ModText.firstNumber(mod);
                    if (v != null) {
                        double[] acc = modValues.computeIfAbsent(key, k -> new double[2]);
                        acc[0] += v;
                        acc[1]++;
                    }
                    Double value = ModText.value(mod);
                    if (value != null) {
                        modSamples.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                    }
                }
            }
            int n = items.size();
            List<Stat> baseStats = bases.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> new Stat(e.getKey(), (double) e.getValue() / n, null))
                    .toList();
            List<ModStat> mods = modCount.entrySet().stream()
                    .filter(e -> (double) e.getValue() / n >= 0.15)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> {
                        double[] acc = modValues.get(e.getKey());
                        Double avg = acc == null || acc[1] == 0 ? null : acc[0] / acc[1];
                        return new ModStat(e.getKey(), (double) e.getValue() / n, avg,
                                Range.of(modSamples.get(e.getKey())));
                    })
                    .toList();
            out.add(new RareSlot(slot, n, pct, baseStats, mods, Range.of(eleRes), Range.of(chaosRes)));
        });
        return out;
    }

    private static List<Integer> allIndexes(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }
}
