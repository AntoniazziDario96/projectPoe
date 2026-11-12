package it.poemeta.ninja;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client HTTP condiviso: tutte le richieste passano da qui e vengono distanziate
 * di almeno {@code request-delay-ms}, così non sovraccarichiamo poe.ninja.
 * In caso di 429 (troppe richieste) o errori 5xx aspetta e riprova.
 */
@Component
public class RateLimitedHttp {

    private static final Logger log = LoggerFactory.getLogger(RateLimitedHttp.class);
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final Object lock = new Object();
    private long nextAllowedAt = 0;
    private long lastSlowStart = 0;

    public RateLimitedHttp(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** Oltre questa attesa richiesta dal server non aspettiamo qui dentro: lanciamo NinjaRateLimitException. */
    private static final long MAX_INLINE_WAIT_MS = 120_000;

    public JsonNode getJson(String url) {
        return getJson(url, 0);
    }

    /**
     * @param minDelayMs pausa minima prima di questa richiesta (se maggiore di request-delay-ms);
     *                   serve per gli endpoint con limiti più severi, come il dettaglio personaggio.
     */
    public JsonNode getJson(String url, long minDelayMs) {
        byte[] body = getBytes(url, minDelayMs);
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new NinjaException("Risposta non valida da " + url, e);
        }
    }

    public byte[] getBytes(String url) {
        return getBytes(url, 0);
    }

    public byte[] getBytes(String url, long minDelayMs) {
        long backoff = 5_000;
        for (int attempt = 1; ; attempt++) {
            waitTurn(minDelayMs);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", props.userAgent())
                        .header("Accept", "*/*")
                        .GET()
                        .build();
                HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = res.statusCode();
                if (status == 200) {
                    return res.body();
                }
                if (status == 404) {
                    throw new NinjaException("Non trovato (404): " + url);
                }
                boolean retryable = status == 429 || status >= 500;
                long wait = res.headers().firstValue("Retry-After")
                        .map(RateLimitedHttp::parseSeconds).orElse(backoff);
                if (status == 429 && (wait > MAX_INLINE_WAIT_MS || attempt >= MAX_ATTEMPTS)) {
                    log.warn("poe.ninja chiede una pausa di {} minuti per {}", wait / 60_000, url);
                    throw new NinjaRateLimitException("Troppe richieste a poe.ninja", wait);
                }
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw new NinjaException("HTTP " + status + " da " + url);
                }
                log.warn("HTTP {} da {}, nuovo tentativo tra {} s", status, url, wait / 1000);
                sleep(wait);
                backoff *= 2;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new NinjaException("Errore di rete verso " + url + ": " + e.getMessage(), e);
                }
                log.warn("Errore di rete verso {} ({}), riprovo", url, e.getMessage());
                sleep(backoff);
                backoff *= 2;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NinjaException("Interrotto", e);
            }
        }
    }

    /**
     * Riserva il prossimo turno: tutte le richieste sono distanziate di request-delay-ms;
     * quelle "lente" (minDelayMs &gt; 0) sono anche distanziate tra loro di minDelayMs.
     */
    private void waitTurn(long minDelayMs) {
        long delay = Math.max(250, props.requestDelayMs());
        long waitFor;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long start = Math.max(now, nextAllowedAt);
            if (minDelayMs > 0) {
                start = Math.max(start, lastSlowStart + minDelayMs);
                lastSlowStart = start;
            }
            nextAllowedAt = start + delay;
            waitFor = start - now;
        }
        if (waitFor > 0) {
            sleep(waitFor);
        }
    }

    private static long parseSeconds(String value) {
        try {
            return Long.parseLong(value.trim()) * 1000;
        } catch (NumberFormatException e) {
            return 10_000;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NinjaException("Interrotto", e);
        }
    }
}
