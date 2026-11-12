package it.poemeta.web;

import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Archetype;
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
import java.util.List;
import java.util.Optional;

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
                                int unpricedUniques, String profileUrl, String itemList) {
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
            out.add(new ArchetypeView(a, plain(a.supports(), core), uniques, gemsToBuy,
                    plain(a.keystones(), core), plain(a.masteries(), core), plain(a.bloodlines(), core),
                    rares, costChaos, costDivine, unpriced, profileUrl(league, a), itemList(r, a, core)));
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
