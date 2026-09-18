package io.quarkiverse.onepassword;

/** A secret resolver. Implementations must not include secret values in diagnostics. */
@FunctionalInterface
public interface OnePasswordResolver {
    String resolve(String reference);
}
