package io.quarkiverse.onepassword;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class ConfigIntegrationTest {
    @TempDir Path dir;
    @ConfigMapping(prefix = "demo")
    public interface DemoConfig { String secret(); }

    private SmallRyeConfigBuilder builder() {
        return new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withSecretKeyHandlerFactories(new OnePasswordSecretKeysHandlerFactory());
    }
    @Test void resolvesExpressionsAndConfigMappings() throws Exception {
        Path op = dir.resolve("fake op");
        Files.writeString(op, "#!/bin/sh\nprintf 'mapped-secret'\n");
        assertTrue(op.toFile().setExecutable(true));
        SmallRyeConfig config = builder().withMapping(DemoConfig.class).withDefaultValues(Map.of(
                "demo.secret", "${op::op://v/i/password}", "onepassword.cli-path", op.toString())).build();
        assertEquals("mapped-secret", config.getValue("demo.secret", String.class));
        assertEquals("mapped-secret", config.getConfigMapping(DemoConfig.class).secret());
    }
    @Test void unusedAndOverriddenReferencesNeverExecuteCli() {
        SmallRyeConfig config = builder().withDefaultValues(Map.of(
                "unused.secret", "${op::op://v/i/password}",
                "demo.secret", "${op::op://v/i/password}",
                "onepassword.cli-path", dir.resolve("does-not-exist").toString()))
                .withSources(new MapBackedConfigSource("override", Map.of("demo.secret", "override"), 300) {})
                .build();
        assertEquals("override", config.getValue("demo.secret", String.class));
        assertThrows(OnePasswordException.class, () -> config.getValue("unused.secret", String.class));
    }
    @Test void runtimeBuilderRegistersHandler() throws Exception {
        Path op = dir.resolve("op");
        Files.writeString(op, "#!/bin/sh\nprintf ok\n");
        assertTrue(op.toFile().setExecutable(true));
        var builder = new OnePasswordRuntimeConfigBuilder().configBuilder(new SmallRyeConfigBuilder().addDefaultInterceptors());
        var config = builder.withDefaultValues(Map.of("demo.secret", "${op::op://v/i/p}",
                "onepassword.cli-path", op.toString())).build();
        assertEquals("ok", config.getValue("demo.secret", String.class));
    }
}
