package rolerelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {

    @TempDir
    Path dir;

    /** Replies with a fixed text and records what it was asked. */
    static final class Scripted implements LlmClient {
        final String reply;
        final List<String[]> calls = new ArrayList<>();

        Scripted(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResult chat(String model, String systemPrompt, String userContent) {
            calls.add(new String[]{model, systemPrompt, userContent});
            return new ChatResult(reply, model, model, "prov/" + model, null);
        }
    }

    private RelayConfig setup(String dispatcherModel) throws Exception {
        Path roles = dir.resolve(".agents/roles");
        Files.createDirectories(roles);
        for (String r : List.of("planner", "builder", "reviewer")) {
            Files.writeString(roles.resolve(r + ".md"), "---\nname: " + r + "\ndescription: does " + r + " work\n---\nSECRET PROMPT OF " + r + "\n");
        }
        String yaml = (dispatcherModel == null ? "" : "dispatcher_model: " + dispatcherModel + "\n")
                + "max_steps: 3\nsteps:\n  - role: planner\n";
        Path file = dir.resolve("relay.yaml");
        Files.writeString(file, yaml);
        return RelayConfig.load(file);
    }

    private Dispatcher dispatcher(RelayConfig cfg, LlmClient client) {
        return new Dispatcher(cfg, new RoleLoader(cfg.resolve(cfg.rolesDir)), client);
    }

    @Test
    void sendsOnlyNamesAndDescriptionsToFixedModel() throws Exception {
        RelayConfig cfg = setup("model-x");
        Scripted fake = new Scripted("{\"steps\":[{\"role\":\"builder\",\"reason\":\"simple\"}],\"notes\":\"\"}");
        Dispatcher.Plan plan = dispatcher(cfg, fake).plan("Write a haiku", dir.resolve("plans"));

        assertEquals("model-x", fake.calls.get(0)[0]);
        String user = fake.calls.get(0)[2];
        assertTrue(user.contains("- builder: does builder work"));
        assertTrue(user.contains("Write a haiku"));
        assertFalse(user.contains("SECRET PROMPT"), "role prompts must not be sent to the dispatcher");
        assertEquals(1, plan.steps().size());
        assertEquals("builder", plan.steps().get(0).role());
    }

    @Test
    void requiresDispatcherModel() throws Exception {
        RelayConfig cfg = setup(null);
        var e = assertThrows(IllegalArgumentException.class,
                () -> dispatcher(cfg, new Scripted("{}")).plan("t", dir.resolve("plans")));
        assertTrue(e.getMessage().contains("dispatcher_model"));
    }

    @Test
    void rejectsInventedRoleAndSavesReply() throws Exception {
        RelayConfig cfg = setup("m");
        Scripted fake = new Scripted("{\"steps\":[{\"role\":\"hacker\"}]}");
        var e = assertThrows(IllegalStateException.class,
                () -> dispatcher(cfg, fake).plan("t", dir.resolve("plans")));
        assertTrue(e.getMessage().contains("does not exist"));
        try (var s = Files.list(dir.resolve("plans"))) {
            Path saved = s.findFirst().orElseThrow();
            assertTrue(saved.getFileName().toString().endsWith("-REJECTED.log.md"));
            assertTrue(Files.readString(saved).contains("hacker"));
        }
    }

    @Test
    void rejectsTooManyStepsAndNonJson() throws Exception {
        RelayConfig cfg = setup("m");
        Dispatcher d = dispatcher(cfg, null);
        Set<String> names = Set.of("planner", "builder");
        assertThrows(IllegalStateException.class, () -> d.parse(
                "{\"steps\":[{\"role\":\"planner\"},{\"role\":\"planner\"},{\"role\":\"planner\"},{\"role\":\"planner\"}]}", names, 3));
        assertThrows(IllegalStateException.class, () -> d.parse("Sure! Here is the plan: planner then builder", names, 3));
        assertThrows(IllegalStateException.class, () -> d.parse("{\"steps\":[]}", names, 3));
        assertThrows(IllegalStateException.class, () -> d.parse("Here: {\"steps\":[{\"role\":\"planner\"}]}", names, 3));
    }

    @Test
    void acceptsFencedJson() throws Exception {
        RelayConfig cfg = setup("m");
        Dispatcher.Plan p = dispatcher(cfg, null).parse(
                "```json\n{\"steps\":[{\"role\":\"planner\"},{\"role\":\"builder\",\"reason\":\"do it\"}],\"notes\":\"n\"}\n```",
                Set.of("planner", "builder"), 3);
        assertEquals(2, p.steps().size());
        assertEquals("do it", p.steps().get(1).reason());
        assertEquals("n", p.notes());
    }

    @Test
    void writtenPlanIsRunnableAndCarriesTask() throws Exception {
        RelayConfig cfg = setup("m");
        Scripted fake = new Scripted("{\"steps\":[{\"role\":\"planner\",\"reason\":\"plan \\\"quoted\\\"\"},{\"role\":\"reviewer\"}],\"notes\":\"line1\\nline2\"}");
        Dispatcher d = dispatcher(cfg, fake);
        Path plansDir = dir.resolve("plans");
        Dispatcher.Plan plan = d.plan("中文任務: x", plansDir);
        Path file = d.write(plan, "中文任務: x", plansDir);

        RelayConfig loaded = RelayConfig.load(file);
        assertEquals("中文任務: x", loaded.task);
        assertEquals(List.of("planner", "reviewer"), loaded.steps.stream().map(s -> s.role).toList());
        assertEquals("plan \"quoted\"", loaded.steps.get(0).reason);
        // roles_dir in the plan must point back at the project's roles
        assertEquals("planner", new RoleLoader(loaded.resolve(loaded.rolesDir)).load("planner").name());
        assertTrue(Files.exists(plansDir.resolve(file.getFileName().toString().replace(".yaml", ".log.md"))));

        // and the run command can execute it without --task
        RelayRunnerTest.FakeClient runClient = new RelayRunnerTest.FakeClient();
        var results = new RelayRunner(loaded, new RoleLoader(loaded.resolve(loaded.rolesDir)), runClient,
                new RunLog(loaded.resolve(loaded.runsDir))).run(loaded.task);
        assertEquals(2, results.size());
        assertTrue(Files.isDirectory(dir.resolve("runs")), "runs must land in the project runs folder, not plans/");
    }

    @Test
    void customDispatcherPromptFileOverridesDefault() throws Exception {
        RelayConfig cfg = setup("m");
        Files.writeString(dir.resolve(".agents/dispatcher.md"), "CUSTOM DISPATCHER");
        Scripted fake = new Scripted("{\"steps\":[{\"role\":\"planner\"}]}");
        dispatcher(cfg, fake).plan("t", dir.resolve("plans"));
        assertEquals("CUSTOM DISPATCHER", fake.calls.get(0)[1]);
    }
}
