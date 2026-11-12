package it.poemeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tutte le impostazioni modificabili da application.yml (prefisso "poemeta").
 */
@ConfigurationProperties(prefix = "poemeta")
public record AppProperties(
        String userAgent,
        long requestDelayMs,
        String defaultLeague,
        Builds builds,
        Archetypes archetypes,
        Economy economy) {

    public record Builds(
            int sampleSize,
            int extendSize,
            int minCharacters,
            long characterDelayMs,
            int combosRefreshHours,
            int favoritesRefreshHours,
            int characterCacheHours) {
    }

    public record Archetypes(
            double similarityThreshold,
            double weightGems,
            double weightTree,
            double weightItems,
            double coreThreshold,
            double flexThreshold,
            double minorShare) {
    }

    public record Economy(
            int refreshMinutes,
            int historyEveryMinutes,
            int historyDays,
            int minListings,
            double minChaosCurrency,
            double minChaosUnique) {
    }
}
