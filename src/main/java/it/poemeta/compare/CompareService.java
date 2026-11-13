package it.poemeta.compare;

import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.ModStat;
import it.poemeta.analysis.AnalysisResult.RareSlot;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.analysis.AnalysisResult.Tier;
import it.poemeta.analysis.CharacterProfile;
import it.poemeta.analysis.DemandService;
import it.poemeta.analysis.ModText;
import it.poemeta.compare.CompareResult.ArchetypeMatch;
import it.poemeta.compare.CompareResult.GemSuggestion;
import it.poemeta.compare.CompareResult.ItemSuggestion;
import it.poemeta.compare.CompareResult.ModCheck;
import it.poemeta.compare.CompareResult.Notice;
import it.poemeta.compare.CompareResult.Pantheon;
import it.poemeta.compare.CompareResult.Summary;
import it.poemeta.compare.ImportedCharacter.EquippedItem;
import it.poemeta.config.AppProperties;
import it.poemeta.economy.EconomyService;
import it.poemeta.economy.PriceLine;
import it.poemeta.league.League;
import it.poemeta.links.TradeLinks;
import it.poemeta.links.TradeLinks.ModFilter;
import it.poemeta.links.TradeLinks.TradeLink;
import it.poemeta.links.TradePrices;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confronta il personaggio del giocatore con l'archetipo più simile di una coppia analizzata:
 * difese, gemme, keystone, pantheon e soprattutto gli item da cambiare, con il link al trade.
 */
@Service
public class CompareService {

    /** Slot dell'equipaggiamento confrontati, nell'ordine in cui compaiono. */
    static final List<String> EQUIP_SLOTS =
            List.of("Weapon", "Offhand", "Helm", "BodyArmour", "Gloves", "Boots", "Amulet", "Ring", "Belt");
    /**
     * true se lo slot è uno di quelli dell'equipaggiamento. Lo slot può mancare (null) per gli unique
     * di cui non sappiamo dove si mettono: List.of(...).contains(null) lancerebbe un'eccezione.
     */
    static boolean isEquipSlot(String slot) {
        return slot != null && EQUIP_SLOTS.contains(slot);
    }

    /** Un unique si consiglia se lo usa almeno questa quota dei giocatori dell'archetipo. */
    static final double UNIQUE_MIN = 0.50;
    /** Un giallo si confronta se almeno questa quota dei giocatori ne usa uno in quello slot. */
    static final double RARE_SLOT_MIN = 0.40;
    /** Sotto questa differenza un giallo è considerato già buono. */
    static final double MIN_DIFF = 0.20;
    /** Sotto questo valore le resistenze sull'item non vengono richieste. */
    static final double MIN_RES_FILTER = 10;
    /** Valore in più (nel piano con budget) dei gialli che coprono anche le resistenze mancanti. */
    static final double RES_BONUS = 0.25;
    private static final String CHAOS_INOCULATION = "Chaos Inoculation";

    private final EconomyService economy;
    private final TradeLinks trade;
    private final TradePrices prices;
    private final AppProperties props;
    private final TreeData tree;
    /** Ricerche dei gialli in attesa del prezzo: token -> link (scadono dopo un'ora). */
    private final Map<String, PendingPrice> pending = new ConcurrentHashMap<>();

    private record PendingPrice(TradeLink link, Instant createdAt) {
    }

    public CompareService(EconomyService economy, TradeLinks trade, TradePrices prices, AppProperties props,
                          TreeData tree) {
        this.economy = economy;
        this.trade = trade;
        this.prices = prices;
        this.props = props;
        this.tree = tree;
    }

    /** Il link del trade associato a un token di prezzo (per la lettura asincrona del prezzo). */
    public Optional<TradeLink> pendingPrice(String token) {
        PendingPrice p = pending.get(token);
        return p == null ? Optional.empty() : Optional.of(p.link());
    }

