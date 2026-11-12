package it.poemeta.links;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.poemeta.analysis.ModText;
import it.poemeta.ninja.RateLimitedHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Costruisce i link al trade ufficiale (pathofexile.com/trade) con una ricerca già compilata:
 * stessa base, item raro e TUTTI i mod del profilo (ognuno come explicit, crafted o fractured).
 *
 * <p>Per tradurre il testo dei mod negli id del trade scarica una sola volta l'elenco
 * pubblico delle statistiche (api/trade/data/stats). Se non è raggiungibile il link
 * cerca comunque la base, senza filtri sui mod.
 */
@Component("trade")
public class TradeLinks {

    private static final Logger log = LoggerFactory.getLogger(TradeLinks.class);
    private static final String STATS_URL = "https://www.pathofexile.com/api/trade/data/stats";
    private static final String TRADE_URL = "https://www.pathofexile.com/trade/search/";

    private final RateLimitedHttp http;
    private final ObjectMapper mapper;
    private volatile Map<String, List<String>> statIds;
    private volatile Instant lastAttempt = Instant.EPOCH;

    public TradeLinks(RateLimitedHttp http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Link al trade per item gialli simili.
     *
     * @param league   nome della lega (es. "Mirage")
     * @param baseType base dell'item (es. "Hubris Circlet"), può essere null
     * @param mods     mod normalizzati ("+# to maximum Life")
     */
    public TradeLink similarRares(String league, String baseType, List<String> mods) {
        Map<String, List<String>> ids = ids();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode query = root.putObject("query");
        query.putObject("status").put("option", "online");
        if (baseType != null && !baseType.isBlank()) {
            query.put("type", baseType);
        }
        ArrayNode stats = query.putArray("stats");
        List<String> searched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String mod : mods) {
            List<String> alternatives = ids.getOrDefault(mod, ids.get(mod + " (Local)"));
            if (alternatives == null || alternatives.isEmpty()) {
                missing.add(mod);
                continue;
            }
            // ogni mod è obbligatorio: un gruppo "almeno 1 di" con le varianti explicit / crafted / fractured
            ObjectNode group = stats.addObject();
            group.put("type", "count");
            group.putObject("value").put("min", 1);
            ArrayNode fs = group.putArray("filters");
            alternatives.forEach(id -> fs.addObject().put("id", id));
            searched.add(mod);
        }
        if (searched.isEmpty()) {
            stats.addObject().put("type", "and").putArray("filters");
        }
        query.putObject("filters").putObject("type_filters").putObject("filters")
                .putObject("rarity").put("option", "rare");
        root.putObject("sort").put("price", "asc");
        return new TradeLink(TRADE_URL + pathEncode(league) + "?q=" + pathEncode(root.toString()),
                List.copyOf(searched), List.copyOf(missing));
    }

    /**
     * Link al trade con l'elenco dei mod cercati e di quelli che il trade non permette di cercare
     * (es. testi che non hanno una statistica corrispondente).
     */
    public record TradeLink(String url, List<String> searchedMods, List<String> missingMods) {
    }

    /** Link al trade per un unique per nome. */
    public String unique(String league, String name) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode query = root.putObject("query");
        query.putObject("status").put("option", "online");
        query.put("name", name);
        root.putObject("sort").put("price", "asc");
        return TRADE_URL + pathEncode(league) + "?q=" + pathEncode(root.toString());
    }

    /** Tipi di mod accettati: lo stesso testo può essere explicit, crafted (bench) o fractured. */
    private static final List<String> MOD_TYPES = List.of("explicit", "crafted", "fractured");

    /** Testo normalizzato del mod -> id del trade (uno per tipo: explicit, crafted, fractured). */
    private Map<String, List<String>> ids() {
        Map<String, List<String>> current = statIds;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (statIds != null) {
                return statIds;
            }
            if (Instant.now().isBefore(lastAttempt.plus(Duration.ofMinutes(30)))) {
                return Map.of();
            }
            lastAttempt = Instant.now();
            try {
                JsonNode root = http.getJson(STATS_URL);
                Map<String, List<String>> map = new HashMap<>();
                for (JsonNode group : root.path("result")) {
                    for (JsonNode e : group.path("entries")) {
                        String type = e.path("type").asText();
                        if (MOD_TYPES.contains(type)) {
                            List<String> list = map.computeIfAbsent(
                                    ModText.normalize(e.path("text").asText()), k -> new ArrayList<>());
                            String id = e.path("id").asText();
                            // un solo id per tipo (esistono testi duplicati con id diversi)
                            if (list.stream().noneMatch(x -> x.startsWith(type + "."))) {
                                list.add(id);
                            }
                        }
                    }
                }
                Map<String, List<String>> frozen = new HashMap<>();
                map.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
                statIds = Map.copyOf(frozen);
                log.info("Caricate {} statistiche del trade", map.size());
                return statIds;
            } catch (RuntimeException e) {
                log.warn("Statistiche del trade non disponibili ({}): i link useranno solo la base", e.getMessage());
                return Map.of();
            }
        }
    }

    private static String pathEncode(String s) {
        return URLEncoder.encode(s == null ? "Standard" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
