package it.poemeta.web;

import it.poemeta.analysis.AnalysisService;
import it.poemeta.analysis.DemandService;
import it.poemeta.analysis.DemandService.DemandItem;
import it.poemeta.analysis.TrendService;
import it.poemeta.economy.EconomyService;
import it.poemeta.economy.EconomyService.Period;
import it.poemeta.economy.EconomyService.Snapshot;
import it.poemeta.economy.PriceLine;
import it.poemeta.league.League;
import it.poemeta.links.TradeLinks;
import it.poemeta.persistence.BuildAnalysisEntity;
import it.poemeta.persistence.FavoriteComboEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class MarketController {

    private final CurrentLeague currentLeague;
    private final EconomyService economy;
    private final DemandService demand;
    private final AnalysisService analyses;
    private final TradeLinks trade;
    private final Fmt fmt;
    private final TrendService trendService;

    public MarketController(CurrentLeague currentLeague, EconomyService economy, DemandService demand,
                            AnalysisService analyses, TradeLinks trade, Fmt fmt, TrendService trendService) {
        this.trendService = trendService;
        this.currentLeague = currentLeague;
        this.economy = economy;
        this.demand = demand;
        this.analyses = analyses;
        this.trade = trade;
        this.fmt = fmt;
    }

    /** Pagina iniziale "Novità". */
    @GetMapping("/")
    public String home(HttpServletRequest req, Model model) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        if (league.hasEconomy()) {
            String eco = league.economyName();
            Snapshot snap = economy.snapshot(eco);
            model.addAttribute("snapshot", snap);
            model.addAttribute("noUniquePrices", noUniquePrices(snap));
            model.addAttribute("currencyUp", economy.movers(eco, economy.currencyFilter(), Period.DAY, true, 8));
            model.addAttribute("currencyDown", economy.movers(eco, economy.currencyFilter(), Period.DAY, false, 8));
            model.addAttribute("uniquesUp", economy.movers(eco, economy.uniqueFilter(), Period.WEEK, true, 8));
        }
        List<FavoriteRow> favorites = new ArrayList<>();
        for (FavoriteComboEntity f : analyses.favorites(league.url())) {
            BuildAnalysisEntity last = analyses.latestDone(league.url(), f.getAscendancy(), f.getSkill()).orElse(null);
            favorites.add(new FavoriteRow(f.getAscendancy(), f.getSkill(), last,
                    last == null ? null : demand.trend(last),
                    BuildsController.viewUrl(f.getAscendancy(), f.getSkill())));
        }
        model.addAttribute("favorites", favorites);
        model.addAttribute("demand", demand.demand(league.url()).stream().limit(6).toList());
        model.addAttribute("activeAnalyses", analyses.activeIn(league.url()));
        return "index";
    }

    /**
     * poe.ninja non ha i prezzi degli unique per alcune leghe (per esempio gli eventi):
     * le sezioni degli unique restano vuote e lo diciamo invece di mostrare un riquadro vuoto.
     */
    static boolean noUniquePrices(Snapshot s) {
        return s != null && s.lines().stream().noneMatch(PriceLine::isUnique);
    }

    public record FavoriteRow(String ascendancy, String skill, BuildAnalysisEntity last, Double trend, String url) {
    }

    @GetMapping("/currency")
    public String currency(HttpServletRequest req, Model model, @RequestParam(defaultValue = "day") String period) {
        return movers(req, model, period, true);
    }

    @GetMapping("/uniques")
    public String uniques(HttpServletRequest req, Model model, @RequestParam(defaultValue = "week") String period,
                          @RequestParam(required = false) String cat) {
        return movers(req, model, period, false, cat);
    }

    /** Categorie degli unique su poe.ninja, con la chiave usata nell'URL (?cat=weapon). */
    static final Map<String, String> UNIQUE_CATEGORIES = new java.util.LinkedHashMap<>();

    static {
        UNIQUE_CATEGORIES.put("weapon", "UniqueWeapon");
        UNIQUE_CATEGORIES.put("armour", "UniqueArmour");
        UNIQUE_CATEGORIES.put("accessory", "UniqueAccessory");
        UNIQUE_CATEGORIES.put("flask", "UniqueFlask");
        UNIQUE_CATEGORIES.put("jewel", "UniqueJewel");
    }

    private String movers(HttpServletRequest req, Model model, String periodParam, boolean currency) {
        return movers(req, model, periodParam, currency, null);
    }

    private String movers(HttpServletRequest req, Model model, String periodParam, boolean currency, String catParam) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        Period period = "week".equals(periodParam) ? Period.WEEK : Period.DAY;
        model.addAttribute("period", period == Period.WEEK ? "week" : "day");
        model.addAttribute("currencyPage", currency);
        String category = currency || catParam == null ? null : UNIQUE_CATEGORIES.get(catParam);
        model.addAttribute("cat", category == null ? "all" : catParam);
        model.addAttribute("categories", UNIQUE_CATEGORIES.keySet());
        if (!league.hasEconomy()) {
            return "movers";
        }
        String eco = league.economyName();
        Snapshot s = economy.snapshot(eco);
        model.addAttribute("snapshot", s);
        model.addAttribute("noUniquePrices", !currency && noUniquePrices(s));
        model.addAttribute("refreshing", economy.isRefreshing(eco));
        java.util.function.Predicate<PriceLine> filter = currency ? economy.currencyFilter() : economy.uniqueFilter();
        if (category != null) {
            filter = filter.and(l -> category.equals(l.category()));
        }
        List<PriceLine> up = economy.movers(eco, filter, period, true, 40);
        List<PriceLine> down = economy.movers(eco, filter, period, false, 40);
        model.addAttribute("up", up);
        model.addAttribute("down", down);
        Map<String, String> spark = new HashMap<>();
        Map<String, String> tradeUrls = new HashMap<>();
        for (List<PriceLine> list : List.of(up, down)) {
            for (PriceLine l : list) {
                String key = EconomyService.historyKey(l);
                spark.put(key, fmt.historySparkline(economy.history(eco, l, Duration.ofDays(30))));
                if (l.isUnique()) {
                    tradeUrls.put(key, trade.unique(eco, l.name()));
                }
            }
        }
        model.addAttribute("spark", spark);
        model.addAttribute("tradeUrls", tradeUrls);
        return "movers";
    }

    /** Tendenze: unique sempre più (o meno) usati nelle build analizzate, con il prezzo ancora fermo. */
    @GetMapping("/trends")
    public String trends(HttpServletRequest req, Model model) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        String eco = league.economyName();
        Snapshot snap = eco == null ? null : economy.snapshot(eco);
        model.addAttribute("noUniquePrices", noUniquePrices(snap));
        TrendService.Trends t = trendService.trends(league.url(), eco);
        model.addAttribute("trends", t);
        Map<String, String> tradeUrls = new HashMap<>();
        if (eco != null) {
            for (List<TrendService.UniqueTrend> list : List.of(t.buy(), t.sell())) {
                list.forEach(u -> tradeUrls.put(u.name(), trade.unique(eco, u.name())));
            }
        }
        model.addAttribute("tradeUrls", tradeUrls);
        return "trends";
    }

    @GetMapping("/search")
    public String search(HttpServletRequest req, Model model, @RequestParam(required = false) String q) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        model.addAttribute("q", q == null ? "" : q);
        if (league.hasEconomy() && q != null && !q.isBlank()) {
            String eco = league.economyName();
            model.addAttribute("snapshot", economy.snapshot(eco));
            List<PriceLine> results = economy.search(eco, q, 40);
            Map<String, String> spark = new HashMap<>();
            for (PriceLine l : results) {
                if (!l.isGem()) {
                    spark.put(EconomyService.historyKey(l),
                            fmt.historySparkline(economy.history(eco, l, Duration.ofDays(30))));
                }
            }
            model.addAttribute("results", results);
            model.addAttribute("spark", spark);
        }
        return "search";
    }

    @GetMapping("/demand")
    public String demand(HttpServletRequest req, Model model) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        List<DemandItem> items = demand.demand(league.url()).stream().limit(150).toList();
        Map<String, TradeLinks.TradeLink> tradeUrls = new HashMap<>();
        if (league.hasEconomy()) {
            for (DemandItem d : items) {
                tradeUrls.put(d.key(), trade.similarRares(league.economyName(), d.baseType(), d.mods()));
            }
        }
        model.addAttribute("items", items);
        model.addAttribute("tradeUrls", tradeUrls);
        model.addAttribute("analysedCombos", analyses.doneIn(league.url()).stream()
                .map(a -> a.getAscendancy() + "|" + a.getSkill()).distinct().count());
        return "demand";
    }
}
