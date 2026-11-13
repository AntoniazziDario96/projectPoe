package it.poemeta.web;

import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Member;
import it.poemeta.analysis.AnalysisResult.ModStat;
import it.poemeta.analysis.AnalysisResult.RareSlot;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.config.AppProperties;
import it.poemeta.economy.EconomyService;
import it.poemeta.economy.PriceLine;
import it.poemeta.league.League;
import it.poemeta.analysis.DemandService;
import it.poemeta.links.TradeLinks;
import it.poemeta.links.TradeLinks.TradeLink;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Prepara un risultato di analisi per la pagina: aggiunge prezzi attuali, costo stimato
 * e link al trade. I prezzi vengono calcolati al momento, quindi sono sempre aggiornati.
 */
@Service
public class BuildViewService {

    /** Le gemme sotto questo prezzo non compaiono tra le "gemme da comprare". */
    private static final double EXPENSIVE_GEM_CHAOS = 5;

    private final EconomyService economy;
    private final TradeLinks trade;
    private final AppProperties props;
    private final Fmt fmt;
    private final MessageSource messages;

    public BuildViewService(EconomyService economy, TradeLinks trade, AppProperties props, Fmt fmt,
                            MessageSource messages) {
        this.fmt = fmt;
        this.messages = messages;
        this.economy = economy;
        this.trade = trade;
        this.props = props;
    }

    public record PricedStat(String name, double pct, String icon, Double chaos, boolean core) {
    }

    /**
     * @param profileMods i mod del profilo (presenti in almeno il 40% degli item): il link al trade li richiede tutti
     */
    public record RareSlotView(String slot, int count, double pct, List<Stat> bases, List<ModStat> mods,
                               List<String> profileMods, TradeLink trade) {

        public boolean inProfile(String mod) {
            return profileMods.contains(mod);
        }
    }

    public record ArchetypeView(Archetype a, List<PricedStat> supports, List<PricedStat> uniques,
                                List<PricedStat> gemsToBuy, List<PricedStat> keystones,
                                List<PricedStat> masteries, List<PricedStat> bloodlines,
                                List<RareSlotView> rareSlots, Double costChaos, Double costDivine,
                                int unpricedUniques, String profileUrl, String itemList,
                                BudgetVersion budget, String budgetStatus) {
    }

    /**
     * Versione economica dell'archetipo: il personaggio "tipico" che spende meno in unique.
     *
     * @param cost       costo dei suoi unique ai prezzi attuali (chaos)
     * @param typicalCost quanto spende in unique il personaggio tipico a metà classifica
     * @param skips      unique comuni nell'archetipo che lui non usa (i più cari per primi)
     * @param uses       i suoi unique con il prezzo
     * @param unpriced   unique senza prezzo (non contati nel costo)
     */
    public record BudgetVersion(Member member, double cost, double typicalCost, List<PricedStat> uses,
                                List<PricedStat> skips, int unpriced, String profileUrl) {

        /** Quanto si risparmia rispetto al personaggio tipico (0-1). */
        public double saving() {
            return typicalCost <= 0 ? 0 : Math.max(0, 1 - cost / typicalCost);
        }
    }

    /** Un personaggio conta come "tipico" se non è nel quarto meno simile del gruppo. */
    static final double TYPICAL_QUANTILE = 0.25;
    /** Livelli sotto la media oltre i quali un personaggio non è considerato (build non finite). */
    static final int MAX_LEVEL_GAP = 5;
    /** Unique comuni: usati da almeno questa quota dell'archetipo. */
    static final double COMMON_UNIQUE = 0.5;

