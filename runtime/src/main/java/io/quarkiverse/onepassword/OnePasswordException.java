package io.quarkiverse.onepassword;

/** Carries a sanitized reason only; never subprocess output or an underlying cause. */
public final class OnePasswordException extends RuntimeException {
    private final boolean allowsFallback;

    public OnePasswordException(String message) {
        this(message, true);
    }

    public OnePasswordException(String message, boolean allowsFallback) {
        super(message);
        this.allowsFallback = allowsFallback;
    }

    public boolean allowsFallback() {
        return allowsFallback;
    }
}
