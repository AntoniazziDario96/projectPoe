package it.poemeta.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.config.AppProperties;
import it.poemeta.league.League;
import it.poemeta.league.LeagueService;
import it.poemeta.ninja.NinjaBuildsClient;
import it.poemeta.ninja.NinjaBuildsClient.CharacterRef;
import it.poemeta.ninja.NinjaBuildsClient.SearchResult;
import it.poemeta.persistence.BuildAnalysisEntity;
import it.poemeta.persistence.BuildAnalysisEntity.Status;
import it.poemeta.persistence.BuildAnalysisRepository;
import it.poemeta.persistence.CachedCharacterEntity;
import it.poemeta.persistence.CachedCharacterRepository;
import it.poemeta.persistence.FavoriteComboEntity;
import it.poemeta.persistence.FavoriteComboRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.poemeta.ninja.NinjaRateLimitException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Esegue le analisi skill + ascendancy in background, una alla volta:
 * <ol>
 *   <li>cerca i personaggi della coppia su poe.ninja (più ordinamenti, perché ognuno dà max 100 righe);</li>
 *   <li>scarica il dettaglio di ognuno (o lo riprende dalla cache);</li>
 *   <li>li raggruppa in archetipi e salva il risultato.</li>
 * </ol>
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /** Ordinamenti usati per raccogliere più di 100 personaggi (null = default per livello). */
    private static final List<String> SORTS = List.of("", "life", "dps", "energyshield", "ehp");
    private static final Set<Status> ACTIVE = Set.of(Status.QUEUED, Status.RUNNING);

    private final NinjaBuildsClient client;
    private final LeagueService leagues;
    private final BuildAnalysisRepository analyses;
    private final CachedCharacterRepository characters;
    private final FavoriteComboRepository favorites;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "build-analysis");
        t.setDaemon(true);
        return t;
    });

    public AnalysisService(NinjaBuildsClient client, LeagueService leagues, BuildAnalysisRepository analyses,
                           CachedCharacterRepository characters, FavoriteComboRepository favorites,
                           ObjectMapper mapper, AppProperties props) {
        this.client = client;
        this.leagues = leagues;
        this.analyses = analyses;
        this.characters = characters;
        this.favorites = favorites;
        this.mapper = mapper;
        this.props = props;
    }

    /** Al riavvio rimette in coda le analisi rimaste a metà. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePending() {
        for (BuildAnalysisEntity a : analyses.findByStatusIn(ACTIVE)) {
            a.setStatus(Status.QUEUED);
            a.setMessage("status.queuedResumed");
            analyses.save(a);
            Long id = a.getId();
            executor.submit(() -> run(id));
        }
    }

    /** Mette in coda un'analisi; se la stessa coppia è già in coda o in corso restituisce quella. */
    public BuildAnalysisEntity enqueue(League league, String ascendancy, String skill, int sampleTarget) {
        Optional<BuildAnalysisEntity> running = analyses
                .findFirstByLeagueAndAscendancyAndSkillAndStatusInOrderByCreatedAtDesc(
                        league.url(), ascendancy, skill, ACTIVE);
        if (running.isPresent()) {
            return running.get();
        }
        BuildAnalysisEntity a = analyses.save(new BuildAnalysisEntity(league.url(), ascendancy, skill, sampleTarget));
        Long id = a.getId();
        executor.submit(() -> run(id));
        return a;
    }

    public BuildAnalysisEntity enqueueDefault(League league, String ascendancy, String skill) {
        return enqueue(league, ascendancy, skill, props.builds().sampleSize());
    }

    /** "Estendi analisi": aggiunge altri personaggi all'ultima analisi completata. */
    public BuildAnalysisEntity extend(League league, String ascendancy, String skill) {
        int base = latestDone(league.url(), ascendancy, skill)
                .map(BuildAnalysisEntity::getSampleTarget).orElse(props.builds().sampleSize());
        return enqueue(league, ascendancy, skill, base + props.builds().extendSize());
    }

    public Optional<BuildAnalysisEntity> latestDone(String league, String ascendancy, String skill) {
        return analyses.findByLeagueAndAscendancyAndSkillOrderByCreatedAtDesc(league, ascendancy, skill).stream()
                .filter(a -> a.getStatus() == Status.DONE).findFirst();
    }

    /** L'analisi completata precedente all'ultima, per calcolare il trend. */
    public Optional<BuildAnalysisEntity> previousDone(String league, String ascendancy, String skill) {
        return analyses.findByLeagueAndAscendancyAndSkillOrderByCreatedAtDesc(league, ascendancy, skill).stream()
                .filter(a -> a.getStatus() == Status.DONE).skip(1).findFirst();
    }

    public List<BuildAnalysisEntity> activeIn(String league) {
        return analyses.findByLeagueAndStatusInOrderByCreatedAtAsc(league, ACTIVE);
    }

    public List<BuildAnalysisEntity> doneIn(String league) {
        return analyses.findByLeagueAndStatusOrderByCreatedAtDesc(league, Status.DONE);
    }

    public Optional<BuildAnalysisEntity> find(long id) {
        return analyses.findById(id);
    }

    public Optional<AnalysisResult> result(BuildAnalysisEntity a) {
        if (a.getResultJson() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(a.getResultJson(), AnalysisResult.class));
        } catch (JsonProcessingException e) {
            log.warn("Risultato analisi {} non leggibile: {}", a.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ preferite

    public boolean isFavorite(String league, String ascendancy, String skill) {
        return favorites.findByLeagueAndAscendancyAndSkill(league, ascendancy, skill).isPresent();
    }

    public List<FavoriteComboEntity> favorites(String league) {
        return favorites.findByLeagueOrderByAscendancyAscSkillAsc(league);
    }

    public boolean toggleFavorite(String league, String ascendancy, String skill) {
        Optional<FavoriteComboEntity> f = favorites.findByLeagueAndAscendancyAndSkill(league, ascendancy, skill);
        if (f.isPresent()) {
            favorites.delete(f.get());
            return false;
        }
        favorites.save(new FavoriteComboEntity(league, ascendancy, skill));
        return true;
    }

    /** Ogni ora controlla se qualche preferita va aggiornata. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void refreshFavorites() {
        Duration maxAge = Duration.ofHours(props.builds().favoritesRefreshHours());
        for (FavoriteComboEntity f : favorites.findAll()) {
            Optional<League> league = leagues.byUrl(f.getLeague());
            if (league.isEmpty()) {
                continue;
            }
            Optional<BuildAnalysisEntity> last = latestDone(f.getLeague(), f.getAscendancy(), f.getSkill());
            boolean due = last.map(a -> a.getFinishedAt() == null
                    || a.getFinishedAt().plus(maxAge).isBefore(Instant.now())).orElse(true);
            if (due) {
                int sample = last.map(BuildAnalysisEntity::getSampleTarget).orElse(props.builds().sampleSize());
                enqueue(league.get(), f.getAscendancy(), f.getSkill(), sample);
            }
        }
    }

    // ------------------------------------------------------------ esecuzione

    private void run(Long id) {
        BuildAnalysisEntity a = analyses.findById(id).orElse(null);
        if (a == null || a.getStatus() != Status.QUEUED) {
            return;
        }
        try {
            League league = leagues.byUrl(a.getLeague())
                    .orElseThrow(() -> new IllegalStateException("Lega non più disponibile: " + a.getLeague()));
            a.setStatus(Status.RUNNING);
            a.setMessage("status.searching");
            analyses.save(a);

            Map<String, String> filters = Map.of("class", a.getAscendancy(), "skills", a.getSkill());
            Set<CharacterRef> refs = new LinkedHashSet<>();
            long comboTotal = 0;
            AnalysisResult.Overall overall = null;
            for (String sort : SORTS) {
                if (refs.size() >= a.getSampleTarget()) {
                    break;
                }
                SearchResult r = client.search(league.snapshotVersion(), league.url(), filters, sort);
                comboTotal = Math.max(comboTotal, r.total());
                if (overall == null) {
                    overall = overall(r);
                }
                for (CharacterRef ref : r.rows()) {
                    if (refs.size() >= a.getSampleTarget()) {
                        break;
                    }
                    refs.add(ref);
                }
                if (r.total() <= refs.size()) {
                    break; // abbiamo già tutti i personaggi esistenti
                }
            }
            a.setComboTotal(comboTotal);
            a.setProgressTotal(refs.size());
            a.setMessage("status.downloading");
            analyses.save(a);

            List<CharacterProfile> profiles = new ArrayList<>();
            int failed = 0;
            int done = 0;
            List<CharacterRef> refList = new ArrayList<>(refs);
            for (int i = 0; i < refList.size(); ) {
                CharacterRef ref = refList.get(i);
                try {
                    CharacterProfile p = profile(league, ref);
                    if (p.ascendancy().equalsIgnoreCase(a.getAscendancy())) {
                        profiles.add(p);
                    }
                } catch (NinjaRateLimitException e) {
                    // poe.ninja chiede una pausa: aspettiamo e riproviamo lo stesso personaggio
                    pause(a, e.getRetryAfterMs());
                    continue;
                } catch (RuntimeException e) {
                    failed++;
                    log.debug("Personaggio {}/{} non scaricato: {}", ref.account(), ref.name(), e.getMessage());
                }
                i++;
                done++;
                if (done % 5 == 0 || done == refs.size()) {
                    a.setProgress(done);
                    a.setMessage("status.downloading");
                    analyses.save(a);
                }
            }
            if (profiles.isEmpty()) {
                throw new IllegalStateException("Nessun personaggio scaricato");
            }

            a.setMessage("status.clustering");
            analyses.save(a);
            List<AnalysisResult.Archetype> archetypes =
                    new ArchetypeClusterer(props.archetypes()).cluster(profiles, a.getSkill());
            AnalysisResult result = new AnalysisResult(league.url(), a.getAscendancy(), a.getSkill(), comboTotal,
                    profiles.size(), failed, Instant.now(), overall, archetypes);
            a.setResultJson(mapper.writeValueAsString(result));
            a.setStatus(Status.DONE);
            a.setMessage("status.done|" + profiles.size() + "|" + archetypes.size());
            a.setFinishedAt(Instant.now());
            analyses.save(a);
            log.info("Analisi {} {} + {} completata ({} personaggi)", league.name(), a.getSkill(),
                    a.getAscendancy(), profiles.size());
        } catch (Exception e) {
            log.error("Analisi {} fallita: {}", id, e.getMessage());
            a.setStatus(Status.FAILED);
            a.setMessage("status.error|" + e.getMessage());
            a.setFinishedAt(Instant.now());
            analyses.save(a);
        }
    }

    private void pause(BuildAnalysisEntity a, long retryAfterMs) throws InterruptedException {
        long wait = Math.max(60_000, retryAfterMs) + 5_000;
        String until = LocalTime.now().plus(Duration.ofMillis(wait)).format(DateTimeFormatter.ofPattern("HH:mm"));
        a.setMessage("status.paused|" + until);
        analyses.save(a);
        log.warn("Analisi {} in pausa fino alle {}", a.getId(), until);
        Thread.sleep(wait);
        a.setMessage("status.downloading");
        analyses.save(a);
    }

    /** Statistiche su tutti i personaggi della coppia, dai conteggi della ricerca di poe.ninja. */
    static AnalysisResult.Overall overall(SearchResult r) {
        double total = Math.max(1, r.total());
        Map<String, Map<String, Integer>> d = r.dimensions();
        List<AnalysisResult.Stat> uniques = new ArrayList<>();
        List<AnalysisResult.Stat> rareSlots = new ArrayList<>();
        d.getOrDefault("items", Map.of()).forEach((name, count) -> {
            double pct = count / total;
            if (name.startsWith("Rare ")) {
                rareSlots.add(new AnalysisResult.Stat(name.substring(5), pct, null));
            } else if (!name.startsWith("Magic ") && !name.startsWith("Normal ")) {
                uniques.add(new AnalysisResult.Stat(name, pct, null));
            }
        });
        return new AnalysisResult.Overall(
                top(uniques, 20),
                top(toStats(d.get("allgems"), total), 25),
                top(toStats(d.get("keypassives"), total), 12),
                top(toStats(d.get("masteries"), total), 12),
                top(toStats(d.get("anointed"), total), 8),
                top(toStats(d.get("secondascendancy"), total).stream()
                        .filter(s -> !"None".equals(s.name())).toList(), 5),
                top(rareSlots, 12));
    }

    private static List<AnalysisResult.Stat> toStats(Map<String, Integer> counts, double total) {
        if (counts == null) {
            return List.of();
        }
        return counts.entrySet().stream()
                .map(e -> new AnalysisResult.Stat(e.getKey(), e.getValue() / total, null)).toList();
    }

    private static List<AnalysisResult.Stat> top(List<AnalysisResult.Stat> stats, int limit) {
        return stats.stream()
                .filter(s -> s.pct() >= 0.05)
                .sorted(Comparator.comparingDouble(AnalysisResult.Stat::pct).reversed())
                .limit(limit)
                .toList();
    }

    private CharacterProfile profile(League league, CharacterRef ref) throws JsonProcessingException {
        Optional<CachedCharacterEntity> cached =
                characters.findByLeagueAndAccountAndName(league.url(), ref.account(), ref.name());
        Duration maxAge = Duration.ofHours(props.builds().characterCacheHours());
        if (cached.isPresent() && cached.get().getProfileJson() != null
                && cached.get().getFetchedAt().plus(maxAge).isAfter(Instant.now())) {
            return mapper.readValue(cached.get().getProfileJson(), CharacterProfile.class);
        }
        CharacterProfile p = ProfileExtractor.extract(
                client.character(league.snapshotVersion(), league.url(), ref.account(), ref.name()));
        CachedCharacterEntity e = cached.orElseGet(() ->
                new CachedCharacterEntity(league.url(), ref.account(), ref.name()));
        e.setProfileJson(mapper.writeValueAsString(p));
        e.setFetchedAt(Instant.now());
        characters.save(e);
        return p;
    }
}
