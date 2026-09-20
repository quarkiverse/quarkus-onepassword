package io.quarkiverse.onepassword.example;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Runs the packaged artifact, ensuring this is a runtime lookup, not a test-mode shortcut. */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class ExampleIT {
    @TempDir
    Path dir;

    @Test
    void packagedApplicationLoadsSecretOnlyAtRuntime() throws Exception {
        Path marker = dir.resolve("invoked");
        Path op = dir.resolve("fake op");
        Files.writeString(op, "#!/bin/sh\nprintf called > '" + marker
                + "'\nprintf 'test-value-never-print'; printf 'stderr-never-print' >&2\n");
        assertTrue(op.toFile().setExecutable(true));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Donepassword.cli-path=" + op, "-jar", "target/quarkus-app/quarkus-run.jar")
                .redirectErrorStream(true).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(40), () -> assertTrue(child.waitFor(35, TimeUnit.SECONDS)));
            String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(Files.exists(marker));
            assertTrue(output.contains("1Password secret loaded successfully."), output);
            assertFalse(output.contains("test-value-never-print"));
            assertFalse(output.contains("stderr-never-print"));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void packagedFallbackWarnsAndStarts() throws Exception {
        checkFailure(true);
    }

    @Test
    void packagedMissingDefaultReportsCauseAndFails() throws Exception {
        checkFailure(false);
    }

    private void checkFailure(boolean withDefault) throws Exception {
        Path op = dir.resolve("failing-op");
        Files.writeString(op, "#!/bin/sh\nprintf 'DO-NOT-LOG-STDERR' >&2\nexit 7\n");
        assertTrue(op.toFile().setExecutable(true));
        Path outputFile = dir.resolve("output.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Donepassword.cli-path=" + op,
                "-Dvault.password=${op::op://v/i/p}",
                "-Ddemo.secret=" + (withDefault ? "${vault.password:PRIVATE-DEFAULT}" : "${vault.password}"),
                "-jar", "target/quarkus-app/quarkus-run.jar").redirectErrorStream(true)
                .redirectOutput(outputFile.toFile()).start();
        try {
            assertTrue(child.waitFor(35, TimeUnit.SECONDS));
            String output = Files.readString(outputFile);
            if (withDefault) {
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("OPCFG001"), output);
                assertTrue(output.contains("1Password secret loaded successfully."), output);
            } else {
                assertNotEquals(0, child.exitValue(), output);
                assertTrue(output.contains("OPCFG002"), output);
                assertTrue(output.contains("demo.secret"), output);
            }
            assertTrue(output.contains("exit 7"), output);
            assertFalse(output.contains("DO-NOT-LOG-STDERR"), output);
            assertFalse(output.contains("PRIVATE-DEFAULT"), output);
        } finally {
            child.destroyForcibly();
        }
    }
}
