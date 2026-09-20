package io.quarkiverse.onepassword;

import java.time.Duration;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SecretKeysHandler;
import io.smallrye.config.SecretKeysHandlerFactory;

/** Registered only by the Quarkus runtime config builder, not through ServiceLoader. */
public final class OnePasswordSecretKeysHandlerFactory implements SecretKeysHandlerFactory {
    @Override
    public String getName() {
        return "op";
    }

    @Override
    public SecretKeysHandler getSecretKeysHandler(ConfigSourceContext context) {
        OnePasswordResolver resolver = createResolver(context);
        return new SecretKeysHandler() {
            @Override
            public String getName() {
                return "op";
            }

            @Override
            public String decode(String reference) {
                return resolver.resolve(reference);
            }
        };
    }

    static OnePasswordResolver createResolver(ConfigSourceContext context) {
        String command = value(context, "onepassword.cli-path", "op");
        String account = value(context, "onepassword.account", "");
        Duration timeout;
        try {
            timeout = Duration.parse(value(context, "onepassword.timeout", "PT60S"));
        } catch (RuntimeException e) {
            throw new OnePasswordException("onepassword.timeout must be an ISO-8601 duration, e.g. PT60S");
        }
        String cache = value(context, "onepassword.cache", "true");
        if (!cache.equalsIgnoreCase("true") && !cache.equalsIgnoreCase("false"))
            throw new OnePasswordException("onepassword.cache must be true or false");
        OnePasswordResolver cli = new CliOnePasswordResolver(command, account, timeout);
        return Boolean.parseBoolean(cache) ? new CachingOnePasswordResolver(cli, 256) : cli;
    }

    private static String value(ConfigSourceContext context, String key, String fallback) {
        ConfigValue value = context.getValue(key);
        return value == null || value.getValue() == null ? fallback : value.getValue();
    }
}
