package it.poemeta.ninja;

/**
 * poe.ninja (Cloudflare) ha risposto 429 chiedendo di aspettare a lungo.
 * Chi chiama decide come gestire la pausa (es. l'analisi si ferma e riprende dopo).
 */
public class NinjaRateLimitException extends NinjaException {

    private final long retryAfterMs;

    public NinjaRateLimitException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
