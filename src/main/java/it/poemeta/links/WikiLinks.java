package it.poemeta.links;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Link alla PoE Wiki (poewiki.net). Usato dai template come {@code @wiki.url(nome)}.
 */
@Component("wiki")
public class WikiLinks {

    private static final String BASE = "https://www.poewiki.net/wiki/";

    /** Pagina della wiki per un oggetto, gemma o currency (spazi sostituiti da "_"). */
    public String url(String name) {
        if (name == null || name.isBlank()) {
            return "https://www.poewiki.net/";
        }
        String title = name.trim().replace(' ', '_');
        return BASE + URLEncoder.encode(title, StandardCharsets.UTF_8)
                .replace("%2F", "/").replace("%27", "'").replace("+", "%20");
    }

    /** Ricerca sulla wiki: utile se il nome non corrisponde esattamente a una pagina. */
    public String search(String text) {
        return "https://www.poewiki.net/w/index.php?search="
                + URLEncoder.encode(text == null ? "" : text.trim(), StandardCharsets.UTF_8);
    }
}
