package io.quarkiverse.onepassword;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.smallrye.common.process.AbnormalExitException;
import io.smallrye.common.process.ProcessBuilder;
import io.smallrye.common.process.ProcessUtil;
import io.smallrye.common.process.WaitableProcessHandle;

/** Uses SmallRye Common Process with the caller's CLI session/environment. */
public final class CliOnePasswordResolver implements OnePasswordResolver {
    // UTF-16 code units, matching SmallRye's bounded string output collector.
    private static final int MAX_CHARS = 1024 * 1024;
    private final String executable;
    private final String account;
    private final Duration timeout;

    public CliOnePasswordResolver(String executable, String account, Duration timeout) {
        if (executable == null || executable.isBlank())
            throw new IllegalArgumentException("1Password executable is required");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("1Password timeout must be positive and at most PT10M");
        this.executable = executable;
        this.account = account;
        this.timeout = timeout;
    }

    @Override
    public String resolve(String reference) {
        validateReference(reference);
        if (Thread.currentThread().isInterrupted())
            throw new OnePasswordException("1Password lookup interrupted", false);
        List<String> arguments = new ArrayList<>(List.of("read", "--no-newline"));
        if (account != null && !account.isBlank()) {
            arguments.add("--account");
            arguments.add(account);
        }
        arguments.add(reference);

        // SmallRye owns threads, streams, process creation and process-tree destruction.
        CompletableFuture<WaitableProcessHandle> handle = new CompletableFuture<>();
        CompletableFuture<String> result;
        try {
            result = ProcessBuilder.newBuilder(executable, arguments)
                    .whileRunning(handle::complete)
                    .input().empty()
                    .error().logOnSuccess(false).gatherOnFail(false).discard()
                    .output().charset(StandardCharsets.UTF_8).gatherOnFail(false)
                    // The collector truncates: retain one extra character so we can reject
                    // oversized secrets instead of silently accepting a truncated password.
                    .toSingleString(MAX_CHARS + 1)
                    .runAsync();
        } catch (RuntimeException e) {
            throw new OnePasswordException("Cannot start 1Password CLI; install op or set onepassword.cli-path");
        }
        try {
            // SmallRye soft/hard exit timeouts begin AFTER I/O; use a deadline here
            // so a blocked CLI/desktop approval is also bounded. Future.cancel alone
            // does not terminate a SmallRye process.
            String secret = result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (secret.length() > MAX_CHARS)
                throw new OnePasswordException("1Password response exceeds 1048576 characters; output withheld");
            return secret;
        } catch (TimeoutException e) {
            handle.thenAccept(ProcessUtil::destroyAllForcibly);
            throw new OnePasswordException(
                    "1Password lookup timed out; authorize the desktop prompt or increase onepassword.timeout");
        } catch (InterruptedException e) {
            handle.thenAccept(ProcessUtil::destroyAllForcibly);
            Thread.currentThread().interrupt();
            throw new OnePasswordException("1Password lookup interrupted", false);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbnormalExitException exit)
                throw new OnePasswordException("1Password lookup failed (exit " + exit.exitCode()
                        + "); check authorization, account, vault and item access. CLI output withheld");
            throw new OnePasswordException("Cannot read 1Password response; output withheld");
        }
    }

    static void validateReference(String reference) {
        if (reference == null || !reference.startsWith("op://") || reference.length() > 8192
                || reference.chars().anyMatch(Character::isISOControl))
            throw new OnePasswordException("Expected a valid op://vault/item/field secret reference", false);
        String[] parts = reference.substring(5).split("/", -1);
        if (parts.length < 3 || java.util.Arrays.stream(parts).anyMatch(String::isBlank))
            throw new OnePasswordException("Expected a valid op://vault/item/field secret reference", false);
        // OTP values are short-lived and unsuitable for this configuration cache.
        if (reference.contains("?"))
            throw new OnePasswordException("Secret reference query parameters are not supported", false);
    }
}
