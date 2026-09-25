package io.quarkiverse.onepassword;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class CliOnePasswordResolverTest {
    @TempDir
    Path dir;
    private java.util.Set<Thread> initialThreads;

    @org.junit.jupiter.api.BeforeEach
    void recordThreads() {
        initialThreads = Thread.getAllStackTraces().keySet();
    }

    @org.junit.jupiter.api.AfterEach
    void noLeakedProcessThreads() throws Exception {
        for (int i = 0; i < 100; i++) {
            var lingering = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("process-") && !initialThreads.contains(t)).toList();
            if (lingering.isEmpty())
                return;
            Thread.sleep(20);
        }
        var lingering = Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey().getName().startsWith("process-") && !initialThreads.contains(e.getKey()))
                .map(e -> e.getKey().getName() + java.util.Arrays.toString(e.getValue())).toList();
        assertTrue(lingering.isEmpty(), () -> "Lingering SmallRye process threads: " + lingering);
    }

    private CliOnePasswordResolver cli(String body, Duration timeout) throws Exception {
        Path op = dir.resolve("fake op");
        Files.writeString(op, "#!/bin/sh\n" + body + "\n");
        assertTrue(op.toFile().setExecutable(true));
        return new CliOnePasswordResolver(op.toString(), "my-account", timeout);
    }

    @Test
    void preservesWhitespaceAndPassesArgumentsLiterally() throws Exception {
        String ref = "op://Vault/$(touch should-never-exist)/password";
        var resolver = cli(
                "[ \"$1\" = read ] && [ \"$2\" = --no-newline ] && [ \"$3\" = --account ] && [ \"$4\" = my-account ] || exit 2\nprintf '%s' \"$5\"\nprintf '  \n'",
                Duration.ofSeconds(3));
        assertEquals(ref + "  \n", resolver.resolve(ref));
    }

    @Test
    void discardsStderrAndReportsOnlyExitCode() throws Exception {
        var resolver = cli("printf 'SENSITIVE-STDOUT'; printf 'SENSITIVE-STDERR' >&2; exit 7", Duration.ofSeconds(10));
        var error = assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password"));
        assertTrue(error.getMessage().contains("exit 7"));
        assertFalse(error.toString().contains("SENSITIVE"));
        assertNull(error.getCause());
    }

    @Test
    void timesOutAndKillsProcess() throws Exception {
        Path pid = dir.resolve("pid");
        var resolver = cli("echo $$ > '" + pid + "'\nexec sleep 30", Duration.ofMillis(200));
        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password")));
        assertTrue(error.getMessage().contains("timed out"));
        long id = Long.parseLong(Files.readString(pid).trim());
        for (int i = 0; i < 20 && ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false); i++)
            Thread.sleep(20);
        assertFalse(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void interruptionIsPreserved() throws Exception {
        var resolver = cli("exec sleep 30", Duration.ofSeconds(10));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(pool.submit(() -> {
                Thread.currentThread().interrupt();
                assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password"));
                return Thread.currentThread().isInterrupted();
            }).get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingExecutableIsSanitized() {
        var resolver = new CliOnePasswordResolver(dir.resolve("missing").toString(), null, Duration.ofSeconds(1));
        var error = assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password"));
        assertTrue(error.getMessage().contains("Cannot start"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsMalformedAndOtpReferences() {
        for (String ref : new String[] { "password", "--help", "op://v/i", "op://v//p", "op://v/i/p\n",
                "op://v/i/otp?attribute=otp" })
            assertThrows(OnePasswordException.class, () -> CliOnePasswordResolver.validateReference(ref));
    }

    @Test
    void rejectsOversizedOutput() throws Exception {
        var resolver = cli("head -c 1048577 /dev/zero", Duration.ofSeconds(3));
        assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password"));
    }

    @Test
    void interruptionDuringLookupTerminatesProcess() throws Exception {
        Path pid = dir.resolve("running-pid");
        var resolver = cli("echo $$ > '" + pid + "'\nexec sleep 30", Duration.ofSeconds(10));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                resolver.resolve("op://v/i/password");
            } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            for (int i = 0; i < 100 && !Files.exists(pid); i++)
                Thread.sleep(20);
            assertTrue(Files.exists(pid), "CLI must be running before interruption");
            caller.interrupt();
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertInstanceOf(OnePasswordException.class, failure.get());
            assertTrue(interrupted.get());
            assertProcessExited(Long.parseLong(Files.readString(pid).trim()));
        } finally {
            caller.interrupt();
            caller.join(3000);
        }
    }

    @Test
    void timeoutTerminatesChildProcessesToo() throws Exception {
        // Some containers expose a host /proc inside a different PID namespace.
        // Java can start/kill direct processes there but cannot enumerate children.
        Process probe = new java.lang.ProcessBuilder("sleep", "1").start();
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    ProcessHandle.current().children().anyMatch(p -> p.pid() == probe.pid()),
                    "Environment does not expose subprocess PIDs to Java ProcessHandle");
        } finally {
            probe.destroyForcibly();
            probe.waitFor();
        }
        Path pid = dir.resolve("child-pid");
        var resolver = cli("sleep 30 &\necho $! > '" + pid + "'\nwait", Duration.ofMillis(500));
        assertThrows(OnePasswordException.class, () -> resolver.resolve("op://v/i/password"));
        assertProcessExited(Long.parseLong(Files.readString(pid).trim()));
    }

    private static void assertProcessExited(long pid) throws InterruptedException {
        for (int i = 0; i < 100 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++)
            Thread.sleep(20);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
