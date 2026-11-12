package it.poemeta.ninja;

import com.fasterxml.jackson.databind.JsonNode;
import it.poemeta.config.AppProperties;
import it.poemeta.ninja.ProtoReader.Field;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static it.poemeta.ninja.NinjaEconomyClient.enc;

/**
 * Legge le build da poe.ninja:
 * <ul>
 *   <li>stato degli indici (leghe e versioni degli snapshot) in JSON;</li>
 *   <li>ricerca dei personaggi in protobuf (max 100 righe per richiesta);</li>
 *   <li>dettaglio del singolo personaggio in JSON.</li>
 * </ul>
 */
@Component
public class NinjaBuildsClient {

    private static final String BASE = "https://poe.ninja/poe1/api";

    private final RateLimitedHttp http;
    private final AppProperties props;
    private final Map<String, NinjaDictionary> dictionaries = new ConcurrentHashMap<>();

    public NinjaBuildsClient(RateLimitedHttp http, AppProperties props) {
        this.http = http;
        this.props = props;
    }

    public record CharacterRef(String account, String name) {
    }

    /**
     * Risultato di una ricerca.
     *
     * @param total      personaggi totali che soddisfano i filtri
     * @param dimensions per ogni dimensione (class, skills, ...) il conteggio per valore
     * @param rows       i personaggi restituiti (max 100)
     */
    public record SearchResult(long total, Map<String, Map<String, Integer>> dimensions, List<CharacterRef> rows) {
    }

    public JsonNode indexState() {
        return http.getJson(BASE + "/data/index-state");
    }

    public JsonNode buildIndexState() {
        return http.getJson(BASE + "/data/build-index-state");
    }

    /**
     * Dettaglio di un personaggio. Questo endpoint ha un limite molto più severo degli altri
     * (Cloudflare blocca per un'ora dopo troppe richieste ravvicinate), quindi usa una pausa
     * dedicata (builds.character-delay-ms).
     */
    public JsonNode character(String version, String leagueUrl, String account, String name) {
        return http.getJson(BASE + "/builds/" + enc(version) + "/character?account=" + enc(account)
                + "&name=" + enc(name) + "&overview=" + enc(leagueUrl) + "&type=0&timeMachine=",
                props.builds().characterDelayMs());
    }

    public SearchResult search(String version, String leagueUrl, Map<String, String> filters, String sort) {
        StringBuilder url = new StringBuilder(BASE + "/builds/" + enc(version) + "/search?");
        filters.forEach((k, v) -> url.append(enc(k)).append('=').append(enc(v)).append('&'));
        url.append("overview=").append(enc(leagueUrl)).append("&type=exp");
        if (sort != null && !sort.isBlank()) {
            url.append("&sort=").append(enc(sort));
        }
        return parseSearch(http.getBytes(url.toString()));
    }

    SearchResult parseSearch(byte[] data) {
        Field rootField = ProtoReader.first(ProtoReader.read(data), 1);
        if (rootField == null) {
            throw new NinjaException("Ricerca poe.ninja vuota o in formato sconosciuto");
        }
        List<Field> root = rootField.message();
        long total = ProtoReader.firstVarint(root, 1);

        // dizionari: id -> hash del file NDIC
        Map<String, String> dictHashes = new HashMap<>();
        for (Field f : root) {
            if (f.number() == 6 && f.isBytes()) {
                List<Field> d = f.message();
                dictHashes.put(ProtoReader.firstString(d, 1), ProtoReader.firstString(d, 2));
            }
        }

        Map<String, Map<String, Integer>> dimensions = new LinkedHashMap<>();
        for (Field f : root) {
            if (f.number() != 2 || !f.isBytes()) {
                continue;
            }
            List<Field> dim = f.message();
            String id = ProtoReader.firstString(dim, 1);
            String dictId = ProtoReader.firstString(dim, 2);
            String hash = dictHashes.get(dictId);
            if (id == null || hash == null) {
                continue;
            }
            NinjaDictionary dict = dictionary(hash);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Field c : dim) {
                if (c.number() != 3 || !c.isBytes()) {
                    continue;
                }
                List<Field> kv = c.message();
                int key = (int) ProtoReader.firstVarint(kv, 1);
                int count = (int) ProtoReader.firstVarint(kv, 2);
                String name = dict.get(key);
                if (count > 0 && name != null) {
                    counts.merge(name, count, Integer::sum);
                }
            }
            dimensions.put(id, counts);
        }

        List<String> names = List.of();
        List<String> accounts = List.of();
        for (Field f : root) {
            if (f.number() != 12 || !f.isBytes()) {
                continue;
            }
            List<Field> col = f.message();
            String id = ProtoReader.firstString(col, 1);
            if ("name".equals(id)) {
                names = stringValues(col);
            } else if ("account".equals(id)) {
                accounts = stringValues(col);
            }
        }
        List<CharacterRef> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(names.size(), accounts.size()); i++) {
            rows.add(new CharacterRef(accounts.get(i), names.get(i)));
        }
        return new SearchResult(total, dimensions, rows);
    }

    private static List<String> stringValues(List<Field> col) {
        List<String> out = new ArrayList<>();
        for (Field f : col) {
            if (f.number() == 7 && f.isBytes()) {
                out.add(f.string());
            }
        }
        return out;
    }

    private NinjaDictionary dictionary(String hash) {
        return dictionaries.computeIfAbsent(hash,
                h -> NinjaDictionary.parse(http.getBytes(BASE + "/builds/dictionary/" + enc(h))));
    }
}
