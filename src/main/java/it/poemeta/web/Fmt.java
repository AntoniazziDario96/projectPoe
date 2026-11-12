package it.poemeta.web;

import it.poemeta.persistence.PriceSnapshotEntity;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Funzioni di formattazione usate nei template come {@code @fmt.xxx(...)}.
 * Numeri e testi seguono la lingua scelta dall'utente (italiano o inglese).
 */
@Component("fmt")
public class Fmt {

    /** Etichetta salvata dalle analisi per il gruppo dei personaggi "avanzati". */
    private static final String LEFTOVERS_LABEL = "Varianti sperimentali";
    private static final String GENERIC_LABEL = "Archetipo";

    private final MessageSource messages;

    public Fmt(MessageSource messages) {
        this.messages = messages;
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }

    private String msg(String key, String fallback, Object... args) {
        return messages.getMessage(key, args, fallback, locale());
    }

    public String chaos(Double v) {
        if (v == null) {
            return "–";
        }
        double a = Math.abs(v);
        if (a >= 100_000) {
            return String.format(locale(), "%.0fk c", v / 1000);
        }
        if (a >= 10_000) {
            return String.format(locale(), "%.1fk c", v / 1000);
        }
        if (a >= 100) {
            return String.format(locale(), "%.0f c", v);
        }
        if (a >= 1) {
            return String.format(locale(), "%.1f c", v);
        }
        return String.format(locale(), "%.2f c", v);
    }

    public String divine(Double v) {
        if (v == null) {
            return "–";
        }
        return String.format(locale(), v >= 10 ? "%.0f div" : "%.1f div", v);
    }

    /** Prezzo in chaos con l'equivalente in divine se conviene. */
    public String price(Double chaos, Double divinePrice) {
        if (chaos == null) {
            return "–";
        }
        if (divinePrice != null && divinePrice > 0 && chaos >= divinePrice) {
            return divine(chaos / divinePrice);
        }
        return chaos(chaos);
    }

    public String pct(Double v) {
        if (v == null || v.isNaN()) {
            return "–";
        }
        return String.format(locale(), "%.0f%%", v * 100);
    }

    /** Variazione già espressa in percentuale (es. 12.5 = +12,5%). */
    public String change(Double v) {
        if (v == null || v.isNaN()) {
            return "–";
        }
        return String.format(locale(), "%+.1f%%", v);
    }

    public String changeClass(Double v) {
        if (v == null || v.isNaN() || Math.abs(v) < 0.05) {
            return "neutral";
        }
        return v > 0 ? "up" : "down";
    }

    public String num(Number v) {
        if (v == null) {
            return "–";
        }
        return String.format(locale(), "%,d", v.longValue());
    }

    public String decimal(Double v) {
        return v == null ? "–" : String.format(locale(), "%.1f", v);
    }

    public String slot(String slot) {
        return slot == null ? "" : msg("slot." + slot, slot);
    }

    public String support(String gem) {
        return gem == null ? "" : gem.replace(" Support", "");
    }

    public String ago(Instant t) {
        if (t == null) {
            return msg("ago.never", "mai");
        }
        Duration d = Duration.between(t, Instant.now());
        if (d.toMinutes() < 1) {
            return msg("ago.now", "adesso");
        }
        if (d.toMinutes() < 60) {
            return msg("ago.minutes", d.toMinutes() + " min", d.toMinutes());
        }
        if (d.toHours() < 48) {
            return msg("ago.hours", d.toHours() + " h", d.toHours());
        }
        return msg("ago.days", d.toDays() + " d", d.toDays());
    }

    /** Nome della categoria di un prezzo (es. "UniqueWeapon"). */
    public String category(String category) {
        return category == null ? "" : msg("category." + category, category);
    }

    /** Nome di un archetipo: traduce le etichette generiche, lascia com'è il resto (nomi di gemme e item). */
    public String archetype(String label) {
        if (LEFTOVERS_LABEL.equals(label)) {
            return msg("archetype.leftovers", label);
        }
        if (GENERIC_LABEL.equals(label)) {
            return msg("archetype.generic", label);
        }
        return label;
    }

    /**
     * Stato di un'analisi. Il messaggio è salvato come {@code chiave|arg1|arg2} (es. {@code status.done|200|4});
     * i messaggi salvati dalle versioni precedenti (testo libero) vengono mostrati così come sono.
     */
    public String status(String raw) {
        if (raw == null) {
            return "";
        }
        if (!raw.startsWith("status.")) {
            return raw;
        }
        int bar = raw.indexOf('|');
        String key = bar < 0 ? raw : raw.substring(0, bar);
        Object[] args;
        if (bar < 0) {
            args = new Object[0];
        } else if ("status.error".equals(key)) {
            args = new Object[]{raw.substring(bar + 1)};
        } else {
            args = raw.substring(bar + 1).split("\\|", -1);
        }
        return msg(key, raw, args);
    }

    /** Punti di una sparkline SVG (viewBox 0 0 100 30) da una serie di valori. */
    public String sparkline(List<Double> values) {
        if (values == null || values.size() < 2) {
            return "";
        }
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double range = max - min == 0 ? 1 : max - min;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            double x = i * 100.0 / (values.size() - 1);
            double y = 28 - (values.get(i) - min) / range * 26;
            sb.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return sb.toString().trim();
    }

    public String historySparkline(List<PriceSnapshotEntity> history) {
        return sparkline(history == null ? null : history.stream().map(PriceSnapshotEntity::getChaos).toList());
    }
}