    /**
     * Trova la versione economica. Restituisce null se l'archetipo non ha i membri (analisi vecchia)
     * o se nessuno dei suoi unique ha un prezzo.
     */
    static BudgetVersion budgetVersion(Archetype a, Function<String, Double> price, String profileUrlPrefix) {
        List<Member> members = a.members();
        if (members == null || members.isEmpty()) {
            return null;
        }
        List<Double> sims = members.stream().map(Member::similarity).sorted().toList();
        double minSim = sims.get((int) Math.floor((sims.size() - 1) * TYPICAL_QUANTILE));
        List<Member> typical = members.stream()
                .filter(m -> m.similarity() >= minSim - 1e-9 && m.level() >= a.avgLevel() - MAX_LEVEL_GAP)
                .toList();
        if (typical.size() < 2) {
            typical = members;
        }
        boolean anyPrice = false;
        List<double[]> costs = new ArrayList<>(); // [costo, indice]
        for (int i = 0; i < typical.size(); i++) {
            double cost = 0;
            for (String u : typical.get(i).uniques()) {
                Double p = price.apply(u);
                if (p != null) {
                    cost += p;
                    anyPrice = true;
                }
            }
            costs.add(new double[]{cost, i});
        }
        if (!anyPrice) {
            return null;
        }
        costs.sort(Comparator.comparingDouble(c -> c[0]));
        Member cheapest = typical.get((int) costs.get(0)[1]);
        double typicalCost = costs.get(costs.size() / 2)[0];

        List<PricedStat> uses = new ArrayList<>();
        int unpriced = 0;
        Set<String> used = new HashSet<>();
        for (String u : cheapest.uniques()) {
            used.add(u.toLowerCase(Locale.ROOT));
            Double p = price.apply(u);
            if (p == null) {
                unpriced++;
            }
            uses.add(new PricedStat(u, 0, null, p, false));
        }
        uses.sort(Comparator.comparing((PricedStat s) -> s.chaos() == null ? 0 : -s.chaos()));
        List<PricedStat> skips = new ArrayList<>();
        for (Stat s : a.uniques()) {
            if (s.pct() >= COMMON_UNIQUE && !used.contains(s.name().toLowerCase(Locale.ROOT))) {
                skips.add(new PricedStat(s.name(), s.pct(), s.icon(), price.apply(s.name()), true));
            }
        }
        skips.sort(Comparator.comparing((PricedStat s) -> s.chaos() == null ? 0 : -s.chaos()));
        String url = profileUrlPrefix == null ? null
                : profileUrlPrefix + enc(cheapest.account()) + "/" + enc(cheapest.name());
        return new BudgetVersion(cheapest, costs.get(0)[0], typicalCost, uses, skips, unpriced, url);
    }

    public List<ArchetypeView> views(AnalysisResult r, League league) {
        return views(r, league, AnalysisResult.Tier.MID);
    }

    /**
     * @param tier valori minimi richiesti ai mod e alle resistenze nei link al trade dei gialli
     */
    public List<ArchetypeView> views(AnalysisResult r, League league, AnalysisResult.Tier tier) {
        String eco = league.economyName();
        Double divine = eco == null ? null : economy.divinePrice(eco).orElse(null);
        double core = props.archetypes().coreThreshold();
        List<ArchetypeView> out = new ArrayList<>();
        for (Archetype a : r.archetypes()) {
            List<PricedStat> uniques = new ArrayList<>();
            double cost = 0;
            int unpriced = 0;
            int priced = 0;
            for (Stat s : a.uniques()) {
                Double chaos = eco == null ? null
                        : economy.uniquePrice(eco, s.name()).map(PriceLine::chaos).orElse(null);
                boolean isCore = s.pct() >= core;
                uniques.add(new PricedStat(s.name(), s.pct(), s.icon(), chaos, isCore));
                if (isCore) {
                    if (chaos != null) {
                        cost += chaos;
                        priced++;
                    } else {
                        unpriced++;
                    }
                }
            }
            List<PricedStat> gemsToBuy = new ArrayList<>();
            for (Stat s : a.gems()) {
                Optional<PriceLine> p = eco == null ? Optional.empty() : economy.gemPrice(eco, s.name());
                if (p.isPresent() && p.get().chaos() >= EXPENSIVE_GEM_CHAOS) {
                    boolean isCore = s.pct() >= core;
                    gemsToBuy.add(new PricedStat(s.name(), s.pct(), p.get().icon(), p.get().chaos(), isCore));
                    if (isCore) {
                        cost += p.get().chaos();
                        priced++;
                    }
                }
            }
            List<RareSlotView> rares = new ArrayList<>();
            for (RareSlot slot : a.rareSlots()) {
                String base = slot.bases().isEmpty() ? null : slot.bases().get(0).name();
                List<String> mods = DemandService.profileMods(slot);
                TradeLink link = eco == null ? null : rareLink(eco, base, slot, mods, tier);
                rares.add(new RareSlotView(slot.slot(), slot.count(), slot.pctOfArchetype(), slot.bases(),
                        slot.mods(), mods, link));
            }
            // senza nessun prezzo disponibile (es. lega appena iniziata) il costo non ha senso
            Double costChaos = eco == null || priced == 0 ? null : cost;
            Double costDivine = costChaos == null || divine == null || divine <= 0 ? null : costChaos / divine;
            Function<String, Double> uniquePrice = name -> eco == null ? null
                    : economy.uniquePrice(eco, name).map(PriceLine::chaos).orElse(null);
            BudgetVersion budget = budgetVersion(a, uniquePrice,
                    "https://poe.ninja/poe1/builds/" + league.url() + "/character/");
            String budgetStatus = a.members() == null ? "old" : (budget == null ? "noPrices" : "ok");
            out.add(new ArchetypeView(a, plain(a.supports(), core), uniques, gemsToBuy,
                    plain(a.keystones(), core), plain(a.masteries(), core), plain(a.bloodlines(), core),
                    rares, costChaos, costDivine, unpriced, profileUrl(league, a), itemList(r, a, core),
                    budget, budgetStatus));
        }
        return out;
    }

