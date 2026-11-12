package it.poemeta.analysis;

import it.poemeta.config.AppProperties;
import it.poemeta.league.League;
import it.poemeta.ninja.NinjaBuildsClient;
import it.poemeta.ninja.NinjaBuildsClient.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calcola l'elenco delle coppie skill + ascendancy di una lega.
 * Servono 1 richiesta per la lega + 1 per ogni ascendancy (circa 20-30 in tutto),
 * quindi il risultato viene tenuto in memoria per alcune ore.
 */
@Service
public class ComboService {

    private static final Logger log = LoggerFactory.getLogger(ComboService.class);

    /** Classi base: non sono ascendancy, le escludiamo. */
    private static final Set<String> BASE_CLASSES =
            Set.of("Scion", "Marauder", "Ranger", "Witch", "Duelist", "Templar", "Shadow");

    public record ComboList(Instant loadedAt, long leagueTotal, List<BuildCombo> combos, String error) {
    }

    /** Stato del caricamento, per mostrare l'avanzamento. */
    public record Loading(int done, int total) {
    }

    private final NinjaBuildsClient client;
    private final AppProperties props;
    private final Map<String, ComboList> cache = new ConcurrentHashMap<>();
    private final Map<String, Loading> loading = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "combo-loader");
        t.setDaemon(true);
        return t;
    });

    public ComboService(NinjaBuildsClient client, AppProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Elenco delle coppie; null se è ancora in caricamento (che viene avviato se serve). */
    public ComboList combos(League league) {
        ComboList c = cache.get(league.url());
        boolean stale = c == null
                || Instant.now().isAfter(c.loadedAt().plus(Duration.ofHours(props.builds().combosRefreshHours())));
        if (stale) {
            startLoading(league);
        }
        return c;
    }

    public Loading loading(League league) {
        return loading.get(league.url());
    }

    public void reload(League league) {
        cache.remove(league.url());
        startLoading(league);
    }

    private void startLoading(League league) {
        if (loading.putIfAbsent(league.url(), new Loading(0, 1)) != null) {
            return;
        }
        executor.submit(() -> {
            try {
                cache.put(league.url(), load(league));
            } catch (RuntimeException e) {
                log.error("Errore nel caricare le build di {}: {}", league.name(), e.getMessage());
                ComboList old = cache.get(league.url());
                cache.put(league.url(), new ComboList(Instant.now(), old == null ? 0 : old.leagueTotal(),
                        old == null ? List.of() : old.combos(), e.getMessage()));
            } finally {
                loading.remove(league.url());
            }
        });
    }

    private ComboList load(League league) {
        SearchResult all = client.search(league.snapshotVersion(), league.url(), Map.of(), null);
        Map<String, Integer> classes = all.dimensions().getOrDefault("class", Map.of());
        int min = props.builds().minCharacters();
        List<Map.Entry<String, Integer>> ascendancies = classes.entrySet().stream()
                .filter(e -> !BASE_CLASSES.contains(e.getKey()) && e.getValue() >= min)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();

        List<BuildCombo> combos = new ArrayList<>();
        int done = 0;
        for (Map.Entry<String, Integer> asc : ascendancies) {
            loading.put(league.url(), new Loading(done, ascendancies.size()));
            SearchResult r = client.search(league.snapshotVersion(), league.url(),
                    Map.of("class", asc.getKey()), null);
            r.dimensions().getOrDefault("skills", Map.of()).forEach((skill, count) -> {
                if (count >= min) {
                    combos.add(new BuildCombo(asc.getKey(), skill, count, r.total(), all.total()));
                }
            });
            done++;
        }
        combos.sort(Comparator.comparingInt(BuildCombo::count).reversed());
        log.info("Lega {}: {} coppie skill + ascendancy", league.name(), combos.size());
        return new ComboList(Instant.now(), all.total(), List.copyOf(combos), null);
    }
}
