package it.poemeta.economy;

/**
 * Una riga di prezzo (currency, frammento, unique o gemma) come la restituisce poe.ninja,
 * già normalizzata.
 *
 * @param category   tipo poe.ninja (Currency, Fragment, UniqueArmour, SkillGem, ...)
 * @param change7d   variazione % negli ultimi 7 giorni (null se non disponibile)
 * @param change24h  variazione % nelle ultime 24 ore, ricavata dalla sparkline (null se non disponibile)
 * @param links      numero di link (solo per armi/armature a 5-6 link), null altrimenti
 * @param variant    variante della gemma (es. "20/20", "21/23c"), null altrimenti
 * @param listings   offerte attive, oppure (se volumeBased) volume scambiato in chaos
 * @param volumeBased true se il prezzo viene dal Currency Exchange (listings = volume in chaos)
 */
public record PriceLine(
        String category,
        String name,
        String baseType,
        String icon,
        double chaos,
        Double change7d,
        Double change24h,
        int listings,
        Integer links,
        String variant,
        boolean lowConfidence,
        boolean volumeBased) {

    public boolean isCurrencyLike() {
        return "Currency".equals(category) || "Fragment".equals(category);
    }

    public boolean isUnique() {
        return category != null && category.startsWith("Unique");
    }

    public boolean isGem() {
        return "SkillGem".equals(category);
    }
}