    /**
     * @param archetypeIndex archetipo scelto dall'utente, null = il più simile
     */
    public CompareResult compare(ImportedCharacter ch, AnalysisResult analysis, League league,
                                 Integer archetypeIndex, Tier tier) {
        cleanupPending();
        CharacterProfile p = ch.profile();
        boolean keystonesKnown = "ninja".equals(ch.source()) || !p.keystones().isEmpty() || tree.available();

        List<ArchetypeMatch> matches = matches(ch, analysis, keystonesKnown);
        Archetype arch = null;
        if (archetypeIndex != null) {
            arch = analysis.archetypes().stream().filter(a -> a.index() == archetypeIndex).findFirst().orElse(null);
        }
        if (arch == null && !matches.isEmpty()) {
            int best = matches.get(0).index();
            arch = analysis.archetypes().stream().filter(a -> a.index() == best).findFirst().orElse(null);
        }
        if (arch == null) {
            return null;
        }
        boolean oldAnalysis = arch.rareSlots().stream()
                .anyMatch(r -> r.eleRes() == null || r.mods().stream().anyMatch(m -> m.values() == null))
                || arch.defence() == null;

        List<Notice> defence = defence(p, arch);
        String eco = league.economyName();
        List<ItemSuggestion> items = items(ch, arch, tier, eco, p);
        double core = props.archetypes().coreThreshold();

        // gemme
        List<Notice> gemNotices = new ArrayList<>();
        Set<String> playerSupports = lower(p.supportsOf(analysis.skill()));
        boolean hasSkill = p.skillGroups().stream()
                .anyMatch(g -> g.stream().anyMatch(x -> CharacterProfile.isSameSkill(x, analysis.skill())));
        if (!hasSkill) {
            gemNotices.add(Notice.of("bad", "compare.gems.noSkill", analysis.skill()));
        }
        List<GemSuggestion> gems = new ArrayList<>();
        for (Stat s : arch.supports()) {
            if (s.pct() >= core && !playerSupports.contains(s.name().toLowerCase(Locale.ROOT))) {
                Double price = eco == null ? null : economy.gemPrice(eco, s.name()).map(PriceLine::chaos).orElse(null);
                gems.add(new GemSuggestion(s.name(), s.pct(), price));
            }
        }
        long expected = arch.supports().stream().filter(s -> s.pct() >= 0.5).count();
        if (hasSkill && playerSupports.size() < expected) {
            gemNotices.add(Notice.of("warn", "compare.gems.fewLinks", playerSupports.size() + 1, expected + 1));
        }

        // keystone
        Set<String> playerKeystones = lower(p.keystones());
        List<String> missingKeystones = new ArrayList<>();
        if (keystonesKnown) {
            for (Stat s : arch.keystones()) {
                if (s.pct() >= core && !playerKeystones.contains(s.name().toLowerCase(Locale.ROOT))) {
                    missingKeystones.add(s.name());
                }
            }
        }

        Pantheon pantheon = pantheon(p, arch);

        Summary summary = new Summary(
                defenceLevel(p, defence),
                !hasSkill || gems.size() >= 2 ? "bad" : (gems.isEmpty() && gemNotices.isEmpty() ? "ok" : "warn"),
                items.size() >= 3 ? "bad" : (items.isEmpty() ? "ok" : "warn"),
                !keystonesKnown ? "na" : (missingKeystones.size() >= 2 ? "bad" : (missingKeystones.isEmpty() ? "ok" : "warn")));

        return new CompareResult(ch, analysis, arch, matches, tier, oldAnalysis, defence, items, gems, gemNotices,
                missingKeystones, keystonesKnown, pantheon, summary);
    }

    // ------------------------------------------------------------ archetipo più simile

