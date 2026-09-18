package io.quarkiverse.onepassword;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

public final class OnePasswordRuntimeConfigBuilder implements ConfigBuilder {
    @Override
    public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
        OnePasswordFallback fallback = new OnePasswordFallback();
        return builder.withSecretKeyHandlerFactories(new OnePasswordSecretKeysHandlerFactory())
                .withInterceptorFactories(fallback.lookup(), fallback.diagnostics());
    }
}
