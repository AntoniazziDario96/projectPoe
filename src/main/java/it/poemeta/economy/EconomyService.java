package it.poemeta.economy;

import it.poemeta.config.AppProperties;
import it.poemeta.league.League;
import it.poemeta.league.LeagueService;
import it.poemeta.ninja.NinjaEconomyClient;
import it.poemeta.persistence.PriceSnapshotEntity;
import it.poemeta.persistence.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Prezzi del mercato per lega, tenuti in memoria e aggiornati periodicamente da poe.ninja.
 * Ogni ora (configurabile) salva anche uno snapshot nel database per lo storico.
 */
@Service
public class EconomyService {

    private static final Logger log = LoggerFactory.getLogger(EconomyService.class);

    public record Snapshot(Instant fetchedAt, List<PriceLine> lines, String error) {
    }

    /** Periodo per le classifiche. */
    public enum Period { DAY, WEEK }

    private final NinjaEconomyClient client;
    private final LeagueService leagues;
    private final PriceSnapshotRepository history;
    private final AppProperties props;

    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    /** Leghe visualizzate di recente: vengono aggiornate in automatico. */
    private final Map<String, Instant> watched = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "economy-refresh");
        t.setDaemon(true);
        return t;
    });

    public EconomyService(NinjaEconomyClient client, LeagueService leagues,
                          PriceSnapshotRepository history, AppProperties props) {
        this.client = client;
        this.leagues = leagues;
        this.history = history;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        executor.submit(() -> leagues.defaultLeague()
                .filter(League::hasEconomy)
                .ifPresent(l -> requestRefresh(l.economyName())));
    }

    /**
     * Restituisce i prezzi della lega. Se non ci sono o sono vecchi avvia un aggiornamento
     * in background; può quindi restituire null al primo accesso.
     */
    public Snapshot snapshot(String economyLeague) {
        if (economyLeague == null) {
            return null;
        }
        watched.put(economyLeague, Instant.now());
        Snapshot s = snapshots.get(economyLeague);
        if (s == null || isStale(s)) {
            requestRefresh(economyLeague);
        }
        return s;
    }

    public boolean isRefreshing(String economyLeague) {
        return economyLeague != null && refreshing.contains(economyLeague);
    }

    public void requestRefresh(String economyLeague) {
        if (refreshing.add(economyLeague)) {
            executor.submit(() -> {
                try {
                    refresh(economyLeague);
                } finally {
                    refreshing.remove(economyLeague);
                }
            });
        }
    }

    private boolean isStale(Snapshot s) {
        return Instant.now().isAfter(s.fetchedAt().plus(Duration.ofMinutes(props.economy().refreshMinutes())));
    }

    private void refresh(String league) {
        List<PriceLine> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String type : NinjaEconomyClient.CURRENCY_TYPES) {
            try {
                lines.addAll(client.currencyOverview(league, type));
            } catch (RuntimeException e) {
                errors.add(type + ": " + e.getMessage());
            }
        }
        for (String type : NinjaEconomyClient.ITEM_TYPES) {
            try {
                lines.addAll(client.itemOverview(league, type));
            } catch (RuntimeException e) {
                errors.add(type + ": " + e.getMessage());
            }
        }
        String error = errors.isEmpty() ? null : String.join("; ", errors);
        if (error != null) {
            log.warn("Prezzi {} caricati con errori: {}", league, error);
        }
        if (lines.isEmpty() && snapshots.containsKey(league)) {
            return; // teniamo i dati vecchi piuttosto che niente
        }
        Instant now = Instant.now();
        snapshots.put(league, new Snapshot(now, List.copyOf(lines), error));
        log.info("Prezzi aggiornati per {}: {} righe", league, lines.size());
        saveHistory(league, lines, now);
    }

    private void saveHistory(String league, List<PriceLine> lines, Instant now) {
        Optional<PriceSnapshotEntity> last = history.findTopByLeagueOrderByTakenAtDesc(league);
        Duration every = Duration.ofMinutes(props.economy().historyEveryMinutes());
        if (last.isPresent() && last.get().getTakenAt().plus(every).isAfter(now.plusSeconds(60))) {
            return;
        }
        List<PriceSnapshotEntity> rows = lines.stream()
                .filter(this::worthStoring)
                .map(l -> new PriceSnapshotEntity(league, l.category(), historyKey(l), l.chaos(), now))
                .toList();
        history.saveAll(rows);
    }

    private boolean worthStoring(PriceLine l) {
        if (l.isCurrencyLike()) {
            return true;
        }
        if (l.isUnique()) {
            return l.links() == null && l.chaos() >= props.economy().minChaosUnique();
        }
        return false;
    }

    /** Chiave per lo storico: per i unique con link include i link. */
    public static String historyKey(PriceLine l) {
        return l.links() != null ? l.name() + " (" + l.links() + "L)" : l.name();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void scheduledRefresh() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        watched.forEach((league, lastSeen) -> {
            if (lastSeen.isBefore(cutoff)) {
                watched.remove(league);
                return;
            }
            Snapshot s = snapshots.get(league);
            if (s == null || isStale(s)) {
                requestRefresh(league);
            }
        });
    }

    @Scheduled(cron = "0 17 4 * * *")
    public void purgeHistory() {
        int removed = history.deleteOlderThan(Instant.now().minus(Duration.ofDays(props.economy().historyDays())));
        if (removed > 0) {
            log.info("Rimossi {} prezzi storici vecchi", removed);
        }
    }

    // ------------------------------------------------------------------ query

    public List<PriceLine> movers(String league, Predicate<PriceLine> filter, Period period,
                                  boolean rising, int limit) {
        Snapshot s = snapshot(league);
        if (s == null) {
            return List.of();
        }
        Comparator<PriceLine> cmp = Comparator.comparingDouble(l -> change(l, period));
        if (rising) {
            cmp = cmp.reversed();
        }
        return s.lines().stream()
                .filter(filter)
                .filter(l -> change(l, period) != 0 && !Double.isNaN(change(l, period)))
                .filter(l -> rising ? change(l, period) > 0 : change(l, period) < 0)
                .sorted(cmp)
                .limit(limit)
                .toList();
    }

    public Predicate<PriceLine> currencyFilter() {
        return l -> l.isCurrencyLike()
                && !l.lowConfidence()
                && l.listings() >= props.economy().minListings()
                && l.chaos() >= props.economy().minChaosCurrency();
    }

    public Predicate<PriceLine> uniqueFilter() {
        return l -> l.isUnique() && l.links() == null
                && !l.lowConfidence()
                && l.listings() >= props.economy().minListings()
                && l.chaos() >= props.economy().minChaosUnique();
    }

    public static double change(PriceLine l, Period period) {
        Double v = period == Period.DAY ? l.change24h() : l.change7d();
        return v == null ? Double.NaN : v;
    }

    public List<PriceLine> search(String league, String query, int limit) {
        Snapshot s = snapshot(league);
        if (s == null || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        return s.lines().stream()
                .filter(l -> l.name().toLowerCase(Locale.ROOT).contains(q)
                        || (l.baseType() != null && l.baseType().toLowerCase(Locale.ROOT).contains(q)))
                .filter(l -> !l.isGem() || l.variant() == null || !l.variant().endsWith("c"))
                .sorted(Comparator.<PriceLine>comparingInt(l -> l.name().toLowerCase(Locale.ROOT).equals(q) ? 0
                                : l.name().toLowerCase(Locale.ROOT).startsWith(q) ? 1 : 2)
                        .thenComparing(Comparator.comparingInt(PriceLine::listings).reversed()))
                .limit(limit)
                .toList();
    }

    public Optional<Double> divinePrice(String league) {
        return currencyPrice(league, "Divine Orb");
    }

    public Optional<Double> currencyPrice(String league, String name) {
        Snapshot s = snapshots.get(league);
        if (s == null) {
            return Optional.empty();
        }
        return s.lines().stream().filter(l -> l.isCurrencyLike() && l.name().equalsIgnoreCase(name))
                .map(PriceLine::chaos).findFirst();
    }

    /** Prezzo più basso del unique (senza link speciali). */
    public Optional<PriceLine> uniquePrice(String league, String name) {
        Snapshot s = snapshots.get(league);
        if (s == null || name == null) {
            return Optional.empty();
        }
        return s.lines().stream()
                .filter(l -> l.isUnique() && l.links() == null && l.name().equalsIgnoreCase(name))
                .min(Comparator.comparingDouble(PriceLine::chaos));
    }

    /** Prezzo indicativo di una gemma: la variante non corrotta più economica con abbastanza offerte. */
    public Optional<PriceLine> gemPrice(String league, String name) {
        Snapshot s = snapshots.get(league);
        if (s == null || name == null) {
            return Optional.empty();
        }
        List<PriceLine> candidates = s.lines().stream()
                .filter(l -> l.isGem() && l.name().equalsIgnoreCase(name))
                .filter(l -> l.variant() == null || !l.variant().endsWith("c"))
                .toList();
        return candidates.stream().filter(l -> l.listings() >= 5)
                .min(Comparator.comparingDouble(PriceLine::chaos))
                .or(() -> candidates.stream().min(Comparator.comparingDouble(PriceLine::chaos)));
    }

    public List<PriceSnapshotEntity> history(String league, PriceLine line, Duration window) {
        return history.findByLeagueAndCategoryAndNameAndTakenAtAfterOrderByTakenAtAsc(
                league, line.category(), historyKey(line), Instant.now().minus(window));
    }
}