    /** Peso degli item nella somiglianza con un archetipo (il resto: gemme di supporto e keystone). */
    static final double ITEM_WEIGHT = 0.70;
    static final double SUPPORT_WEIGHT = 0.20;
    static final double KEYSTONE_WEIGHT = 0.10;
    /** Elementi usati da meno di questa quota dei giocatori non contano nella somiglianza. */
    static final double RELEVANT = 0.25;

    /**
     * Somiglianza con ogni archetipo, soprattutto in base agli item: per ogni slot conta quanto è
     * comune tra i giocatori dell'archetipo la scelta del giocatore (quell'unique, oppure un giallo),
     * rispetto alla scelta più comune. Gemme di supporto e keystone pesano meno; le mastery no
     * (sono tante e molto variabili). Avere qualcosa in più dell'archetipo non abbassa il punteggio.
     */
    List<ArchetypeMatch> matches(ImportedCharacter ch, AnalysisResult analysis, boolean keystonesKnown) {
        CharacterProfile p = ch.profile();
        Set<String> supports = lower(p.supportsOf(analysis.skill()));
        Set<String> keystones = lower(p.keystones());
        List<ArchetypeMatch> out = new ArrayList<>();
        for (Archetype a : analysis.archetypes()) {
            double items = itemSimilarity(ch, a);
            double sup = coverage(supports, a.supports());
            double score;
            if (keystonesKnown) {
                double ks = keystoneSimilarity(keystones, a.keystones());
                score = ITEM_WEIGHT * items + SUPPORT_WEIGHT * sup + KEYSTONE_WEIGHT * ks;
            } else {
                score = (ITEM_WEIGHT * items + SUPPORT_WEIGHT * sup) / (ITEM_WEIGHT + SUPPORT_WEIGHT);
            }
            out.add(new ArchetypeMatch(a.index(), a.label(), score, items, a.minor()));
        }
        // le "varianti sperimentali" (gruppo misto) solo se non c'è altro
        boolean hasMain = analysis.archetypes().size() > 1;
        out.sort(Comparator.comparing((ArchetypeMatch m) -> hasMain && isLeftovers(analysis, m.index()))
                .thenComparing(Comparator.comparingDouble(ArchetypeMatch::score).reversed()));
        return out;
    }

    /**
     * Somiglianza degli item (0-1). Per ogni slot dell'equipaggiamento: quota di giocatori dell'archetipo
     * che usa la stessa scelta del giocatore (lo stesso unique, oppure un giallo), divisa per la quota
     * della scelta più comune. Così chi usa esattamente gli item più comuni arriva al 100%.
     * Gli unique fuori dall'equipaggiamento (flask, jewel) contano come uno slot in più.
     */
    static double itemSimilarity(ImportedCharacter ch, Archetype a) {
        Map<String, String> uniqueSlots = a.uniqueSlots() == null ? Map.of() : a.uniqueSlots();
        if (uniqueSlots.isEmpty()) {
            return uniqueOverlap(ch, a); // analisi vecchie: non sappiamo in che slot sono gli unique
        }
        Map<String, Double> uniquePct = new HashMap<>();
        a.uniques().forEach(s -> uniquePct.put(s.name().toLowerCase(Locale.ROOT), s.pct()));
        double total = 0;
        int slots = 0;
        for (String slot : EQUIP_SLOTS) {
            int positions = "Ring".equals(slot) ? 2 : 1;
            double best = 0;
            for (Stat u : a.uniques()) {
                if (slot.equals(uniqueSlots.get(u.name()))) {
                    best = Math.max(best, u.pct());
                }
            }
            RareSlot rs = a.rareSlots().stream().filter(r -> slot.equals(r.slot())).findFirst().orElse(null);
            double rarePct = rs == null ? 0 : rs.pctOfArchetype();
            best = Math.max(best, rarePct);
            if (best < RELEVANT) {
                continue; // nessuna scelta davvero comune: lo slot non dice nulla
            }
            List<EquippedItem> mine = ch.items().stream().filter(i -> slot.equals(i.slot())).limit(positions).toList();
            double sum = 0;
            for (EquippedItem it : mine) {
                double pct;
                if (it.isUnique()) {
                    pct = uniquePct.getOrDefault(it.name().toLowerCase(Locale.ROOT), 0.0);
                } else if (it.isRare()) {
                    pct = rarePct;
                } else {
                    pct = 0;
                }
                sum += Math.min(1, pct / best);
            }
            total += sum / positions; // uno slot vuoto conta 0
            slots++;
        }
        // flask e jewel unique usati da almeno metà dei giocatori
        Set<String> owned = new HashSet<>();
        ch.items().stream().filter(EquippedItem::isUnique).forEach(i -> owned.add(i.name().toLowerCase(Locale.ROOT)));
        double want = 0;
        double have = 0;
        for (Stat u : a.uniques()) {
            if (u.pct() >= 0.5 && !isEquipSlot(uniqueSlots.get(u.name()))) {
                want += u.pct();
                if (owned.contains(u.name().toLowerCase(Locale.ROOT))) {
                    have += u.pct();
                }
            }
        }
        if (want > 0) {
            total += have / want;
            slots++;
        }
        return slots == 0 ? uniqueOverlap(ch, a) : total / slots;
    }

