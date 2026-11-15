package rolerelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryTest {

    @TempDir
    Path dir;

    private final List<Long> slept = new ArrayList<>();
    private final Retry.Sleeper noSleep = slept::add;

    private static ChatResult ok() {
        return new ChatResult("ok", "auto", "m", "prov/m", null);
    }

    @Test
    void retriesRetryableFailureThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        Retry.Outcome o = Retry.call(() -> {
            if (n.incrementAndGet() == 1) throw new LlmException("HTTP 502 upstream", true);
            return ok();
        }, 1, noSleep);
        assertEquals(2, o.attempts());
        assertEquals(List.of("HTTP 502 upstream"), o.errors());
        assertEquals(List.of(2000L), slept);
    }

    @Test
    void doesNotRetryNonRetryable() {
        AtomicInteger n = new AtomicInteger();
        var e = assertThrows(Retry.Exhausted.class, () -> Retry.call(() -> {
            n.incrementAndGet();
            throw new LlmException("HTTP 401 bad key", false);
        }, 3, noSleep));
        assertEquals(1, n.get());
        assertEquals(1, e.attempts);
        assertTrue(slept.isEmpty());
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger n = new AtomicInteger();
        assertThrows(Retry.Exhausted.class, () -> Retry.call(() -> {
            n.incrementAndGet();
            throw new IllegalArgumentException("bug");
        }, 3, noSleep));
        assertEquals(1, n.get());
    }

    @Test
    void stopsAfterConfiguredRetries() {
        AtomicInteger n = new AtomicInteger();
        var e = assertThrows(Retry.Exhausted.class, () -> Retry.call(() -> {
            n.incrementAndGet();
            throw new LlmException("HTTP 429", true);
        }, 2, noSleep));
        assertEquals(3, n.get());
        assertEquals(3, e.attempts);
        assertEquals(3, e.errors.size());
        assertEquals(List.of(2000L, 4000L), slept);
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        AtomicInteger n = new AtomicInteger();
        assertThrows(Retry.Exhausted.class, () -> Retry.call(() -> {
            n.incrementAndGet();
            throw new LlmException("HTTP 503", true);
        }, 0, noSleep));
        assertEquals(1, n.get());
    }

    @Test
    void runnerRetriesStepAndLogsAttempts() throws Exception {
        Path roles = dir.resolve(".agents/roles");
        Files.createDirectories(roles);
        Files.writeString(roles.resolve("a.md"), "---\nname: a\n---\nPrompt a\n");
        Files.writeString(roles.resolve("b.md"), "---\nname: b\n---\nPrompt b\n");
        Path yaml = dir.resolve("relay.yaml");
        Files.writeString(yaml, "retries: 1\nsteps:\n  - role: a\n  - role: b\n");
        RelayConfig cfg = RelayConfig.load(yaml);

        AtomicInteger calls = new AtomicInteger();
        LlmClient flaky = (model, system, user) -> {
            int c = calls.incrementAndGet();
            if (c == 2) throw new LlmException("HTTP 502 from x: BlazeAPI returned a non-JSON body", true);
            return new ChatResult("reply-" + c, model, model, "prov/" + c, null);
        };
        RunLog log = new RunLog(cfg.resolve(cfg.runsDir));
        RelayRunner runner = new RelayRunner(cfg, new RoleLoader(cfg.resolve(cfg.rolesDir)), flaky, log);
        runner.setSleeper(noSleep);
        var results = runner.run("t");

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).attempts());
        assertEquals(2, results.get(1).attempts());
        String step2 = Files.readString(log.dir().resolve("02-b.md"));
        assertTrue(step2.contains("- Attempts: 2"));
        assertTrue(step2.contains("Attempt 1 failed: HTTP 502"));
    }

    @Test
    void runnerStopsWhenRetriesRunOut() throws Exception {
        Path roles = dir.resolve(".agents/roles");
        Files.createDirectories(roles);
        Files.writeString(roles.resolve("a.md"), "---\nname: a\n---\nPrompt a\n");
        Path yaml = dir.resolve("relay.yaml");
        Files.writeString(yaml, "retries: 1\nsteps:\n  - role: a\n");
        RelayConfig cfg = RelayConfig.load(yaml);
        LlmClient down = (model, system, user) -> {
            throw new LlmException("HTTP 503", true);
        };
        RunLog log = new RunLog(cfg.resolve(cfg.runsDir));
        RelayRunner runner = new RelayRunner(cfg, new RoleLoader(cfg.resolve(cfg.rolesDir)), down, log);
        runner.setSleeper(noSleep);
        var e = assertThrows(RelayRunner.RelayException.class, () -> runner.run("t"));
        assertTrue(e.getMessage().contains("after 2 attempts"));
        assertTrue(Files.readString(log.dir().resolve("01-a-FAILED.md")).contains("- Attempts: 2"));
    }

    @Test
    void configRejectsOutOfRangeRetries() throws Exception {
        Path yaml = dir.resolve("r.yaml");
        Files.writeString(yaml, "retries: 5\nsteps:\n  - role: a\n");
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.load(yaml));
    }
}
