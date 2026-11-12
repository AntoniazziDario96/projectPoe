package it.poemeta.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Tier;
import it.poemeta.analysis.AnalysisService;
import it.poemeta.compare.CompareResult;
import it.poemeta.compare.CompareResult.ItemSuggestion;
import it.poemeta.compare.CompareService;
import it.poemeta.compare.ImportException;
import it.poemeta.compare.ImportService;
import it.poemeta.compare.ImportedCharacter;
import it.poemeta.economy.EconomyService;
import it.poemeta.league.League;
import it.poemeta.league.LeagueService;
import it.poemeta.links.TradeLinks.TradeLink;
import it.poemeta.links.TradePrices;
import it.poemeta.persistence.BuildAnalysisEntity;
import it.poemeta.persistence.SavedCharacterEntity;
import it.poemeta.persistence.SavedCharacterRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Pagina "Confronta": il personaggio del giocatore contro gli archetipi analizzati. */
@Controller
public class CompareController {

    private static final Logger log = LoggerFactory.getLogger(CompareController.class);
    /** I confronti non salvati vengono cancellati dopo questo tempo. */
    private static final Duration UNSAVED_TTL = Duration.ofDays(3);

    private final CurrentLeague currentLeague;
    private final LeagueService leagues;
    private final ImportService importer;
    private final CompareService compare;
    private final AnalysisService analyses;
    private final SavedCharacterRepository saved;
    private final TradePrices prices;
    private final EconomyService economy;
    private final ObjectMapper mapper;
    private final Fmt fmt;

    public CompareController(CurrentLeague currentLeague, LeagueService leagues, ImportService importer,
                             CompareService compare, AnalysisService analyses, SavedCharacterRepository saved,
                             TradePrices prices, EconomyService economy, ObjectMapper mapper, Fmt fmt) {
        this.currentLeague = currentLeague;
        this.leagues = leagues;
        this.importer = importer;
        this.compare = compare;
        this.analyses = analyses;
        this.saved = saved;
        this.prices = prices;
        this.economy = economy;
        this.mapper = mapper;
        this.fmt = fmt;
    }

    @GetMapping("/compare")
    public String form(HttpServletRequest req, Model model) {
        if (currentLeague.get(req) == null) {
            return "unavailable";
        }
        model.addAttribute("saved", saved.findBySavedTrueOrderByUpdatedAtDesc());
        model.addAttribute("form", new ImportForm("pob", "", "", "", false, ""));
        return "compare";
    }

    /** Valori del form, per ripresentarli in caso di errore. */
    public record ImportForm(String source, String pob, String account, String character, boolean save, String label) {
    }

