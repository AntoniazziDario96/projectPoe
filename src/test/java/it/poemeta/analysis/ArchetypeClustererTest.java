package it.poemeta.analysis;

import it.poemeta.analysis.AnalysisResult.Archetype;
import it.poemeta.analysis.CharacterProfile.ItemRef;
import it.poemeta.analysis.CharacterProfile.RareItem;
import it.poemeta.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchetypeClustererTest {

    private static final AppProperties.Archetypes CFG =
            new AppProperties.Archetypes(0.55, 0.35, 0.35, 0.30, 0.70, 0.25, 0.05);

    @Test
    void jaccard() {
        assertThat(ArchetypeClusterer.jaccard(Set.of(1, 2, 3), Set.of(2, 3, 4))).isEqualTo(0.5);
        assertThat(ArchetypeClusterer.jaccard(Set.of(), Set.of())).isEqualTo(1.0);
        assertThat(ArchetypeClusterer.jaccard(Set.of(1), Set.of(2))).isEqualTo(0.0);
    }

    @Test
    void separatesTwoDistinctVariants() {
        List<CharacterProfile> profiles = new ArrayList<>();
        // variante A: 30 personaggi, supporti e albero "A"
        for (int i = 0; i < 30; i++) {
            profiles.add(profile("a" + i, List.of("Fire Trap", "Swift Affliction Support", "Burning Damage Support"),
                    range(0, 100, i), "Cloak of Flame"));
        }
        // variante B: 10 personaggi, supporti e albero diversi
        for (int i = 0; i < 10; i++) {
            profiles.add(profile("b" + i, List.of("Fire Trap", "Trap and Mine Damage Support", "Cluster Traps Support"),
                    range(500, 600, i), "Hrimsorrow"));
        }

        List<Archetype> result = new ArchetypeClusterer(CFG).cluster(profiles, "Fire Trap");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).size()).isEqualTo(30);
        assertThat(result.get(1).size()).isEqualTo(10);
        assertThat(result.get(0).supports()).extracting(AnalysisResult.Stat::name)
                .contains("Swift Affliction Support");
        assertThat(result.get(0).representative().name()).startsWith("a");
        assertThat(result.get(1).uniques()).extracting(AnalysisResult.Stat::name).contains("Hrimsorrow");
        assertThat(result.get(0).rareSlots()).isNotEmpty();
        assertThat(result.get(0).rareSlots().get(0).mods()).extracting(AnalysisResult.ModStat::text)
                .contains("+# to maximum Life");
    }

    @Test
    void smallGroupsBecomeExperimental() {
        List<CharacterProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            profiles.add(profile("a" + i, List.of("RF", "Efficacy Support"), range(0, 100, i), "X"));
        }
        profiles.add(profile("odd", List.of("RF", "Other Support"), range(900, 950, 0), "Y"));
        List<Archetype> result = new ArchetypeClusterer(CFG).cluster(profiles, "RF");
        assertThat(result).hasSize(2);
        assertThat(result.get(1).label()).isEqualTo("Varianti sperimentali");
        assertThat(result.get(1).minor()).isTrue();
    }

    @Test
    void findsSupportsOfVaalVersion() {
        CharacterProfile p = new CharacterProfile("acc", "x", 90, "Chieftain", null, 1, 0, null,
                List.of(List.of("Shield Charge", "Faster Attacks Support"),
                        List.of("Vaal Righteous Fire", "Burning Damage Support", "Efficacy Support")),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null);
        assertThat(p.supportsOf("Righteous Fire")).containsExactly("Burning Damage Support", "Efficacy Support");
    }

    /** Nodi da from a to, con qualche variazione per personaggio. */
    private static List<Integer> range(int from, int to, int seed) {
        List<Integer> nodes = new ArrayList<>(IntStream.range(from, to).boxed().toList());
        nodes.remove(Integer.valueOf(from + seed % (to - from)));
        return nodes;
    }

    private static CharacterProfile profile(String name, List<String> mainGroup, List<Integer> passives, String unique) {
        return new CharacterProfile("acc-" + name, name, 95, "Chieftain", null, 5000, 0, "pob",
                List.of(mainGroup), passives, List.of(), List.of(), List.of(new ItemRef(unique, null, "Helm")),
                List.of(new RareItem("Helm", "Hubris Circlet", "Doom Crown", null,
                        List.of("+" + (90 + name.length()) + " to maximum Life", "+40% to Fire Resistance"))),
                null, null, null);
    }
}
