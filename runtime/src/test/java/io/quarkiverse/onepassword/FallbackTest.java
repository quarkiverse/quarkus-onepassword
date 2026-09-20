package io.quarkiverse.onepassword;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.config.*;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class FallbackTest {
    @TempDir
    Path dir;
    final List<LogRecord> logs = new ArrayList<>();
    final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("io.quarkiverse.onepassword.config");
    final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLogs() {
        logger.addHandler(capture);
    }

    @AfterEach
    void stopCapture() {
        logger.removeHandler(capture);
    }

    SmallRyeConfig config(String body, Map<String, String> properties) throws Exception {
        Path op = dir.resolve("op");
        Files.writeString(op, "#!/bin/sh\n" + body + "\n");
        assertTrue(op.toFile().setExecutable(true));
        var values = new HashMap<>(properties);
        values.put("onepassword.cli-path", op.toString());
        return new OnePasswordRuntimeConfigBuilder().configBuilder(new SmallRyeConfigBuilder().addDefaultInterceptors())
                .withDefaultValues(values).build();
    }

    @Test
    void literalDefaultWarnsWithoutExposingValues() throws Exception {
        var c = config("printf SENSITIVE >&2; exit 7", Map.of(
                "vault.password", "${op::op://v/i/p}", "app.password", "${vault.password:PRIVATE-DEFAULT}"));
        assertEquals("PRIVATE-DEFAULT", c.getValue("app.password", String.class));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel().intValue() == Level.WARNING.intValue()));
        assertFalse(logs.stream().map(r -> r.getMessage() + Arrays.toString(r.getParameters())).toList().toString()
                .contains("SENSITIVE"));
        assertFalse(logs.stream().map(r -> r.getMessage() + Arrays.toString(r.getParameters())).toList().toString()
                .contains("PRIVATE-DEFAULT"));
    }

    @Test
    void missingDefaultReportsPropertyAndLookupReason() throws Exception {
        var c = config("exit 7", Map.of("vault.password", "${op::op://v/i/p}", "app.password", "${vault.password}"));
        var error = assertThrows(OnePasswordException.class, () -> c.getValue("app.password", String.class));
        assertTrue(error.getMessage().contains("app.password"));
        assertTrue(error.getMessage().contains("exit 7"));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()));
    }

    @Test
    void directRequiredSecretPreservesReason() throws Exception {
        var c = config("exit 7", Map.of("app.password", "${op::op://v/i/p}"));
        var error = assertThrows(OnePasswordException.class, () -> c.getValue("app.password", String.class));
        assertTrue(error.getMessage().contains("exit 7"));
    }

    @Test
    void nestedDefaultsResolveThroughSmallRye() throws Exception {
        var c = config("exit 7", Map.of("vault.password", "${op::op://v/i/p}",
                "app.password", "${vault.password:${alternative:local}}", "alternative", "second"));
        assertEquals("second", c.getValue("app.password", String.class));
    }

    @Test
    void successPreservesExpressionLookingSecretData() throws Exception {
        var c = config("printf '%s' '${MISSING}:$${literal}  '", Map.of(
                "vault.password", "${op::op://v/i/p}", "app.password", "${vault.password:local}"));
        assertEquals("${MISSING}:$${literal}  ", c.getValue("app.password", String.class));
        assertTrue(logs.isEmpty());
    }

    @Test
    void explicitOverrideAvoidsLookupAndWarnings() throws Exception {
        var c = config("exit 7", Map.of("vault.password", "${op::op://v/i/p}",
                "app.password", "${OVERRIDE:${vault.password:local}}", "OVERRIDE", "override"));
        assertEquals("override", c.getValue("app.password", String.class));
        assertTrue(logs.isEmpty());
    }

    @Test
    void malformedReferenceIsNotHiddenByDefault() throws Exception {
        var c = config("exit 7", Map.of("vault.password", "${op::invalid}", "app.password", "${vault.password:local}"));
        assertThrows(OnePasswordException.class, () -> c.getValue("app.password", String.class));
        assertTrue(logs.stream().anyMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()));
    }

    @Test
    void timeoutCanUseDefault() throws Exception {
        var c = config("exec sleep 10", Map.of("onepassword.timeout", "PT0.1S",
                "vault.password", "${op::op://v/i/p}", "app.password", "${vault.password:local}"));
        assertEquals("local", c.getValue("app.password", String.class));
    }

    @Test
    void disabledExpressionsDoNotLookUpSecrets() throws Exception {
        var c = config("exit 7", Map.of(org.eclipse.microprofile.config.Config.PROPERTY_EXPRESSIONS_ENABLED, "false",
                "vault.password", "${op::op://v/i/p}"));
        assertEquals("${op::op://v/i/p}", c.getValue("vault.password", String.class));
        assertTrue(logs.isEmpty());
    }
}
