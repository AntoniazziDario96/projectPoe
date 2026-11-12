package it.poemeta.web;

import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisService;
import it.poemeta.analysis.BuildCombo;
import it.poemeta.analysis.ComboService;
import it.poemeta.analysis.DemandService;
import it.poemeta.config.AppProperties;
import it.poemeta.league.League;
import it.poemeta.persistence.BuildAnalysisEntity;
import it.poemeta.persistence.FavoriteComboEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Controller
public class BuildsController {

    private final CurrentLeague currentLeague;
    private final ComboService combos;
    private final AnalysisService analyses;
    private final DemandService demand;
    private final BuildViewService views;
    private final AppProperties props;
    private final Fmt fmt;

    public BuildsController(CurrentLeague currentLeague, ComboService combos, AnalysisService analyses,
                            DemandService demand, BuildViewService views, AppProperties props, Fmt fmt) {
        this.fmt = fmt;
        this.currentLeague = currentLeague;
        this.combos = combos;
        this.analyses = analyses;
        this.demand = demand;
        this.views = views;
        this.props = props;
    }

    @GetMapping("/builds")
    public String builds(HttpServletRequest req, Model model,
                         @RequestParam(required = false) Integer queued) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        ComboService.ComboList list = combos.combos(league);
        model.addAttribute("comboList", list);
        model.addAttribute("loading", combos.loading(league));
        model.addAttribute("queued", queued);

        Map<String, BuildAnalysisEntity> done = new HashMap<>();
        for (BuildAnalysisEntity a : analyses.doneIn(league.url())) {
            done.putIfAbsent(a.getAscendancy() + "|" + a.getSkill(), a);
        }
        Set<String> active = new HashSet<>();
        for (BuildAnalysisEntity a : analyses.activeIn(league.url())) {
            active.add(a.getAscendancy() + "|" + a.getSkill());
        }
        Set<String> favs = new HashSet<>();
        for (FavoriteComboEntity f : analyses.favorites(league.url())) {
            favs.add(f.getAscendancy() + "|" + f.getSkill());
        }
        Set<String> ascendancies = new TreeSet<>();
        if (list != null) {
            list.combos().forEach(c -> ascendancies.add(c.ascendancy()));
        }
        model.addAttribute("done", done);
        model.addAttribute("active", active);
        model.addAttribute("favs", favs);
        model.addAttribute("ascendancies", ascendancies);
        model.addAttribute("sampleSize", props.builds().sampleSize());
        model.addAttribute("minutesPerCombo", Math.max(1, Math.round(
                props.builds().sampleSize() * Math.max(props.builds().characterDelayMs(), props.requestDelayMs()) / 60_000.0)));
        model.addAttribute("activeAnalyses", analyses.activeIn(league.url()));
        return "builds";
    }

    @PostMapping("/builds/analyze")
    public String analyze(HttpServletRequest req, @RequestParam(name = "combo", required = false) List<String> selected) {
        League league = currentLeague.get(req);
        if (league == null || selected == null || selected.isEmpty()) {
            return "redirect:/builds";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String s : selected) {
            String[] p = s.split("\\|", 2);
            if (p.length == 2) {
                pairs.add(p);
                analyses.enqueueDefault(league, p[0], p[1]);
            }
        }
        if (pairs.size() == 1) {
            return "redirect:" + viewUrl(pairs.get(0)[0], pairs.get(0)[1]);
        }
        return "redirect:/builds?queued=" + pairs.size();
    }

    @GetMapping("/builds/view")
    public String view(HttpServletRequest req, Model model,
                       @RequestParam String ascendancy, @RequestParam String skill) {
        League league = currentLeague.get(req);
        if (league == null) {
            return "unavailable";
        }
        Optional<BuildAnalysisEntity> latest = analyses.latestDone(league.url(), ascendancy, skill);
        Optional<AnalysisResult> result = latest.flatMap(analyses::result);
        BuildAnalysisEntity running = analyses.activeIn(league.url()).stream()
                .filter(a -> a.getAscendancy().equals(ascendancy) && a.getSkill().equals(skill))
                .findFirst().orElse(null);
        model.addAttribute("ascendancy", ascendancy);
        model.addAttribute("skill", skill);
        model.addAttribute("analysis", latest.orElse(null));
        model.addAttribute("result", result.orElse(null));
        model.addAttribute("views", result.map(r -> views.views(r, league)).orElse(List.of()));
        model.addAttribute("overall", result.map(AnalysisResult::overall).orElse(null));
        model.addAttribute("overallUniques", result.map(AnalysisResult::overall)
                .map(o -> views.pricedUniques(o.uniques(), league)).orElse(List.of()));
        model.addAttribute("running", running);
        model.addAttribute("trend", latest.map(demand::trend).orElse(null));
        model.addAttribute("favorite", analyses.isFavorite(league.url(), ascendancy, skill));
        model.addAttribute("coreThreshold", props.archetypes().coreThreshold());
        model.addAttribute("extendSize", props.builds().extendSize());
        return "build-view";
    }

    @PostMapping("/builds/run")
    public String run(HttpServletRequest req, @RequestParam String ascendancy, @RequestParam String skill,
                      @RequestParam(defaultValue = "false") boolean extend) {
        League league = currentLeague.get(req);
        if (league != null) {
            if (extend) {
                analyses.extend(league, ascendancy, skill);
            } else {
                int sample = analyses.latestDone(league.url(), ascendancy, skill)
                        .map(BuildAnalysisEntity::getSampleTarget).orElse(props.builds().sampleSize());
                analyses.enqueue(league, ascendancy, skill, sample);
            }
        }
        return "redirect:" + viewUrl(ascendancy, skill);
    }

    @PostMapping("/builds/favorite")
    public String favorite(HttpServletRequest req, @RequestParam String ascendancy, @RequestParam String skill,
                           @RequestParam(defaultValue = "/builds") String back) {
        League league = currentLeague.get(req);
        if (league != null) {
            analyses.toggleFavorite(league.url(), ascendancy, skill);
        }
        return "redirect:" + (back.startsWith("/") && !back.startsWith("//") ? back : "/builds");
    }

    @PostMapping("/builds/reload")
    public String reload(HttpServletRequest req) {
        League league = currentLeague.get(req);
        if (league != null) {
            combos.reload(league);
        }
        return "redirect:/builds";
    }

    /** Stato delle analisi in corso, letto dalla pagina per aggiornare le barre di avanzamento. */
    @GetMapping("/api/analyses")
    @ResponseBody
    public List<Map<String, Object>> active(HttpServletRequest req) {
        League league = currentLeague.get(req);
        if (league == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BuildAnalysisEntity a : analyses.activeIn(league.url())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("ascendancy", a.getAscendancy());
            m.put("skill", a.getSkill());
            m.put("status", a.getStatus().name());
            m.put("progress", a.getProgress());
            m.put("total", a.getProgressTotal());
            m.put("percent", a.getProgressPercent());
            m.put("message", fmt.status(a.getMessage()));
            out.add(m);
        }
        return out;
    }

    static String viewUrl(String ascendancy, String skill) {
        return "/builds/view?ascendancy=" + URLEncoder.encode(ascendancy, StandardCharsets.UTF_8)
                + "&skill=" + URLEncoder.encode(skill, StandardCharsets.UTF_8);
    }

    /** Usato dal template per costruire i link alle coppie. */
    public static String comboUrl(BuildCombo c) {
        return viewUrl(c.ascendancy(), c.skill());
    }
}
