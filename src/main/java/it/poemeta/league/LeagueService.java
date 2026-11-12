package it.poemeta.league;

import com.fasterxml.jackson.databind.JsonNode;
import it.poemeta.config.AppProperties;
import it.poemeta.ninja.NinjaBuildsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Elenco delle leghe disponibili su poe.ninja (aggiornato ogni ora).
 */
@Service
public class LeagueService {

    private static final Logger log = LoggerFactory.getLogger(LeagueService.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final NinjaBuildsClient client;
    private final AppProperties props;

    private volatile List<League> leagues = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public LeagueService(NinjaBuildsClient client, AppProperties props) {
        this.client = client;
        this.props = props;
    }

    public synchronized List<League> all() {
        if (leagues.isEmpty() || Instant.now().isAfter(loadedAt.plus(TTL))) {
            try {
                leagues = load();
                loadedAt = Instant.now();
            } catch (RuntimeException e) {
                log.error("Impossibile caricare le leghe da poe.ninja: {}", e.getMessage());
                if (leagues.isEmpty()) {
                    return List.of();
                }
            }
        }
        return leagues;
    }

    public Optional<League> byUrl(String url) {
        if (url == null) {
            return Optional.empty();
        }
        return all().stream().filter(l -> l.url().equalsIgnoreCase(url)).findFirst();
    }

    /** Lega di default: quella configurata, altrimenti la challenge league più recente con i prezzi. */
    public Optional<League> defaultLeague() {
        List<League> list = all();
        String configured = props.defaultLeague();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            Optional<League> match = list.stream()
                    .filter(l -> l.name().equalsIgnoreCase(configured) || l.url().equalsIgnoreCase(configured))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return list.stream()
                .filter(l -> l.hasEconomy() && !l.hardcore() && !"Standard".equalsIgnoreCase(l.name()))
                .max(Comparator.comparing(League::snapshotDate))
                .or(() -> list.stream().filter(l -> "standard".equals(l.url())).findFirst())
                .or(() -> list.stream().findFirst());
    }

    private List<League> load() {
        JsonNode index = client.indexState();
        JsonNode buildIndex = client.buildIndexState();

        Map<String, String> economyByName = new HashMap<>();
        for (String key : List.of("economyLeagues", "oldEconomyLeagues")) {
            for (JsonNode l : index.path(key)) {
                economyByName.put(l.path("name").asText(), l.path("name").asText());
            }
        }

        // leghe pubbliche (category 0 = challenge, 1 = permanenti; 2 = private, escluse)
        Map<String, JsonNode> publicLeagues = new HashMap<>();
        for (JsonNode l : buildIndex.path("leagueBuilds")) {
            if (l.path("category").asInt(2) != 2) {
                publicLeagues.put(l.path("leagueUrl").asText(), l);
            }
        }

        List<League> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode s : index.path("snapshotVersions")) {
            String url = s.path("url").asText();
            if (!"exp".equals(s.path("type").asText()) || !publicLeagues.containsKey(url) || !seen.add(url)) {
                continue;
            }
            JsonNode info = publicLeagues.get(url);
            String name = info.path("leagueName").asText(s.path("name").asText());
            out.add(new League(name, url, s.path("version").asText(), economyByName.get(name),
                    info.path("hardcore").asBoolean(false) || name.toLowerCase().contains("hardcore"),
                    info.path("total").asLong(0)));
        }
        out.sort(Comparator.comparing(League::hasEconomy).reversed()
                .thenComparing(League::snapshotDate, Comparator.reverseOrder())
                .thenComparing(League::name));
        log.info("Caricate {} leghe da poe.ninja", out.size());
        return List.copyOf(out);
    }
}
