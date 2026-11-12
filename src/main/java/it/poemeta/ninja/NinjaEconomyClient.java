package it.poemeta.ninja;

import com.fasterxml.jackson.databind.JsonNode;
import it.poemeta.economy.PriceLine;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Legge i prezzi da poe.ninja (endpoint JSON usati dal sito stesso).
 */
@Component
public class NinjaEconomyClient {

    private static final String BASE = "https://poe.ninja/poe1/api/economy/stash/current";
    private static final String EXCHANGE = "https://poe.ninja/poe1/api/economy/exchange/current";
    private static final String POECDN = "https://web.poecdn.com";

    public static final List<String> CURRENCY_TYPES = List.of("Currency", "Fragment");
    public static final List<String> ITEM_TYPES = List.of(
            "UniqueWeapon", "UniqueArmour", "UniqueAccessory", "UniqueFlask", "UniqueJewel", "SkillGem");

    private final RateLimitedHttp http;

    public NinjaEconomyClient(RateLimitedHttp http) {
        this.http = http;
    }

    /**
     * Currency e frammenti. poe.ninja usa i dati del Currency Exchange per le leghe recenti
     * (endpoint "exchange") e i dati degli stash per quelle vecchie: proviamo il primo e,
     * se non ha dati, il secondo.
     */
    public List<PriceLine> currencyOverview(String league, String type) {
        try {
            List<PriceLine> lines = exchangeOverview(league, type);
            if (!lines.isEmpty()) {
                return lines;
            }
        } catch (NinjaException e) {
            // ricadiamo sull'endpoint degli stash
        }
        return stashCurrencyOverview(league, type);
    }

    List<PriceLine> exchangeOverview(String league, String type) {
        JsonNode root = http.getJson(EXCHANGE + "/overview?league=" + enc(league) + "&type=" + enc(type));
        Map<String, JsonNode> items = new HashMap<>();
        for (JsonNode it : root.path("items")) {
            items.put(it.path("id").asText(), it);
        }
        JsonNode core = root.path("core");
        String primary = core.path("primary").asText("chaos");
        double toChaos = 1;
        if (!"chaos".equals(primary)) {
            double rate = core.path("rates").path("chaos").asDouble(0);
            if (rate <= 0) {
                return List.of();
            }
            toChaos = rate;
        }
        List<PriceLine> out = new ArrayList<>();
        for (JsonNode l : root.path("lines")) {
            String id = l.path("id").asText();
            if ("chaos".equals(id)) {
                continue;
            }
            JsonNode item = items.get(id);
            String name = item != null ? item.path("name").asText(id) : id;
            String image = item != null ? item.path("image").asText(null) : null;
            String icon = image == null ? null : (image.startsWith("http") ? image : POECDN + image);
            JsonNode spark = l.path("sparkline");
            // il volume scambiato (in chaos) sostituisce il numero di offerte come misura di affidabilità
            int volume = (int) Math.min(Integer.MAX_VALUE, Math.round(l.path("volumePrimaryValue").asDouble(0)));
            double chaos = l.path("primaryValue").asDouble(0) * toChaos;
            out.add(new PriceLine(type, name, null, icon, chaos,
                    totalChange(spark), change24h(spark), volume, null, null, isThinMarket(chaos, volume), true));
        }
        return out;
    }

    List<PriceLine> stashCurrencyOverview(String league, String type) {
        JsonNode root = http.getJson(BASE + "/currency/overview?league=" + enc(league) + "&type=" + enc(type));
        Map<String, String> icons = new HashMap<>();
        for (JsonNode d : root.path("currencyDetails")) {
            icons.put(d.path("name").asText(), d.path("icon").asText(null));
        }
        List<PriceLine> out = new ArrayList<>();
        for (JsonNode l : root.path("lines")) {
            String name = l.path("currencyTypeName").asText();
            JsonNode spark = l.path("receiveSparkLine");
            boolean low = false;
            if (spark.path("data").isEmpty()) {
                spark = l.path("lowConfidenceReceiveSparkLine");
                low = true;
            }
            int listings = l.path("receive").path("listing_count").asInt(0);
            out.add(new PriceLine(type, name, null, icons.get(name),
                    l.path("chaosEquivalent").asDouble(0),
                    totalChange(spark), change24h(spark), listings, null, null, low, false));
        }
        return out;
    }

    public List<PriceLine> itemOverview(String league, String type) {
        JsonNode root = http.getJson(BASE + "/item/overview?league=" + enc(league) + "&type=" + enc(type));
        List<PriceLine> out = new ArrayList<>();
        for (JsonNode l : root.path("lines")) {
            JsonNode spark = l.path("sparkLine");
            boolean low = false;
            if (spark.path("data").isEmpty() && l.has("lowConfidenceSparkLine")) {
                spark = l.path("lowConfidenceSparkLine");
                low = true;
            }
            Integer links = l.hasNonNull("links") ? l.path("links").asInt() : null;
            String variant = l.hasNonNull("variant") ? l.path("variant").asText() : null;
            out.add(new PriceLine(type, l.path("name").asText(), l.path("baseType").asText(null),
                    l.path("icon").asText(null), l.path("chaosValue").asDouble(0),
                    totalChange(spark), change24h(spark), l.path("listingCount").asInt(l.path("count").asInt(0)),
                    links, variant, low, false));
        }
        return out;
    }

    /**
     * Mercato troppo sottile: il volume scambiato (in chaos) è meno di 2 volte il prezzo,
     * cioè sono passati di mano al massimo un paio di pezzi. Il prezzo probabilmente
     * viene da uno scambio isolato (es. Fragment of Terror a 25 div con 2.071 c di volume).
     */
    static boolean isThinMarket(double chaos, double volumeChaos) {
        return chaos > 0 && volumeChaos < chaos * 2;
    }

    private static Double totalChange(JsonNode spark) {
        if (spark.isMissingNode() || spark.path("data").isEmpty()) {
            return null;
        }
        return spark.path("totalChange").asDouble();
    }

    /**
     * La sparkline contiene 7 valori: variazione % rispetto a 7 giorni fa, un punto al giorno.
     * La variazione nelle ultime 24 ore si ottiene confrontando gli ultimi due punti.
     */
    static Double change24h(JsonNode spark) {
        JsonNode data = spark.path("data");
        Double last = null;
        Double prev = null;
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode v = data.get(i);
            if (v == null || v.isNull()) {
                continue;
            }
            if (last == null) {
                last = v.asDouble();
            } else {
                prev = v.asDouble();
                break;
            }
        }
        if (last == null || prev == null || 1 + prev / 100 <= 0) {
            return null;
        }
        return ((1 + last / 100) / (1 + prev / 100) - 1) * 100;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
