package it.poemeta.links;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.config.AppProperties;
import it.poemeta.economy.EconomyService;
import it.poemeta.links.TradeLinks.TradeLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legge dal trade ufficiale il prezzo degli item gialli più economici che soddisfano una ricerca.
 *
 * <p>Il trade ha limiti severi (es. 5 ricerche ogni 10 secondi, 30 ogni 5 minuti) e chi li supera
 * viene bloccato fino a 30 minuti. Per questo i limiti indicati dal server nelle intestazioni
 * {@code X-Rate-Limit-Ip} vengono rispettati con un margine, i risultati restano in cache per
 * 30 minuti e, se bisognerebbe aspettare troppo, si rinuncia invece di insistere.
 */
@Component
public class TradePrices {

    private static final Logger log = LoggerFactory.getLogger(TradePrices.class);
    private static final String SEARCH_URL = "https://www.pathofexile.com/api/trade/search/";
    private static final String FETCH_URL = "https://www.pathofexile.com/api/trade/fetch/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(5);
    /** Oltre questa attesa non si aspetta: il prezzo risulta "non disponibile per ora". */
    private static final long MAX_WAIT_MS = 15_000;
    /** Quanti annunci (i più economici) si usano per stimare il prezzo. */
    private static final int SAMPLE = 5;

    /** Sigle delle currency del trade -> nome su poe.ninja. */
    private static final Map<String, String> CURRENCIES = Map.ofEntries(
            Map.entry("divine", "Divine Orb"), Map.entry("exalted", "Exalted Orb"),
            Map.entry("alch", "Orb of Alchemy"), Map.entry("fusing", "Orb of Fusing"),
            Map.entry("chrome", "Chromatic Orb"), Map.entry("alt", "Orb of Alteration"),
            Map.entry("regal", "Regal Orb"), Map.entry("vaal", "Vaal Orb"),
            Map.entry("annul", "Orb of Annulment"), Map.entry("chance", "Orb of Chance"),
            Map.entry("scour", "Orb of Scouring"), Map.entry("jewellers", "Jeweller's Orb"),
            Map.entry("gcp", "Gemcutter's Prism"), Map.entry("mirror", "Mirror of Kalandra"));

    /**
     * Prezzo stimato.
     *
     * @param chaos  prezzo in chaos (mediana dei più economici), null se non ci sono annunci
     * @param total  annunci trovati dal trade
     */
    public record Price(Double chaos, int total) {
    }

    /** Il prezzo non si può leggere adesso (limiti del trade o lega non presente sul trade). */
    public static class Unavailable extends RuntimeException {
        private final String reason;

        public Unavailable(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }

    private record Cached(Price price, String failure, long expiresAt) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final EconomyService economy;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Limiter searchLimiter = new Limiter("5:10:60,15:60:300,30:300:1800");
    private final Limiter fetchLimiter = new Limiter("12:4:60,16:12:300");

    public TradePrices(ObjectMapper mapper, AppProperties props, EconomyService economy) {
        this.mapper = mapper;
        this.props = props;
        this.economy = economy;
    }

    /** Prezzo in cache, senza fare richieste. */
    public Optional<Price> cached(TradeLink link) {
        Cached c = cache.get(key(link));
        return c != null && c.price() != null && c.expiresAt() > System.currentTimeMillis()
                ? Optional.of(c.price()) : Optional.empty();
    }

    /**
     * Prezzo degli item più economici per la ricerca del link.
     *
     * @throws Unavailable se il trade non risponde, la lega non c'è o bisognerebbe aspettare troppo
     */
    public Price price(TradeLink link) {
        String key = key(link);
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt() > now) {
            if (c.price() != null) {
                return c.price();
            }
            throw new Unavailable(c.failure());
        }
        try {
            Price p = fetchPrice(link);
            cache.put(key, new Cached(p, null, now + CACHE_TTL.toMillis()));
            return p;
        } catch (Unavailable e) {
            if (!"busy".equals(e.getReason())) {
                cache.put(key, new Cached(null, e.getReason(), now + FAILURE_TTL.toMillis()));
            }
            throw e;
        }
    }

