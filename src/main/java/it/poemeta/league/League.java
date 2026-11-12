package it.poemeta.league;

/**
 * Una lega di Path of Exile 1.
 *
 * @param name            nome visualizzato e usato per i prezzi (es. "Mirage")
 * @param url             identificativo usato da poe.ninja per le build (es. "mirage")
 * @param snapshotVersion versione dello snapshot build di poe.ninja
 * @param economyName     nome per i prezzi, null se poe.ninja non ha i prezzi per questa lega (es. SSF)
 * @param characters      personaggi indicizzati da poe.ninja
 */
public record League(String name, String url, String snapshotVersion, String economyName,
                     boolean hardcore, long characters) {

    public boolean hasEconomy() {
        return economyName != null;
    }

    /** Data dello snapshot (es. 20260720) estratta dalla versione "2031-20260720-57744". */
    public String snapshotDate() {
        String[] parts = snapshotVersion == null ? new String[0] : snapshotVersion.split("-");
        return parts.length >= 2 ? parts[1] : "";
    }
}