    /**
     * Per le analisi senza slot degli unique: media tra quanti unique comuni dell'archetipo hai
     * (pesati per quota) e quanti dei tuoi unique sono usati anche dall'archetipo.
     */
    static double uniqueOverlap(ImportedCharacter ch, Archetype a) {
        Set<String> mine = new HashSet<>();
        ch.items().stream().filter(EquippedItem::isUnique).forEach(i -> mine.add(i.name().toLowerCase(Locale.ROOT)));
        ch.profile().uniques().forEach(u -> mine.add(u.name().toLowerCase(Locale.ROOT)));
        double recall = coverage(mine, a.uniques());
        if (mine.isEmpty()) {
            return recall;
        }
        Set<String> arch = new HashSet<>();
        a.uniques().forEach(s -> arch.add(s.name().toLowerCase(Locale.ROOT)));
        long shared = mine.stream().filter(arch::contains).count();
        return (recall + (double) shared / mine.size()) / 2;
    }

    /**
     * Quanto il giocatore copre gli elementi comuni dell'archetipo (pesati per quota). Gli elementi
     * in più del giocatore non contano. Se l'archetipo non ha elementi comuni vale 1.
     */
    static double coverage(Set<String> player, List<Stat> arch) {
        double want = 0;
        double have = 0;
        for (Stat s : arch) {
            if (s.pct() < RELEVANT) {
                continue;
            }
            want += s.pct();
            if (player.contains(s.name().toLowerCase(Locale.ROOT))) {
                have += s.pct();
            }
        }
        return want == 0 ? 1 : have / want;
    }

    /** Keystone: copertura di quelle comuni, meno una penalità per le keystone che l'archetipo non usa. */
    static double keystoneSimilarity(Set<String> player, List<Stat> arch) {
        double cov = coverage(player, arch);
        Set<String> used = new HashSet<>();
        arch.forEach(s -> used.add(s.name().toLowerCase(Locale.ROOT)));
        long foreign = player.stream().filter(k -> !used.contains(k)).count();
        return Math.max(0, cov - 0.25 * foreign);
    }

    private static boolean isLeftovers(AnalysisResult analysis, int index) {
        return analysis.archetypes().stream().anyMatch(a -> a.index() == index
                && "Varianti sperimentali".equals(a.label()));
    }

    // ------------------------------------------------------------ difese