    private Price fetchPrice(TradeLink link) {
        JsonNode search = send(searchLimiter, HttpRequest.newBuilder(URI.create(SEARCH_URL + enc(link.league())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(link.query())));
        int total = search.path("total").asInt(0);
        List<String> ids = new ArrayList<>();
        for (JsonNode id : search.path("result")) {
            if (ids.size() >= SAMPLE) {
                break;
            }
            ids.add(id.asText());
        }
        if (ids.isEmpty()) {
            return new Price(null, total);
        }
        JsonNode fetched = send(fetchLimiter, HttpRequest.newBuilder(URI.create(FETCH_URL + String.join(",", ids)
                + "?query=" + enc(search.path("id").asText()))).GET());
        List<Double> prices = new ArrayList<>();
        for (JsonNode r : fetched.path("result")) {
            JsonNode price = r.path("listing").path("price");
            Double chaos = toChaos(link.league(), price.path("amount").asDouble(0), price.path("currency").asText(""));
            if (chaos != null && chaos > 0) {
                prices.add(chaos);
            }
        }
        if (prices.isEmpty()) {
            return new Price(null, total);
        }
        prices.sort(Double::compare);
        return new Price(prices.get(prices.size() / 2), total);
    }

    /** Converte il prezzo in chaos con i prezzi della currency di poe.ninja (null se la currency è sconosciuta). */
    private Double toChaos(String league, double amount, String currency) {
        if ("chaos".equals(currency)) {
            return amount;
        }
        String name = CURRENCIES.get(currency);
        if (name == null) {
            return null;
        }
        return economy.currencyPrice(league, name).map(p -> p * amount).orElse(null);
    }

    private JsonNode send(Limiter limiter, HttpRequest.Builder builder) {
        limiter.acquire();
        try {
            HttpResponse<String> res = client.send(builder
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", props.userAgent())
                    .header("Accept", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
            limiter.update(res);
            int status = res.statusCode();
            if (status == 200) {
                return mapper.readTree(res.body());
            }
            if (status == 429) {
                throw new Unavailable("busy");
            }
            if (status == 400 || status == 404) {
                // tipicamente: lega non presente sul trade (es. leghe evento)
                log.debug("Trade HTTP {}: {}", status, res.body());
                throw new Unavailable("league");
            }
            throw new Unavailable("down");
        } catch (IOException e) {
            throw new Unavailable("down");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("down");
        }
    }

    private String key(TradeLink link) {
        return link.league() + "|" + link.query();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "Standard" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Limiti del tipo "massimo:secondi:penalità" separati da virgole, come nelle intestazioni del trade.
     * Si tiene un margine di una richiesta su ogni regola.
     */
    static final class Limiter {

        private record Rule(int max, long periodMs) {
        }

        private volatile List<Rule> rules;
        private final Deque<Long> history = new ArrayDeque<>();
        private long blockedUntil = 0;

        Limiter(String defaults) {
            this.rules = parse(defaults);
        }

        static List<Rule> parse(String header) {
            List<Rule> out = new ArrayList<>();
            for (String part : header.split(",")) {
                String[] p = part.trim().split(":");
                if (p.length >= 2) {
                    try {
                        out.add(new Rule(Integer.parseInt(p[0]), Long.parseLong(p[1]) * 1000));
                    } catch (NumberFormatException ignored) {
                        // regola non valida
                    }
                }
            }
            return out;
        }

        void acquire() {
            long wait;
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now < blockedUntil) {
                    throw new Unavailable("busy");
                }
                wait = 0;
                for (Rule r : rules) {
                    int allowed = Math.max(1, r.max() - 1);
                    List<Long> inWindow = history.stream().filter(t -> t > now - r.periodMs()).sorted().toList();
                    if (inWindow.size() >= allowed) {
                        long oldestNeeded = inWindow.get(inWindow.size() - allowed);
                        wait = Math.max(wait, oldestNeeded + r.periodMs() - now + 250);
                    }
                }
                if (wait > MAX_WAIT_MS) {
                    throw new Unavailable("busy");
                }
                history.addLast(now + wait);
                long longest = rules.stream().mapToLong(Rule::periodMs).max().orElse(600_000);
                while (!history.isEmpty() && history.peekFirst() < now - longest) {
                    history.removeFirst();
                }
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Unavailable("busy");
                }
            }
        }

        synchronized void update(HttpResponse<?> res) {
            res.headers().firstValue("X-Rate-Limit-Ip").ifPresent(h -> {
                List<Rule> parsed = parse(h);
                if (!parsed.isEmpty()) {
                    rules = parsed;
                }
            });
            if (res.statusCode() == 429) {
                long retry = res.headers().firstValue("Retry-After").map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000;
                    } catch (NumberFormatException e) {
                        return 60_000L;
                    }
                }).orElse(60_000L);
                blockedUntil = System.currentTimeMillis() + retry;
                log.warn("Il trade chiede una pausa di {} secondi", retry / 1000);
            }
        }
    }
}
