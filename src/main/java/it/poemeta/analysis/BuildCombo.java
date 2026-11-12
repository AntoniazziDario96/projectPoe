package it.poemeta.analysis;

/**
 * Una coppia skill + ascendancy presente nella lega.
 *
 * @param count             personaggi che usano la skill con quell'ascendancy
 * @param ascendancyTotal   personaggi totali dell'ascendancy
 * @param leagueTotal       personaggi totali della lega
 */
public record BuildCombo(String ascendancy, String skill, int count, long ascendancyTotal, long leagueTotal) {

    /** Quota sui personaggi dell'ascendancy (%). */
    public double ascendancyShare() {
        return ascendancyTotal == 0 ? 0 : count * 100.0 / ascendancyTotal;
    }

    /** Quota sui personaggi della lega (%). */
    public double leagueShare() {
        return leagueTotal == 0 ? 0 : count * 100.0 / leagueTotal;
    }

    public String key() {
        return ascendancy + "|" + skill;
    }
}