    private List<Notice> defence(CharacterProfile p, Archetype arch) {
        List<Notice> out = new ArrayList<>();
        CharacterProfile.Defence d = p.defence();
        if (d == null) {
            out.add(Notice.of("warn", "compare.def.noStats"));
            return out;
        }
        resCheck(out, "compare.def.fire", d.fireRes(), d.fireMax());
        resCheck(out, "compare.def.cold", d.coldRes(), d.coldMax());
        resCheck(out, "compare.def.lightning", d.lightningRes(), d.lightningMax());

        AnalysisResult.Defence ad = arch.defence();
        boolean ci = p.keystones().stream().anyMatch(k -> k.equalsIgnoreCase(CHAOS_INOCULATION))
                || p.life() <= 1;
        if (!ci && ad != null && d.chaosRes() < ad.chaosRes() - 20) {
            out.add(Notice.of("warn", "compare.def.chaos", d.chaosRes(), Math.round(ad.chaosRes())));
        } else if (!ci && ad == null && d.chaosRes() < -30) {
            out.add(Notice.of("warn", "compare.def.chaosLow", d.chaosRes()));
        }
        if (ad != null && ad.suppression() >= 80 && d.suppression() < 100) {
            out.add(Notice.of("warn", "compare.def.suppression", d.suppression(), Math.round(ad.suppression())));
        }
        if (arch.avgLife() > 1000 && p.life() > 1 && p.life() < arch.avgLife() * 0.8) {
            out.add(Notice.of("warn", "compare.def.life", p.life(), Math.round(arch.avgLife())));
        }
        if (arch.avgEnergyShield() > 1000 && p.energyShield() < arch.avgEnergyShield() * 0.8) {
            out.add(Notice.of("warn", "compare.def.es", p.energyShield(), Math.round(arch.avgEnergyShield())));
        }
        if (out.isEmpty()) {
            out.add(Notice.of("ok", "compare.def.allGood"));
        }
        return out;
    }

    private static void resCheck(List<Notice> out, String key, int value, int max) {
        int cap = Math.max(75, max);
        if (value < cap) {
            out.add(Notice.of("bad", key, value, cap - value, cap));
        }
    }

    private static String defenceLevel(CharacterProfile p, List<Notice> notices) {
        if (p.defence() == null) {
            return "na";
        }
        if (notices.stream().anyMatch(n -> "bad".equals(n.level()))) {
            return "bad";
        }
        return notices.stream().anyMatch(n -> "warn".equals(n.level())) ? "warn" : "ok";
    }

    /** Resistenze elementali che mancano per arrivare al massimo, sommate. */
    static int elementalDeficit(CharacterProfile p) {
        CharacterProfile.Defence d = p.defence();
        if (d == null) {
            return 0;
        }
        return Math.max(0, Math.max(75, d.fireMax()) - d.fireRes())
                + Math.max(0, Math.max(75, d.coldMax()) - d.coldRes())
                + Math.max(0, Math.max(75, d.lightningMax()) - d.lightningRes());
    }

    // ------------------------------------------------------------ pantheon

    private static Pantheon pantheon(CharacterProfile p, Archetype arch) {
        Stat major = arch.pantheonMajor() == null || arch.pantheonMajor().isEmpty() ? null : arch.pantheonMajor().get(0);
        Stat minor = arch.pantheonMinor() == null || arch.pantheonMinor().isEmpty() ? null : arch.pantheonMinor().get(0);
        if (major == null && minor == null) {
            return null; // analisi senza dati sul pantheon
        }
        return new Pantheon(p.pantheonMajor(), p.pantheonMinor(),
                major == null ? null : major.name(), major == null ? 0 : major.pct(),
                minor == null ? null : minor.name(), minor == null ? 0 : minor.pct());
    }

    // ------------------------------------------------------------ item

    /** Un giallo da cercare, prima di costruire il link (serve sapere quanti sono per dividere il deficit). */
    private record RarePlan(String slot, String reason, EquippedItem current, RareSlot profile, double diff,
                            List<ModCheck> checks, Double tierEleRes, double currentEleRes, Double tierChaosRes,
                            double currentChaosRes) {
    }

