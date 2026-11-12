package it.poemeta.analysis;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility per i testi dei mod: "+112 to maximum Life" diventa "+# to maximum Life". */
public final class ModText {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern SINGLE_RES = Pattern.compile(
            "^\\+?(-?\\d+(?:\\.\\d+)?)% to (Fire|Cold|Lightning|Chaos) Resistance$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUAL_RES = Pattern.compile(
            "^\\+?(-?\\d+(?:\\.\\d+)?)% to (Fire|Cold|Lightning) and (Fire|Cold|Lightning) Resistances$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_RES = Pattern.compile(
            "^\\+?(-?\\d+(?:\\.\\d+)?)% to all Elemental Resistances$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRE_CHAOS_LIKE = Pattern.compile(
            "^\\+?(-?\\d+(?:\\.\\d+)?)% to (Fire|Cold|Lightning) and Chaos Resistances$", Pattern.CASE_INSENSITIVE);

    private ModText() {
    }

    public static String normalize(String mod) {
        return NUMBER.matcher(mod.trim()).replaceAll("#");
    }

    public static Double firstNumber(String mod) {
        Matcher m = NUMBER.matcher(mod);
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    /**
     * Valore del mod come lo intende il trade: il numero, oppure la media dei numeri
     * per i mod con più valori (es. "Adds 10 to 20 Fire Damage" vale 15).
     */
    public static Double value(String mod) {
        Matcher m = NUMBER.matcher(mod);
        double sum = 0;
        int n = 0;
        while (m.find()) {
            sum += Double.parseDouble(m.group());
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /** Il mod dà resistenze (elementali o chaos)? Questi mod si cercano con il totale "pseudo". */
    public static boolean isResistance(String mod) {
        // accetta sia il testo reale ("+40% to ...") sia quello normalizzato ("+#% to ...")
        String t = mod.trim().replace("#", "1");
        return SINGLE_RES.matcher(t).matches() || DUAL_RES.matcher(t).matches()
                || ALL_RES.matcher(t).matches() || FIRE_CHAOS_LIKE.matcher(t).matches();
    }

    /**
     * Resistenze date da un insieme di mod, contate come il totale "pseudo" del trade:
     * "all Elemental" vale tre volte, le doppie due volte.
     */
    public record Resists(double elemental, double chaos) {
    }

    public static Resists resists(List<String> mods) {
        double ele = 0;
        double chaos = 0;
        for (String raw : mods) {
            String mod = raw.trim();
            Matcher m = SINGLE_RES.matcher(mod);
            if (m.matches()) {
                double v = Double.parseDouble(m.group(1));
                if ("chaos".equals(m.group(2).toLowerCase(Locale.ROOT))) {
                    chaos += v;
                } else {
                    ele += v;
                }
                continue;
            }
            m = DUAL_RES.matcher(mod);
            if (m.matches()) {
                ele += 2 * Double.parseDouble(m.group(1));
                continue;
            }
            m = ALL_RES.matcher(mod);
            if (m.matches()) {
                ele += 3 * Double.parseDouble(m.group(1));
                continue;
            }
            m = FIRE_CHAOS_LIKE.matcher(mod);
            if (m.matches()) {
                double v = Double.parseDouble(m.group(1));
                ele += v;
                chaos += v;
            }
        }
        return new Resists(ele, chaos);
    }
}
