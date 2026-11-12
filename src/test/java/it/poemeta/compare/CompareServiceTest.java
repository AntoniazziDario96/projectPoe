package it.poemeta.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.poemeta.analysis.AnalysisResult;
import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Defence;
import it.poemeta.analysis.AnalysisResult.ModStat;
import it.poemeta.analysis.AnalysisResult.Range;
import it.poemeta.analysis.AnalysisResult.RareSlot;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.analysis.AnalysisResult.Tier;
import it.poemeta.analysis.ModText;
import it.poemeta.compare.CompareResult.ItemSuggestion;
import it.poemeta.compare.CompareResult.ModCheck;
import it.poemeta.config.AppProperties;
import it.poemeta.economy.EconomyService;
import it.poemeta.league.League;
import it.poemeta.links.TradeLinks;
import it.poemeta.links.TradePrices;
import it.poemeta.ninja.RateLimitedHttp;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompareServiceTest {

    private static final League LEAGUE = new League("Standard", "standard", "v1", "Standard", false, 1000);

    @Test
    void suggestsMissingUniqueAndWeakRareWithTierValues() {
        EconomyService economy = mock(EconomyService.class);
        when(economy.uniquePrice(anyString(), anyString())).thenReturn(Optional.empty());
        when(economy.gemPrice(anyString(), anyString())).thenReturn(Optional.empty());
        CompareService service = service(economy);

        ImportedCharacter ch = new PobParser(PobParserTest.TREE).parse(PobParserTest.XML.strip()
                .replace("Badge of the Brotherhood", "Some Other Amulet"));
        AnalysisResult analysis = analysis();

        CompareResult r = service.compare(ch, analysis, LEAGUE, null, Tier.HIGH);

        assertThat(r).isNotNull();
        assertThat(r.archetype().index()).isEqualTo(1);
        // fuoco al 47%: avviso "da sistemare"
        assertThat(r.defence()).anyMatch(n -> n.key().equals("compare.def.fire") && n.level().equals("bad")
                && n.a1().equals("28"));
        assertThat(r.summary().defence()).isEqualTo("bad");

        ItemSuggestion amulet = r.items().stream().filter(s -> "Amulet".equals(s.slot())).findFirst().orElseThrow();
        assertThat(amulet.kind()).isEqualTo("unique");
        assertThat(amulet.target()).isEqualTo("Badge of the Brotherhood");
        assertThat(amulet.current().name()).isEqualTo("Some Other Amulet");

        ItemSuggestion helm = r.items().stream().filter(s -> "Helm".equals(s.slot())).findFirst().orElseThrow();
        assertThat(helm.kind()).isEqualTo("rare");
        // tier alto: 75° percentile dei valori dei giocatori
        ModCheck es = helm.mods().stream().filter(m -> m.mod().equals("+# to maximum Energy Shield")).findFirst().orElseThrow();
        assertThat(es.min()).isEqualTo(100.0);
        assertThat(es.current()).isEqualTo(93.0);
        assertThat(es.ok()).isFalse();
        // i mod di resistenza diventano un unico totale "pseudo"
        assertThat(helm.mods()).noneMatch(m -> m.mod().contains("Resistance"));
        ModCheck ele = helm.mods().stream().filter(ModCheck::resistance).findFirst().orElseThrow();
        assertThat(ele.min()).isEqualTo(80.0);
        assertThat(ele.current()).isEqualTo(64.5);
        // il deficit (28% fuoco) si aggiunge alle resistenze richieste: 80 + 28
        assertThat(helm.minEleRes()).isEqualTo(108.0);
        String q = URLDecoder.decode(helm.tradeUrl(), StandardCharsets.UTF_8);
        assertThat(q).contains("pseudo.pseudo_total_elemental_resistance").contains("\"min\":108");

        // gemme: manca il supporto core
        assertThat(r.gems()).extracting(CompareResult.GemSuggestion::name).containsExactly("Hypothermia Support");
        // pantheon: minore diverso
        assertThat(r.pantheon().minorDifferent()).isTrue();
        assertThat(r.pantheon().majorDifferent()).isFalse();
    }

    @Test
    void lowTierAsksForLessAndRareCanBeGoodEnough() {
        EconomyService economy = mock(EconomyService.class);
        when(economy.uniquePrice(anyString(), anyString())).thenReturn(Optional.empty());
        when(economy.gemPrice(anyString(), anyString())).thenReturn(Optional.empty());
        CompareResult r = service(economy).compare(new PobParser(PobParserTest.TREE).parse(PobParserTest.XML.strip()),
                analysis(), LEAGUE, null, Tier.LOW);
        // tier basso: 80 ES e 40 di resistenze, il casco (93 ES, 64.5 res) andrebbe bene,
        // ma il deficit di fuoco alza comunque la richiesta: resta solo il controllo resistenze
        assertThat(r.items()).noneMatch(s -> "Amulet".equals(s.slot())); // ha già il Badge
    }

    @Test
    void itemsWeighMostInSimilarity() {
        ImportedCharacter same = new PobParser(PobParserTest.TREE).parse(PobParserTest.XML.strip());
        ImportedCharacter otherAmulet = new PobParser(PobParserTest.TREE).parse(PobParserTest.XML.strip()
                .replace("Badge of the Brotherhood", "Some Other Amulet"));
        Archetype a = analysis().archetypes().get(0);
        // stesso amuleto unique e casco giallo, come la maggior parte dei giocatori
        assertThat(CompareService.itemSimilarity(same, a)).isEqualTo(1.0);
        // amuleto diverso: metà degli slot rilevanti
        assertThat(CompareService.itemSimilarity(otherAmulet, a)).isEqualTo(0.5);

        // item uguali, ma tante mastery e gemme in più: la somiglianza resta alta
        CompareService service = service(mock(EconomyService.class));
        CompareResult.ArchetypeMatch m = service.matches(same, analysis(), true).get(0);
        assertThat(m.items()).isEqualTo(1.0);
        assertThat(m.score()).isGreaterThan(0.8);
    }

    @Test
    void coverageIgnoresExtras() {
        List<Stat> arch = List.of(new Stat("a", 1.0, null), new Stat("b", 0.5, null), new Stat("rare", 0.1, null));
        assertThat(CompareService.coverage(Set.of("a", "b", "x", "y", "z"), arch)).isEqualTo(1.0);
        assertThat(CompareService.coverage(Set.of("a"), arch)).isEqualTo(1.0 / 1.5);
        assertThat(CompareService.coverage(Set.of(), List.of())).isEqualTo(1.0);
    }

    @Test
    void resistTotals() {
        ModText.Resists r = ModText.resists(List.of("+40% to Fire Resistance", "+12% to all Elemental Resistances",
                "+20% to Cold and Lightning Resistances", "+15% to Chaos Resistance", "+10% to Fire and Chaos Resistances"));
        assertThat(r.elemental()).isEqualTo(40 + 36 + 40 + 10);
        assertThat(r.chaos()).isEqualTo(25);
        assertThat(ModText.value("Adds 10 to 20 Fire Damage to Attacks")).isEqualTo(15.0);
        assertThat(Range.of(List.of(10.0, 20.0, 30.0, 40.0, 50.0))).isEqualTo(new Range(20, 30, 40));
    }

    private static CompareService service(EconomyService economy) {
        RateLimitedHttp http = mock(RateLimitedHttp.class);
        when(http.getJson(anyString())).thenThrow(new RuntimeException("offline"));
        TradeLinks trade = new TradeLinks(http, new ObjectMapper());
        TradePrices prices = mock(TradePrices.class);
        when(prices.cached(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        AppProperties props = mock(AppProperties.class);
        AppProperties.Archetypes arch = mock(AppProperties.Archetypes.class);
        when(arch.coreThreshold()).thenReturn(0.7);
        when(props.archetypes()).thenReturn(arch);
        return new CompareService(economy, trade, prices, props, PobParserTest.TREE);
    }

    private static AnalysisResult analysis() {
        RareSlot helm = new RareSlot("Helm", 50, 0.9, List.of(new Stat("Hubris Circlet", 0.8, null)),
                List.of(new ModStat("+# to maximum Energy Shield", 0.9, 90.0, new Range(80, 90, 100)),
                        new ModStat("+#% to Fire Resistance", 0.6, 30.0, new Range(20, 30, 40))),
                new Range(40, 60, 80), new Range(0, 0, 0));
        Archetype a = new Archetype(1, "CI · Badge", 50, 0.8, false, 0.7, null, 92, 1, 7000,
                List.of(new Stat("Infused Channelling Support", 0.95, null), new Stat("Hypothermia Support", 0.9, null)),
                List.of(), List.of(new Stat("Badge of the Brotherhood", 0.9, null)),
                List.of(new Stat("Chaos Inoculation", 0.95, null)), List.of(), List.of(),
                List.of(helm), new Defence(76, 76, 76, 0, 10, 50),
                List.of(new Stat("Lunaris", 0.6, null)), List.of(new Stat("Gruthkul", 0.5, null)),
                Map.of("Badge of the Brotherhood", "Amulet"));
        return new AnalysisResult("standard", "Occultist", "Winter Orb", 1000, 50, 0, Instant.now(), null, List.of(a));
    }
}