    @PostMapping("/compare/import")
    public String importCharacter(HttpServletRequest req, Model model,
                                  @RequestParam(defaultValue = "pob") String source,
                                  @RequestParam(defaultValue = "") String pob,
                                  @RequestParam(defaultValue = "") String account,
                                  @RequestParam(defaultValue = "") String character,
                                  @RequestParam(defaultValue = "false") boolean save,
                                  @RequestParam(defaultValue = "") String label) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        saved.deleteBySavedFalseAndCreatedAtBefore(Instant.now().minus(UNSAVED_TTL));
        boolean ninja = "ninja".equals(source);
        try {
            ImportedCharacter ch = ninja ? importer.fromNinja(league, account, character) : importer.fromPob(pob);
            SavedCharacterEntity e = new SavedCharacterEntity(ch.source());
            e.setSaved(save);
            e.setLabel(defaultLabel(label, ch, character));
            e.setLeague(league.url());
            if (ninja) {
                e.setAccount(account.trim());
                e.setCharacterName(character.trim());
            } else {
                e.setPobCode(pob.trim());
            }
            e.setCharacterJson(mapper.writeValueAsString(ch));
            saved.save(e);
            return "redirect:/compare/" + e.getId();
        } catch (ImportException ex) {
            model.addAttribute("error", ex);
        } catch (JsonProcessingException ex) {
            log.warn("Personaggio non salvabile: {}", ex.getMessage());
            model.addAttribute("error", new ImportException("import.error.invalidPob"));
        }
        model.addAttribute("saved", saved.findBySavedTrueOrderByUpdatedAtDesc());
        model.addAttribute("form", new ImportForm(source, pob, account, character, save, label));
        return "compare";
    }

    private static String defaultLabel(String label, ImportedCharacter ch, String character) {
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        if (character != null && !character.isBlank()) {
            return character.trim();
        }
        String skill = ch.mainSkill() == null ? "" : ch.mainSkill() + " ";
        return skill + ch.profile().ascendancy() + " lv " + ch.profile().level();
    }

    @GetMapping("/compare/{id}")
    public String view(HttpServletRequest req, Model model, @PathVariable long id,
                       @RequestParam(required = false) String pair,
                       @RequestParam(required = false) Integer arch,
                       @RequestParam(required = false) String tier,
                       @RequestParam(defaultValue = "price") String sort,
                       @RequestParam(required = false) String refreshError) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        Optional<SavedCharacterEntity> found = saved.findById(id);
        if (found.isEmpty()) {
            return "redirect:/compare";
        }
        SavedCharacterEntity e = found.get();
        ImportedCharacter ch = read(e);
        if (ch == null) {
            return "redirect:/compare";
        }

        // coppie analizzate nella lega corrente (le più recenti prima)
        Map<String, BuildAnalysisEntity> done = new LinkedHashMap<>();
        for (BuildAnalysisEntity a : analyses.doneIn(league.url())) {
            done.putIfAbsent(a.getAscendancy() + "|" + a.getSkill(), a);
        }
        Map<String, String> pairs = new TreeMap<>();
        done.keySet().forEach(k -> pairs.put(k, k.replace("|", " · ")));

        String asc;
        String skill;
        // l'archetipo scelto vale solo per la sua coppia: cambiando coppia si riparte dal più simile
        if (pair != null && pair.contains("|")) {
            String[] p = pair.split("\\|", 2);
            asc = p[0];
            skill = p[1];
        } else if (e.getAscendancy() != null && e.getSkill() != null) {
            asc = e.getAscendancy();
            skill = e.getSkill();
            if (arch == null) {
                arch = e.getArchetypeIndex();
            }
        } else {
            asc = ch.profile().ascendancy();
            skill = ch.mainSkill();
        }
        Tier t = Tier.parse(tier != null ? tier : e.getTier());

        // la chiave arriva dall'URL: si accettano solo le chiavi di errore note
        model.addAttribute("refreshError",
                refreshError != null && refreshError.matches("import\\.error\\.[A-Za-z]{1,40}") ? refreshError : null);
        model.addAttribute("entry", e);
        model.addAttribute("ch", ch);
        model.addAttribute("pairs", pairs);
        model.addAttribute("pairKey", asc + "|" + skill);
        model.addAttribute("asc", asc);
        model.addAttribute("skill", skill);
        model.addAttribute("tier", t.param());
        model.addAttribute("sort", sort);

        Optional<AnalysisResult> result = skill == null ? Optional.empty()
                : analyses.latestDone(league.url(), asc, skill).flatMap(analyses::result);
        BuildAnalysisEntity running = skill == null ? null : analyses.activeIn(league.url()).stream()
                .filter(a -> a.getAscendancy().equals(asc) && a.getSkill().equals(skill)).findFirst().orElse(null);
        model.addAttribute("running", running);
        if (result.isEmpty()) {
            model.addAttribute("result", null);
            return "compare-result";
        }

        CompareResult r = compare.compare(ch, result.get(), league, arch, t);
        if (r == null) {
            model.addAttribute("result", null);
            return "compare-result";
        }
        List<ItemSuggestion> items = new ArrayList<>(r.items());
        if ("diff".equals(sort)) {
            items.sort(Comparator.comparingDouble(ItemSuggestion::diff).reversed());
        }
        model.addAttribute("result", r);
        model.addAttribute("items", items);
        model.addAttribute("chosenArch", arch);

        // ricorda le scelte e il numero di cambi (per mostrare i progressi)
        int count = r.items().size() + r.gems().size();
        e.setAscendancy(asc);
        e.setSkill(skill);
        e.setArchetypeIndex(arch);
        e.setTier(t.param());
        if (e.getLastCount() == null || !e.getLastCount().equals(count)) {
            e.setLastCount(count);
        }
        saved.save(e);
        model.addAttribute("changeCount", count);
        return "compare-result";
    }

    @PostMapping("/compare/{id}/refresh")
    public String refresh(HttpServletRequest req, Model model, @PathVariable long id,
                          @RequestParam(defaultValue = "") String pob) {
        Optional<SavedCharacterEntity> found = saved.findById(id);
        if (found.isEmpty()) {
            return "redirect:/compare";
        }
        SavedCharacterEntity e = found.get();
        try {
            ImportedCharacter ch;
            if ("ninja".equals(e.getSource())) {
                League league = leagues.byUrl(e.getLeague()).orElse(currentLeague.get(req));
                ch = importer.fromNinja(league, e.getAccount(), e.getCharacterName());
            } else {
                String code = pob.isBlank() ? e.getPobCode() : pob.trim();
                ch = importer.fromPob(code);
                e.setPobCode(code);
            }
            e.setCharacterJson(mapper.writeValueAsString(ch));
            e.setPreviousCount(e.getLastCount());
            e.setLastCount(null);
            e.setUpdatedAt(Instant.now());
            saved.save(e);
            return "redirect:/compare/" + id + "?updated=1";
        } catch (ImportException ex) {
            return "redirect:/compare/" + id + "?refreshError=" + ex.getKey();
        } catch (JsonProcessingException ex) {
            return "redirect:/compare/" + id + "?refreshError=import.error.invalidPob";
        }
    }

    @PostMapping("/compare/{id}/save")
    public String save(@PathVariable long id, @RequestParam(defaultValue = "") String label) {
        saved.findById(id).ifPresent(e -> {
            e.setSaved(true);
            if (!label.isBlank()) {
                e.setLabel(label.trim());
            }
            e.setUpdatedAt(Instant.now());
            saved.save(e);
        });
        return "redirect:/compare/" + id;
    }

    @PostMapping("/compare/{id}/delete")
    public String delete(@PathVariable long id) {
        saved.deleteById(id);
        return "redirect:/compare";
    }

    /** Prezzo di un giallo letto dal trade (chiamato dalla pagina, uno alla volta). */
    @GetMapping("/api/compare/price/{token}")
    @ResponseBody
    public Map<String, Object> price(HttpServletRequest req, @PathVariable String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<TradeLink> link = compare.pendingPrice(token);
        if (link.isEmpty()) {
            out.put("status", "expired");
            return out;
        }
        try {
            TradePrices.Price p = prices.price(link.get());
            out.put("status", p.chaos() == null ? "none" : "ok");
            out.put("chaos", p.chaos());
            out.put("total", p.total());
            Double divine = economy.divinePrice(link.get().league()).orElse(null);
            out.put("text", p.chaos() == null ? null : fmt.price(p.chaos(), divine));
        } catch (TradePrices.Unavailable ex) {
            out.put("status", ex.getReason());
        }
        return out;
    }

    private ImportedCharacter read(SavedCharacterEntity e) {
        try {
            return mapper.readValue(e.getCharacterJson(), ImportedCharacter.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Personaggio salvato {} non leggibile: {}", e.getId(), ex.getMessage());
            return null;
        }
    }
}
