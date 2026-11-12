package it.poemeta.compare;

/** Errore di importazione da mostrare all'utente: {@code key} è la chiave del testo tradotto. */
public class ImportException extends RuntimeException {

    private final String key;
    private final transient Object[] args;

    public ImportException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args;
    }

    public ImportException(String key, Throwable cause, Object... args) {
        super(key, cause);
        this.key = key;
        this.args = args;
    }

    public String getKey() {
        return key;
    }

    public Object[] getArgs() {
        return args;
    }
}