    private List<ItemSuggestion> items(ImportedCharacter ch, Archetype arch, Tier tier, String eco,
                                       CharacterProfile p) {
        Map<String, String> uniqueSlots = arch.uniqueSlots() == null ? Map.of() : arch.uniqueSlots();
        Set<String> owned = new HashSet<>();
        ch.items().stream().filter(EquippedItem::isUnique).forEach(i -> owned.add(i.name().toLowerCase(Locale.ROOT)));
        p.uniques().forEach(u -> owned.add(u.name().toLowerCase(Locale.ROOT)));
        Set<String> archUniques = lower(arch.uniques().stream().map(Stat::name).toList());

        List<ItemSuggestion> out = new ArrayList<>();
        List<RarePlan> plans = new ArrayList<>();
        Set<String> suggestedUniques = new HashSet<>();

        for (String slot : EQUIP_SLOTS) {
            int capacity = "Ring".equals(slot) ? 2 : 1;
            List<EquippedItem> mine = new ArrayList<>(ch.items().stream().filter(i -> slot.equals(i.slot())).toList());
            List<EquippedItem> claimed = new ArrayList<>();
            int used = 0;

            // 1) unique usati dalla maggior parte dei giocatori per questo slot
            for (Stat u : arch.uniques()) {
                if (used >= capacity) {
                    break;
                }
                if (u.pct() < UNIQUE_MIN || !slot.equals(uniqueSlots.get(u.name()))) {
                    continue;
                }
                if (owned.contains(u.name().toLowerCase(Locale.ROOT))) {
                    // già equipaggiato: occupa il suo posto
                    mine.stream().filter(i -> i.isUnique() && i.name().equalsIgnoreCase(u.name())).findFirst()
                            .ifPresent(claimed::add);
                    used++;
                    continue;
                }
                EquippedItem replace = pickToReplace(mine, claimed, archUniques);
                if (replace != null) {
                    claimed.add(replace);
                }
                used++;
                suggestedUniques.add(u.name());
                out.add(uniqueSuggestion(slot, replace, u, eco));
            }

            // 2) item giallo tipico dello slot
            RareSlot rs = arch.rareSlots().stream().filter(r -> slot.equals(r.slot())).findFirst().orElse(null);
            if (rs == null || rs.pctOfArchetype() < RARE_SLOT_MIN || used >= capacity) {
                continue;
            }
            int expectedRares = "Ring".equals(slot)
                    ? Math.max(1, Math.min(capacity - used, (int) Math.round(rs.pctOfArchetype() * 2)))
                    : 1;
            List<EquippedItem> free = mine.stream().filter(i -> !claimed.contains(i)).toList();
            int evaluated = 0;
            for (EquippedItem it : free) {
                if (evaluated >= capacity - used) {
                    break;
                }
                evaluated++;
                if (it.isUnique() && archUniques.contains(it.name().toLowerCase(Locale.ROOT))) {
                    continue; // un'alternativa usata anche dagli altri giocatori
                }
                RarePlan plan = rarePlan(slot, it, rs, tier);
                if (plan != null) {
                    plans.add(plan);
                }
            }
            for (int k = evaluated; k < expectedRares; k++) {
                plans.add(rarePlan(slot, null, rs, tier)); // slot vuoto
            }
        }

        // unique "core" in slot non confrontati (flask, jewel) o di slot sconosciuto (analisi vecchie)
        for (Stat u : arch.uniques()) {
            String slot = uniqueSlots.get(u.name());
            if (u.pct() < UNIQUE_MIN || suggestedUniques.contains(u.name())
                    || owned.contains(u.name().toLowerCase(Locale.ROOT)) || isEquipSlot(slot)) {
                continue;
            }
            out.add(uniqueSuggestion(slot, null, u, eco));
        }

        // il deficit di resistenze viene diviso tra i gialli consigliati che hanno resistenze
        int deficit = elementalDeficit(p);
        long resPlans = plans.stream().filter(pl -> pl.tierEleRes() != null).count();
        double extra = resPlans == 0 ? 0 : Math.ceil((double) deficit / resPlans);
        for (RarePlan pl : plans) {
            out.add(rareSuggestion(pl, eco, extra));
        }

        out.sort(Comparator.comparing((ItemSuggestion s) -> s.priceChaos() == null)
                .thenComparing(s -> s.priceChaos() == null ? 0 : s.priceChaos())
                .thenComparing(Comparator.comparingDouble(ItemSuggestion::diff).reversed()));
        return out;
    }

