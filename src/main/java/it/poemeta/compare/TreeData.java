package it.poemeta.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.ninja.RateLimitedHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomi dei nodi dell'albero passivo, per tradurre gli id di un codice Path of Building
 * in keystone e mastery (poe.ninja li fornisce già per nome, PoB no).
 *
 * <p>I dati sono quelli pubblici di GGG (repository skilltree-export), scaricati una volta
 * e salvati in {@code data/passive-tree.json}; vengono riscaricati dopo una settimana.
 */
@Component
public class TreeData {

    private static final Logger log = LoggerFactory.getLogger(TreeData.class);
    private static final String URL = "https://raw.githubusercontent.com/grindinggear/skilltree-export/master/data.json";
    private static final Path CACHE = Path.of("data", "passive-tree.json");
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final RateLimitedHttp http;
    private final ObjectMapper mapper;
    private volatile Names names;
    private volatile Instant lastAttempt = Instant.EPOCH;

    private record Names(Map<Integer, String> keystones, Map<Integer, String> masteryEffects) {
    }

    public TreeData(RateLimitedHttp http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Nomi delle keystone tra i nodi allocati (vuoto se i dati dell'albero non sono disponibili). */
    public List<String> keystones(List<Integer> nodes) {
        Names n = load();
        List<String> out = new ArrayList<>();
        for (Integer id : nodes) {
            String name = n.keystones().get(id);
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    /** Testo degli effetti mastery scelti, nello stesso formato di poe.ninja. */
    public List<String> masteries(List<Integer> effectIds) {
        Names n = load();
        List<String> out = new ArrayList<>();
        for (Integer id : effectIds) {
            String text = n.masteryEffects().get(id);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    public boolean available() {
        return !load().keystones().isEmpty();
    }

    private Names load() {
        Names current = names;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (names != null) {
                return names;
            }
            try {
                byte[] data = null;
                if (Files.exists(CACHE)
                        && Files.getLastModifiedTime(CACHE).toInstant().plus(MAX_AGE).isAfter(Instant.now())) {
                    data = Files.readAllBytes(CACHE);
                } else if (Instant.now().isAfter(lastAttempt.plus(Duration.ofMinutes(30)))) {
                    lastAttempt = Instant.now();
                    try {
                        data = http.getBytes(URL);
                        Files.createDirectories(CACHE.getParent());
                        Files.write(CACHE, data);
                    } catch (RuntimeException e) {
                        log.warn("Dati dell'albero passivo non scaricati ({})", e.getMessage());
                        if (Files.exists(CACHE)) {
                            data = Files.readAllBytes(CACHE); // meglio vecchi che niente
                        }
                    }
                }
                if (data == null) {
                    return new Names(Map.of(), Map.of());
                }
                names = parse(mapper.readTree(data));
                log.info("Albero passivo: {} keystone, {} effetti mastery", names.keystones().size(),
                        names.masteryEffects().size());
                return names;
            } catch (IOException e) {
                log.warn("Dati dell'albero passivo non leggibili: {}", e.getMessage());
                return new Names(Map.of(), Map.of());
            }
        }
    }

    static Names parse(JsonNode root) {
        Map<Integer, String> keystones = new HashMap<>();
        Map<Integer, String> effects = new HashMap<>();
        root.path("nodes").properties().forEach(e -> {
            JsonNode n = e.getValue();
            if (n.path("isKeystone").asBoolean(false)) {
                keystones.put(n.path("skill").asInt(), n.path("name").asText());
            }
            for (JsonNode eff : n.path("masteryEffects")) {
                List<String> lines = new ArrayList<>();
                for (JsonNode s : eff.path("stats")) {
                    for (String line : s.asText().split("\n")) {
                        if (!line.isBlank()) {
                            lines.add(line.trim());
                        }
                    }
                }
                effects.put(eff.path("effect").asInt(), String.join(", ", lines));
            }
        });
        return new Names(Map.copyOf(keystones), Map.copyOf(effects));
    }
}
