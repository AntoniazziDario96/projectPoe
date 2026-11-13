package it.poemeta.analysis;

import it.poemeta.analysis.TrendService.PairChange;
import it.poemeta.analysis.TrendService.PriceInfo;
import it.poemeta.analysis.TrendService.UniqueTrend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrendServiceTest {

    @Test
    void risingUsageWithFlatPriceIsABuy() {
        List<PairChange> changes = List.of(
                new PairChange("Occultist", "Winter Orb",
                        Map.of("Badge", 100.0, "Old Belt", 200.0), Map.of("Badge", 160.0, "Old Belt", 120.0), Instant.now()),
                new PairChange("Elementalist", "Arc",
                        Map.of("Badge", 50.0), Map.of("Badge", 60.0), Instant.now()));
        Map<String, PriceInfo> prices = Map.of(
                "Badge", new PriceInfo(null, 105.0, 100.0, false),     // +5%: fermo
                "Old Belt", new PriceInfo(null, 50.0, 50.0, false));   // fermo
        List<UniqueTrend> all = TrendService.compute(changes, prices::get);

        UniqueTrend badge = all.stream().filter(t -> t.name().equals("Badge")).findFirst().orElseThrow();
        assertThat(badge.playersBefore()).isEqualTo(150.0);
        assertThat(badge.playersNow()).isEqualTo(220.0);
        assertThat(badge.usageChange()).isCloseTo(46.67, org.assertj.core.data.Offset.offset(0.01));
        assertThat(badge.priceChange()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(badge.builds()).containsExactly("Winter Orb · Occultist", "Arc · Elementalist");
        assertThat(TrendService.isBuy(badge)).isTrue();

        UniqueTrend belt = all.stream().filter(t -> t.name().equals("Old Belt")).findFirst().orElseThrow();
        assertThat(belt.usageChange()).isEqualTo(-40.0);
        assertThat(TrendService.isSell(belt)).isTrue();
    }

    @Test
    void priceAlreadyMovedOrFewPlayersIsIgnored() {
        List<PairChange> changes = List.of(new PairChange("A", "S",
                Map.of("Pumped", 100.0, "Tiny", 5.0), Map.of("Pumped", 150.0, "Tiny", 20.0), Instant.now()));
        Map<String, PriceInfo> prices = Map.of(
                "Pumped", new PriceInfo(null, 200.0, 100.0, false),
                "Tiny", new PriceInfo(null, 10.0, 10.0, false));
        List<UniqueTrend> all = TrendService.compute(changes, prices::get);
        assertThat(all).extracting(UniqueTrend::name).containsExactly("Pumped"); // Tiny: troppo pochi giocatori
        assertThat(TrendService.isBuy(all.get(0))).isFalse(); // prezzo già raddoppiato
    }
}