    /** Quale item del giocatore sostituire: prima gli slot vuoti, poi quelli non usati dall'archetipo. */
    private static EquippedItem pickToReplace(List<EquippedItem> mine, List<EquippedItem> claimed, Set<String> archUniques) {
        EquippedItem best = null;
        int bestRank = Integer.MAX_VALUE;
        for (EquippedItem it : mine) {
            if (claimed.contains(it)) {
                continue;
            }
            int rank;
            if (!it.isUnique() && !it.isRare()) {
                rank = 0;
            } else if (it.isUnique() && !archUniques.contains(it.name().toLowerCase(Locale.ROOT))) {
                rank = 1;
            } else if (it.isRare()) {
                rank = 2;
            } else {
                rank = 3;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = it;
            }
        }
        return best;
    }

    private ItemSuggestion uniqueSuggestion(String slot, EquippedItem current, Stat u, String eco) {
        Double price = null;
        String icon = u.icon();
        String url = null;
        if (eco != null) {
            Optional<PriceLine> line = economy.uniquePrice(eco, u.name());
            price = line.map(PriceLine::chaos).orElse(null);
            if (icon == null) {
                icon = line.map(PriceLine::icon).orElse(null);
            }
            url = trade.unique(eco, u.name());
        }
        return new ItemSuggestion(slot, "unique", current == null ? "compare.reason.missingUniqueEmpty"
                : "compare.reason.missingUnique", current, u.name(), icon, u.pct(), u.pct(), price, null, url,
                List.of(), null, null, List.of(), u.pct());
    }

    private RarePlan rarePlan(String slot, EquippedItem it, RareSlot rs, Tier tier) {
        List<String> profileMods = DemandService.profileMods(rs);
        Double tierEle = rs.eleRes() == null ? null : rs.eleRes().pick(tier);
        if (tierEle != null && tierEle < MIN_RES_FILTER) {
            tierEle = null;
        }
        Double tierChaos = rs.chaosRes() == null ? null : rs.chaosRes().pick(tier);
        if (tierChaos != null && tierChaos < MIN_RES_FILTER) {
            tierChaos = null;
        }
        boolean pseudo = tierEle != null || tierChaos != null;
        List<String> itemMods = it == null ? List.of() : it.mods();
        ModText.Resists res = ModText.resists(itemMods);

        List<ModCheck> checks = new ArrayList<>();
        double total = 0;
        double failed = 0;
        for (String mod : profileMods) {
            if (pseudo && ModText.isResistance(mod)) {
                continue;
            }
            ModStat ms = rs.mods().stream().filter(m -> m.text().equals(mod)).findFirst().orElse(null);
            double weight = ms == null ? 0.5 : ms.pct();
            Double min = ms == null ? null : tierValue(ms, tier);
            Double current = null;
            for (String m : itemMods) {
                if (ModText.normalize(m).equals(mod)) {
                    current = ModText.value(m);
                    if (current == null) {
                        current = 0.0;
                    }
                    break;
                }
            }
            boolean ok = current != null && (min == null || current >= min - 1e-6);
            checks.add(new ModCheck(mod, min, current, ok, false));
            total += weight;
            if (!ok) {
                failed += weight;
            }
        }
        if (tierEle != null) {
            boolean ok = res.elemental() >= tierEle - 1e-6;
            checks.add(new ModCheck("ele", tierEle, res.elemental(), ok, true));
            total += 1;
            if (!ok) {
                failed += 1;
            }
        }
        if (tierChaos != null) {
            boolean ok = res.chaos() >= tierChaos - 1e-6;
            checks.add(new ModCheck("chaos", tierChaos, res.chaos(), ok, true));
            total += 0.5;
            if (!ok) {
                failed += 0.5;
            }
        }

        String reason;
        double diff;
        if (it == null) {
            reason = "compare.reason.empty";
            diff = 1.0;
        } else if (it.isUnique()) {
            reason = "compare.reason.uniqueNotUsed";
            diff = rs.pctOfArchetype();
        } else if (!it.isRare()) {
            reason = "compare.reason.notRare";
            diff = 1.0;
        } else {
            reason = "compare.reason.weakRare";
            diff = total == 0 ? 0 : failed / total;
            if (diff < MIN_DIFF) {
                return null;
            }
        }
        return new RarePlan(slot, reason, it, rs, Math.min(1, diff), checks, tierEle, res.elemental(), tierChaos,
                res.chaos());
    }

