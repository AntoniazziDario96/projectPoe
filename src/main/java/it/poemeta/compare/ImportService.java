package it.poemeta.compare;

import com.fasterxml.jackson.databind.JsonNode;
import it.poemeta.analysis.CharacterProfile;
import it.poemeta.analysis.ProfileExtractor;
import it.poemeta.compare.ImportedCharacter.EquippedItem;
import it.poemeta.league.League;
import it.poemeta.ninja.NinjaBuildsClient;
import it.poemeta.ninja.NinjaException;
import it.poemeta.ninja.NinjaRateLimitException;
import it.poemeta.ninja.RateLimitedHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Importa il personaggio del giocatore da un codice / link Path of Building o da poe.ninja. */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final RateLimitedHttp http;
    private final NinjaBuildsClient ninja;
    private final PobParser parser;

    public ImportService(RateLimitedHttp http, NinjaBuildsClient ninja, TreeData tree) {
        this.http = http;
        this.ninja = ninja;
        this.parser = new PobParser(tree);
    }

    /** Da un codice PoB o da un link (pobb.in, pastebin, poe.ninja/pob). */
    public ImportedCharacter fromPob(String input) {
        String code = input == null ? "" : input.trim();
        if (code.isEmpty()) {
            throw new ImportException("import.error.empty");
        }
        if (PobCode.looksLikeUrl(code)) {
            Optional<String> raw = PobCode.rawUrl(code);
            if (raw.isEmpty()) {
                throw new ImportException("import.error.unknownLink");
            }
            try {
                code = new String(http.getBytes(raw.get()), StandardCharsets.UTF_8).trim();
            } catch (NinjaException e) {
                throw new ImportException("import.error.linkDownload", e);
            }
        }
        return parser.parse(PobCode.decode(code));
    }

    /**
     * Da poe.ninja: il personaggio deve essere tra quelli indicizzati per la lega scelta.
     *
     * @param account nome dell'account, anche nella forma "Nome#1234"
     */
    public ImportedCharacter fromNinja(League league, String account, String character) {
        String acc = account == null ? "" : account.trim().replace('#', '-');
        String name = character == null ? "" : character.trim();
        if (acc.isEmpty() || name.isEmpty()) {
            throw new ImportException("import.error.ninjaMissing");
        }
        JsonNode json;
        try {
            json = ninja.character(league.snapshotVersion(), league.url(), acc, name);
        } catch (NinjaRateLimitException e) {
            throw new ImportException("import.error.ninjaBusy", e);
        } catch (NinjaException e) {
            String msg = String.valueOf(e.getMessage());
            throw new ImportException(msg.contains("404") ? "import.error.ninjaNotFound" : "import.error.ninjaDown", e,
                    name, league.name());
        }
        if (json == null || !json.has("items")) {
            throw new ImportException("import.error.ninjaNotFound", name, league.name());
        }
        CharacterProfile profile = ProfileExtractor.extract(json);

        // la skill principale si legge dal PoB che poe.ninja allega al personaggio
        String mainSkill = null;
        String className = json.path("class").asText(null);
        String pob = json.path("pathOfBuildingExport").asText("");
        if (!pob.isBlank()) {
            try {
                ImportedCharacter fromPob = parser.parse(PobCode.decode(pob));
                mainSkill = fromPob.mainSkill();
                if (fromPob.className() != null && !fromPob.className().isBlank()) {
                    className = fromPob.className();
                }
            } catch (RuntimeException e) {
                log.debug("PoB di poe.ninja non leggibile per {}: {}", name, e.getMessage());
            }
        }
        return new ImportedCharacter("ninja", mainSkill, className, profile, equipped(json));
    }

    /** Item equipaggiati dal JSON di poe.ninja. */
    static List<EquippedItem> equipped(JsonNode c) {
        List<EquippedItem> out = new ArrayList<>();
        for (String section : List.of("items", "flasks", "jewels")) {
            for (JsonNode it : c.path(section)) {
                JsonNode d = it.path("itemData");
                String slot = switch (section) {
                    case "flasks" -> "Flask";
                    case "jewels" -> "Jewel";
                    default -> ProfileExtractor.SLOTS.get(d.path("inventoryId").asText(""));
                };
                if (slot == null) {
                    continue;
                }
                String rarity = switch (d.path("frameType").asInt(-1)) {
                    case 0 -> "NORMAL";
                    case 1 -> "MAGIC";
                    case 2 -> "RARE";
                    case 3 -> "UNIQUE";
                    case 9 -> "RELIC";
                    default -> d.path("rarity").asText("NORMAL").toUpperCase(Locale.ROOT);
                };
                String name = d.path("name").asText("");
                out.add(new EquippedItem(slot, rarity, name.isBlank() ? null : name,
                        d.path("baseType").asText(d.path("typeLine").asText("")),
                        d.path("icon").asText(null), ProfileExtractor.explicitMods(d)));
            }
        }
        return out;
    }
}
