package io.quarkiverse.onepassword;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.jboss.logging.Logger;

import io.smallrye.config.*;

/** Lets SmallRye's existing expression resolver choose defaults for unavailable secrets. */
final class OnePasswordFallback {
    private static final Logger LOG = Logger.getLogger("io.quarkiverse.onepassword.config");
    private static final String PREFIX = "${op::";
    private final ThreadLocal<Map<String, OnePasswordException>> failures = new ThreadLocal<>();

    ConfigSourceInterceptorFactory lookup() {
        return new ConfigSourceInterceptorFactory() {
            // Profiles first, then this lookup, then SmallRye expressions (LIBRARY + 300).
            @Override
            public OptionalInt getPriority() {
                return OptionalInt.of(Priorities.LIBRARY + 250);
            }

            @Override
            public ConfigSourceInterceptor getInterceptor(ConfigSourceInterceptorContext context) {
                ConfigValue expressionSetting = context
                        .proceed(org.eclipse.microprofile.config.Config.PROPERTY_EXPRESSIONS_ENABLED);
                boolean enabled = expressionSetting == null || Boolean.parseBoolean(expressionSetting.getValue());
                // Lazy-init: reading config during interceptor construction can trigger
                // expression resolution through the partially-built chain in newer SmallRye.
                OnePasswordResolver[] holder = new OnePasswordResolver[1];
                return (chain, name) -> {
                    if (holder[0] == null) {
                        holder[0] = OnePasswordSecretKeysHandlerFactory.createResolver(new ConfigSourceContext() {
                            @Override
                            public ConfigValue getValue(String n) {
                                return chain.proceed(n);
                            }

                            @Override
                            public Iterator<String> iterateNames() {
                                return chain.iterateNames();
                            }
                        });
                    }
                    OnePasswordResolver resolver = holder[0];
                    ConfigValue value = chain.proceed(name);
                    if (value == null || value.getValue() == null || !enabled || !Expressions.isEnabled())
                        return value;
                    String raw = value.getValue();
                    // Whole-property references only. Compound expressions retain the strict handler.
                    if (!raw.startsWith(PREFIX) || !raw.endsWith("}"))
                        return value;
                    String reference = raw.substring(PREFIX.length(), raw.length() - 1);
                    if (reference.contains("}"))
                        return value;
                    try {
                        String secret = resolver.resolve(reference);
                        // SmallRye expressions run after us. Never expand expression-looking secret data.
                        return value.withValue(secret.replace("$", "$$"));
                    } catch (OnePasswordException e) {
                        if (!e.allowsFallback())
                            throw e;
                        Map<String, OnePasswordException> current = failures.get();
                        if (current == null)
                            throw e; // Never lose a cause outside a tracked lookup.
                        current.put(name, e);
                        return null; // SmallRye's native ${property:default} decides what happens next.
                    }
                };
            }
        };
    }

    ConfigSourceInterceptorFactory diagnostics() {
        return new ConfigSourceInterceptorFactory() {
            @Override
            public OptionalInt getPriority() {
                return OptionalInt.of(Priorities.LIBRARY + 350);
            }

            @Override
            public ConfigSourceInterceptor getInterceptor(ConfigSourceInterceptorContext context) {
                return (chain, name) -> {
                    Map<String, OnePasswordException> previous = failures.get();
                    Map<String, OnePasswordException> current = new LinkedHashMap<>();
                    failures.set(current);
                    try {
                        ConfigValue result = chain.proceed(name);
                        if (current.isEmpty())
                            return result;
                        if (result != null && !result.hasProblems()) {
                            current.forEach((key, failure) -> LOG.warnf(
                                    "OPCFG001: 1Password lookup for property '%s' failed; expression default resolved '%s'. Reason: %s",
                                    safe(key), safe(name), failure.getMessage()));
                            return result;
                        }
                        StringBuilder reasons = new StringBuilder();
                        current.forEach((key, failure) -> {
                            String message = "OPCFG002: Cannot resolve configuration property '" + safe(name)
                                    + "': 1Password lookup for '" + safe(key)
                                    + "' failed and no usable default resolved it. Reason: "
                                    + failure.getMessage();
                            LOG.error(message);
                            if (!reasons.isEmpty())
                                reasons.append("; ");
                            reasons.append(message);
                        });
                        // Preserve the cause in thrown config/startup errors too, rather than
                        // leaving callers with only SmallRye's generic missing-property error.
                        throw new OnePasswordException(reasons.toString(), false);
                    } catch (OnePasswordException failure) {
                        if (current.isEmpty())
                            LOG.errorf("OPCFG003: 1Password configuration lookup for '%s' failed. Reason: %s",
                                    safe(name), failure.getMessage());
                        throw failure;
                    } finally {
                        if (previous == null)
                            failures.remove();
                        else
                            failures.set(previous);
                    }
                };
            }
        };
    }

    private static String safe(String key) {
        // Property names are useful diagnostics, but must not inject log lines.
        return key.replaceAll("\\p{Cntrl}", "?");
    }
}
