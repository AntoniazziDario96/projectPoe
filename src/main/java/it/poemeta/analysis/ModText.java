package it.poemeta.analysis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility per i testi dei mod: "+112 to maximum Life" diventa "+# to maximum Life". */
public final class ModText {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private ModText() {
    }

    public static String normalize(String mod) {
        return NUMBER.matcher(mod.trim()).replaceAll("#");
    }

    public static Double firstNumber(String mod) {
        Matcher m = NUMBER.matcher(mod);
        return m.find() ? Double.parseDouble(m.group()) : null;
    }
}
