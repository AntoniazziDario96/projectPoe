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
     * Link al trade per item gialli simili, senza valori minimi.
     *
     * @param league   nome della lega (es. "Mirage")
     * @param baseType base dell'item (es. "Hubris Circlet"), può essere null
     * @param mods     mod normalizzati ("+# to maximum Life")
     */
    public TradeLink similarRares(String league, String baseType, List<String> mods) {
        return rare(league, baseType, mods.stream().map(m -> new ModFilter(m, null)).toList(), null, null);
    }

    /** Un mod richiesto, con il valore minimo (null = qualsiasi valore). */
    public record ModFilter(String mod, Double min) {
    }

    /**
     * Link al trade per un item giallo: stessa base, tutti i mod richiesti con il valore minimo
     * e, se indicate, le resistenze minime come totale "pseudo" (in quel caso i singoli mod di
     * resistenza non vengono richiesti uno per uno, così il trade trova qualsiasi combinazione).
     *
     * @param minEleRes   resistenze elementali totali minime sull'item, null = nessun filtro
     * @param minChaosRes resistenza al chaos minima sull'item, null = nessun filtro
     */
    public TradeLink rare(String league, String baseType, List<ModFilter> mods, Double minEleRes, Double minChaosRes) {
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
        boolean pseudoRes = (minEleRes != null && minEleRes > 0) || (minChaosRes != null && minChaosRes > 0);
        for (ModFilter f : mods) {
            String mod = f.mod();
            if (pseudoRes && ModText.isResistance(mod)) {
                continue; // coperto dal totale delle resistenze
            }
            // lo stesso testo può essere globale o "locale" (es. ES piatto su un casco): vanno bene entrambi
            List<String> alternatives = new ArrayList<>(ids.getOrDefault(mod, List.of()));
            alternatives.addAll(ids.getOrDefault(mod + " (Local)", List.of()));
            if (alternatives.isEmpty()) {
                missing.add(mod);
                continue;
            }
            // ogni mod è obbligatorio: un gruppo "almeno 1 di" con le varianti explicit / crafted / fractured
            ObjectNode group = stats.addObject();
            group.put("type", "count");
            group.putObject("value").put("min", 1);
            ArrayNode fs = group.putArray("filters");
            for (String id : alternatives) {
                ObjectNode filter = fs.addObject().put("id", id);
                if (f.min() != null && f.min() > 0) {
                    filter.putObject("value").put("min", roundDown(f.min()));
                }
            }
            searched.add(mod);
        }
        ObjectNode and = stats.addObject();
        and.put("type", "and");
        ArrayNode andFilters = and.putArray("filters");
        if (minEleRes != null && minEleRes > 0) {
            andFilters.addObject().put("id", PSEUDO_ELE_RES).putObject("value").put("min", roundDown(minEleRes));
        }
        if (minChaosRes != null && minChaosRes > 0) {
            andFilters.addObject().put("id", PSEUDO_CHAOS_RES).putObject("value").put("min", roundDown(minChaosRes));
        }
        query.putObject("filters").putObject("type_filters").putObject("filters")
                .putObject("rarity").put("option", "rare");
        root.putObject("sort").put("price", "asc");
        String json = root.toString();
        return new TradeLink(TRADE_URL + pathEncode(league) + "?q=" + pathEncode(json),
                List.copyOf(searched), List.copyOf(missing), league, json,
                minEleRes != null && minEleRes > 0 ? roundDown(minEleRes) : null,
                minChaosRes != null && minChaosRes > 0 ? roundDown(minChaosRes) : null);
    }

    /** Arrotonda per difetto: interi sopra 10, un decimale sotto (es. 1.5% di leech). */
    static double roundDown(double v) {
        return v >= 10 ? Math.floor(v) : Math.floor(v * 10) / 10;
    }

    private static final String PSEUDO_ELE_RES = "pseudo.pseudo_total_elemental_resistance";
    private static final String PSEUDO_CHAOS_RES = "pseudo.pseudo_total_chaos_resistance";

    /**
     * Link al trade con l'elenco dei mod cercati e di quelli che il trade non permette di cercare
     * (es. testi che non hanno una statistica corrispondente).
     *
     * @param league     lega usata nella ricerca
     * @param query      la ricerca in JSON (serve anche per leggere i prezzi dal trade)
     * @param minEleRes  resistenze elementali minime richieste, null se non filtrate
     * @param minChaosRes resistenza al chaos minima richiesta, null se non filtrata
     */
    public record TradeLink(String url, List<String> searchedMods, List<String> missingMods,
                            String league, String query, Double minEleRes, Double minChaosRes) {
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