    /** Unique con il prezzo attuale (per le statistiche su tutta la coppia). */
    public List<PricedStat> pricedUniques(List<Stat> stats, League league) {
        if (stats == null) {
            return List.of();
        }
        double core = props.archetypes().coreThreshold();
        String eco = league.economyName();
        return stats.stream().map(s -> {
            Optional<PriceLine> p = eco == null ? Optional.empty() : economy.uniquePrice(eco, s.name());
            return new PricedStat(s.name(), s.pct(), p.map(PriceLine::icon).orElse(null),
                    p.map(PriceLine::chaos).orElse(null), s.pct() >= core);
        }).toList();
    }

    /** Link al trade per il giallo tipico: mod ai valori del tier e resistenze obbligatorie. */
    private TradeLink rareLink(String eco, String base, RareSlot slot, List<String> mods, AnalysisResult.Tier tier) {
        List<TradeLinks.ModFilter> filters = new ArrayList<>();
        for (String mod : mods) {
            Double min = slot.mods().stream().filter(m -> m.text().equals(mod)).findFirst()
                    .map(m -> m.values() != null ? m.values().pick(tier) : null).orElse(null);
            filters.add(new TradeLinks.ModFilter(mod, min));
        }
        Double ele = slot.eleRes() == null ? null : slot.eleRes().pick(tier);
        Double chaos = slot.chaosRes() == null ? null : slot.chaosRes().pick(tier);
        return trade.rare(eco, base, filters, ele != null && ele >= 10 ? ele : null,
                chaos != null && chaos >= 10 ? chaos : null);
    }

    private static List<PricedStat> plain(List<Stat> stats, double core) {
        return stats.stream().map(s -> new PricedStat(s.name(), s.pct(), s.icon(), null, s.pct() >= core)).toList();
    }

    private static String profileUrl(League league, Archetype a) {
        if (a.representative() == null) {
            return null;
        }
        return "https://poe.ninja/poe1/builds/" + league.url() + "/character/"
                + enc(a.representative().account()) + "/" + enc(a.representative().name());
    }

    /** Testo pronto da copiare (es. su Discord) con unique e gemme principali. */
    private String itemList(AnalysisResult r, Archetype a, double core) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.skill()).append(" ").append(r.ascendancy()).append(" – ").append(fmt.archetype(a.label())).append('\n');
        sb.append(text("itemList.supports", "Supporti")).append(": ");
        sb.append(String.join(", ", a.supports().stream().filter(s -> s.pct() >= core)
                .map(s -> s.name().replace(" Support", "")).toList())).append('\n');
        sb.append(text("itemList.uniques", "Unique")).append(": ");
        sb.append(String.join(", ", a.uniques().stream().filter(s -> s.pct() >= core).map(Stat::name).toList()));
        if (!a.keystones().isEmpty()) {
            sb.append('\n').append(text("itemList.keystones", "Keystone")).append(": ");
            sb.append(String.join(", ", a.keystones().stream().filter(s -> s.pct() >= core).map(Stat::name).toList()));
        }
        return sb.toString();
    }

    private String text(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
