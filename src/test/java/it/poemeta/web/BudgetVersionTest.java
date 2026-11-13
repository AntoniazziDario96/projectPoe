package it.poemeta.web;

import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.AnalysisResult.Member;
import it.poemeta.analysis.AnalysisResult.Stat;
import it.poemeta.web.BuildViewService.BudgetVersion;
import it.poemeta.web.BuildViewService.PricedStat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetVersionTest {

    private static final Map<String, Double> PRICES = Map.of("Mageblood", 40000.0, "Badge", 100.0, "Belt", 5.0);

    @Test
    void picksTheCheapestTypicalCharacter() {
        Archetype a = archetype(List.of(
                new Member("acc", "Rich", 98, 1, 8000, 0.80, List.of("Mageblood", "Badge")),
                new Member("acc", "Typical", 97, 1, 7500, 0.75, List.of("Mageblood", "Badge")),
                new Member("acc", "Cheap", 96, 1, 7000, 0.70, List.of("Badge", "Belt")),
                new Member("acc", "Weird", 97, 1, 7000, 0.20, List.of()),     // poco simile: escluso
                new Member("acc", "Lowbie", 80, 1, 3000, 0.90, List.of())));  // livello troppo basso: escluso
        BudgetVersion b = BuildViewService.budgetVersion(a, PRICES::get, "https://x/");

        assertThat(b.member().name()).isEqualTo("Cheap");
        assertThat(b.cost()).isEqualTo(105.0);
        assertThat(b.typicalCost()).isEqualTo(40100.0);
        assertThat(b.saving()).isGreaterThan(0.99);
        assertThat(b.skips()).extracting(PricedStat::name).containsExactly("Mageblood");
        assertThat(b.uses()).extracting(PricedStat::name).containsExactly("Badge", "Belt");
        assertThat(b.profileUrl()).isEqualTo("https://x/acc/Cheap");
    }

    @Test
    void nothingWithoutMembersOrPrices() {
        assertThat(BuildViewService.budgetVersion(archetype(null), PRICES::get, null)).isNull();
        Archetype a = archetype(List.of(new Member("a", "b", 97, 1, 1, 1, List.of("Unknown"))));
        assertThat(BuildViewService.budgetVersion(a, name -> null, null)).isNull();
    }

    private static Archetype archetype(List<Member> members) {
        return new Archetype(1, "X", 5, 1, false, 0.7, null, 95, 1, 7000,
                List.of(), List.of(), List.of(new Stat("Mageblood", 0.6, null), new Stat("Badge", 0.9, null)),
                List.of(), List.of(), List.of(), List.of(), null, null, null, Map.of(), members);
    }
}
