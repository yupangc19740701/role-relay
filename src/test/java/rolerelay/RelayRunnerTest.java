package rolerelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelayRunnerTest {

    @TempDir
    Path dir;

    record Call(String model, String system, String user) {
    }

    /** Records each call and replies "reply-N". Can be told to fail on a given call. */
    static final class FakeClient implements LlmClient {
        final List<Call> calls = new ArrayList<>();
        int failOn = -1;

        @Override
        public ChatResult chat(String model, String systemPrompt, String userContent) {
            calls.add(new Call(model, systemPrompt, userContent));
            if (calls.size() == failOn) throw new IllegalStateException("HTTP 429 from fake");
            return new ChatResult("reply-" + calls.size(), model, "m" + calls.size(), "prov/m" + calls.size(), null);
        }
    }

    private RelayConfig setup(String handoff, String... roles) throws Exception {
        Path rolesDir = dir.resolve(".agents/roles");
        Files.createDirectories(rolesDir);
        for (String r : roles) {
            String model = r.equals("b") ? "model: fixed-b\n" : "";
            Files.writeString(rolesDir.resolve(r + ".md"), "---\nname: " + r + "\n" + model + "---\nPrompt of " + r + "\n");
        }
        StringBuilder yaml = new StringBuilder("default_model: auto\nhandoff: " + handoff + "\nsteps:\n");
        for (String r : roles) yaml.append("  - role: ").append(r).append("\n");
        Path file = dir.resolve("relay.yaml");
        Files.writeString(file, yaml.toString());
        return RelayConfig.load(file);
    }

    private RelayRunner runner(RelayConfig cfg, LlmClient client) {
        return new RelayRunner(cfg, new RoleLoader(cfg.resolve(cfg.rolesDir)), client, new RunLog(cfg.resolve(cfg.runsDir)));
    }

    @Test
    void relaysOutputToNextRoleUsingRolePromptAsSystem() throws Exception {
        RelayConfig cfg = setup("previous", "a", "b", "c");
        FakeClient fake = new FakeClient();
        var results = runner(cfg, fake).run("Write a haiku");

        assertEquals(3, results.size());
        assertEquals("Prompt of a", fake.calls.get(0).system());
        assertTrue(fake.calls.get(0).user().contains("Write a haiku"));
        assertFalse(fake.calls.get(0).user().contains("Output from role"));

        assertTrue(fake.calls.get(1).user().contains("## Output from role: a\n\nreply-1"));
        // previous mode: step 3 sees only step 2
        assertTrue(fake.calls.get(2).user().contains("reply-2"));
        assertFalse(fake.calls.get(2).user().contains("reply-1"));
    }

    @Test
    void allHandoffIncludesEveryEarlierOutput() throws Exception {
        RelayConfig cfg = setup("all", "a", "b", "c");
        FakeClient fake = new FakeClient();
        runner(cfg, fake).run("t");
        String third = fake.calls.get(2).user();
        assertTrue(third.contains("reply-1") && third.contains("reply-2"));
    }

    @Test
    void modelPrecedenceIsStepThenRoleThenDefault() throws Exception {
        RelayConfig cfg = setup("previous", "a", "b");
        cfg.steps.get(0).model = "step-model";
        FakeClient fake = new FakeClient();
        runner(cfg, fake).run("t");
        assertEquals("step-model", fake.calls.get(0).model());
        assertEquals("fixed-b", fake.calls.get(1).model());

        RelayConfig cfg2 = setup("previous", "a");
        FakeClient fake2 = new FakeClient();
        runner(cfg2, fake2).run("t");
        assertEquals("auto", fake2.calls.get(0).model());
    }

    @Test
    void stopsAtFirstFailureAndLogsIt() throws Exception {
        RelayConfig cfg = setup("previous", "a", "b", "c");
        FakeClient fake = new FakeClient();
        fake.failOn = 2;
        RelayRunner r = runner(cfg, fake);
        var e = assertThrows(RelayRunner.RelayException.class, () -> r.run("t"));
        assertTrue(e.getMessage().contains("step 2 (b)"));
        assertEquals(2, fake.calls.size(), "step 3 must not run after a failure");

        Path runDir;
        try (var s = Files.list(cfg.resolve("runs"))) {
            runDir = s.findFirst().orElseThrow();
        }
        assertTrue(Files.exists(runDir.resolve("02-b-FAILED.md")));
        assertTrue(Files.readString(runDir.resolve("summary.md")).contains("Stopped at step 2"));
    }

    @Test
    void missingRoleFailsBeforeAnyModelCall() throws Exception {
        RelayConfig cfg = setup("previous", "a");
        RelayConfig.Step extra = new RelayConfig.Step();
        extra.role = "ghost";
        cfg.steps.add(extra);
        FakeClient fake = new FakeClient();
        assertThrows(IllegalArgumentException.class, () -> runner(cfg, fake).run("t"));
        assertEquals(0, fake.calls.size());
    }

    @Test
    void runLogRecordsWhoAnswered() throws Exception {
        RelayConfig cfg = setup("previous", "a");
        runner(cfg, new FakeClient()).run("t");
        Path runDir;
        try (var s = Files.list(cfg.resolve("runs"))) {
            runDir = s.findFirst().orElseThrow();
        }
        String step = Files.readString(runDir.resolve("01-a.md"));
        assertTrue(step.contains("Answered by: prov/m1"));
        assertTrue(Files.readString(runDir.resolve("summary.md")).contains("Completed 1 step(s)."));
    }

    @Test
    void configRejectsUnknownKeysAndEmptySteps() throws Exception {
        Path f = dir.resolve("bad.yaml");
        Files.writeString(f, "stepz:\n  - role: a\n");
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.load(f));
        Files.writeString(f, "steps: []\n");
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.load(f));
    }
}
