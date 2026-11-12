package it.poemeta.compare;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Codici Path of Building: base64 (variante URL) di un XML compresso con zlib.
 * Riconosce anche i link di pobb.in, pastebin e poe.ninja e restituisce l'indirizzo del testo "raw".
 */
public final class PobCode {

    /** Limite all'XML decompresso: un PoB reale pesa qualche centinaio di KB. */
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;

    private static final Pattern POBBIN = Pattern.compile("^https?://(?:www\\.)?pobb\\.in/(?:u/[^/]+/)?([A-Za-z0-9_-]+)/?(?:raw)?$");
    private static final Pattern PASTEBIN = Pattern.compile("^https?://(?:www\\.)?pastebin\\.com/(?:raw/)?([A-Za-z0-9]+)/?$");
    private static final Pattern NINJA = Pattern.compile("^https?://(?:www\\.)?poe\\.ninja/(?:poe1/)?pob/(?:raw/)?([A-Za-z0-9_-]+)/?$");

    private PobCode() {
    }

    /** Se il testo è un link a un PoB condiviso, l'indirizzo da cui scaricare il codice. */
    public static Optional<String> rawUrl(String input) {
        String s = input == null ? "" : input.trim();
        Matcher m = POBBIN.matcher(s);
        if (m.matches()) {
            return Optional.of("https://pobb.in/" + m.group(1) + "/raw");
        }
        m = PASTEBIN.matcher(s);
        if (m.matches()) {
            return Optional.of("https://pastebin.com/raw/" + m.group(1));
        }
        m = NINJA.matcher(s);
        if (m.matches()) {
            return Optional.of("https://poe.ninja/pob/raw/" + m.group(1));
        }
        return Optional.empty();
    }

    public static boolean looksLikeUrl(String input) {
        return input != null && input.trim().matches("(?i)^https?://.*");
    }

    /** Decodifica il codice e restituisce l'XML. */
    public static String decode(String code) {
        String clean = code == null ? "" : code.replaceAll("\\s+", "").replace('+', '-').replace('/', '_');
        clean = clean.replace("=", "");
        if (clean.isEmpty()) {
            throw new ImportException("import.error.empty");
        }
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new ImportException("import.error.invalidPob", e);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 8);
        byte[] buf = new byte[16 * 1024];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_XML_BYTES) {
                    throw new ImportException("import.error.invalidPob");
                }
            }
        } catch (DataFormatException e) {
            throw new ImportException("import.error.invalidPob", e);
        } finally {
            inflater.end();
        }
        String xml = out.toString(StandardCharsets.UTF_8);
        if (!xml.contains("<PathOfBuilding")) {
            throw new ImportException("import.error.invalidPob");
        }
        return xml;
    }
}