    /** Valore minimo del mod per il tier scelto (analisi vecchie: la media per tutti i tier). */
    static Double tierValue(ModStat m, Tier tier) {
        if (m.values() != null) {
            return m.values().pick(tier);
        }
        return m.avgValue();
    }

    private ItemSuggestion rareSuggestion(RarePlan pl, String eco, double extraEleRes) {
        RareSlot rs = pl.profile();
        String base = rs.bases().isEmpty() ? null : rs.bases().get(0).name();
        Double minEle = null;
        if (pl.tierEleRes() != null) {
            // mai meno resistenze dell'item attuale, più la parte del deficit da coprire
            minEle = Math.max(pl.tierEleRes(), pl.currentEleRes()) + extraEleRes;
        }
        Double minChaos = pl.tierChaosRes() == null ? null : Math.max(pl.tierChaosRes(), pl.currentChaosRes());
        List<ModFilter> filters = new ArrayList<>();
        for (ModCheck c : pl.checks()) {
            if (!c.resistance()) {
                filters.add(new ModFilter(c.mod(), c.min()));
            }
        }
        String url = null;
        String token = null;
        Double price = null;
        List<String> missing = List.of();
        if (eco != null) {
            TradeLink link = trade.rare(eco, base, filters, minEle, minChaos);
            url = link.url();
            missing = link.missingMods();
            Optional<TradePrices.Price> cached = prices.cached(link);
            if (cached.isPresent()) {
                price = cached.get().chaos();
            } else {
                token = UUID.randomUUID().toString();
                pending.put(token, new PendingPrice(link, Instant.now()));
            }
        }
        String icon = pl.current() != null ? pl.current().icon() : null;
        // un giallo che aiuta anche a cappare le resistenze vale di più
        double impact = pl.diff() * Math.min(1, rs.pctOfArchetype())
                + (minEle != null && extraEleRes > 0 ? RES_BONUS : 0);
        return new ItemSuggestion(pl.slot(), "rare", pl.reason(), pl.current(), base, icon, pl.diff(),
                rs.pctOfArchetype(), price, token, url, pl.checks(),
                minEle == null ? null : TradeLinksRound.down(minEle),
                minChaos == null ? null : TradeLinksRound.down(minChaos), missing, impact);
    }

    private void cleanupPending() {
        Instant limit = Instant.now().minusSeconds(3600);
        pending.values().removeIf(p -> p.createdAt().isBefore(limit));
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Stesso arrotondamento usato nei link del trade, per mostrare i valori cercati. */
    static final class TradeLinksRound {
        private TradeLinksRound() {
        }

        static double down(double v) {
            return v >= 10 ? Math.floor(v) : Math.floor(v * 10) / 10;
        }
    }
}
