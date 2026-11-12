package it.poemeta.ninja;

public class NinjaException extends RuntimeException {

    public NinjaException(String message) {
        super(message);
    }

    public NinjaException(String message, Throwable cause) {
        super(message, cause);
    }
}
