package rolerelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutputFromTest {

    @TempDir
    Path dir;

    private static RelayRunner.StepResult step(int i, String role, String text) {
        return new RelayRunner.StepResult(i, new Role(role, "", null, "p"), "u",
                new ChatResult(text, "auto", null, null, null), 1);
    }

    private final List<RelayRunner.StepResult> results = List.of(
            step(1, "builder", "draft"),
            step(2, "translator", "翻譯"),
            step(3, "reviewer", "ACCEPT"));

    @Test
    void defaultsToLastStep() {
        assertEquals("ACCEPT", RelayRunner.shown(results, List.of()).result().content());
    }

    @Test
    void firstListedRolePresentWins() {
        assertEquals("翻譯", RelayRunner.shown(results, List.of("translator", "builder")).result().content());
        assertEquals("draft", RelayRunner.shown(results, List.of("ghost", "builder")).result().content());
    }

    @Test
    void fallsBackToLastStepWhenNoListedRoleRan() {
        assertEquals("ACCEPT", RelayRunner.shown(results, List.of("ghost")).result().content());
    }

    @Test
    void usesLastOccurrenceOfRepeatedRole() {
        var repeated = List.of(step(1, "builder", "v1"), step(2, "reviewer", "fix"), step(3, "builder", "v2"));
        assertEquals("v2", RelayRunner.shown(repeated, List.of("builder")).result().content());
    }

    @Test
    void configAcceptsSingleNameOrList() throws Exception {
        Path f = dir.resolve("a.yaml");
        Files.writeString(f, "output_from: builder\nsteps:\n  - role: builder\n");
        assertEquals(List.of("builder"), RelayConfig.load(f).outputFrom);
        Files.writeString(f, "output_from: [translator, builder]\nsteps:\n  - role: builder\n");
        assertEquals(List.of("translator", "builder"), RelayConfig.load(f).outputFrom);
        Files.writeString(f, "output_from: [\"Bad Name\"]\nsteps:\n  - role: builder\n");
        assertThrows(IllegalArgumentException.class, () -> RelayConfig.load(f));
    }

    @Test
    void cliPrintsChosenOutputAndMarksIt() throws Exception {
        // End to end through Main with a local fake server.
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply = body.contains("Prompt b") ? "OUTPUT-B" : body.contains("Prompt a") ? "OUTPUT-A" : "OUTPUT-C";
            byte[] b = ("{\"choices\":[{\"message\":{\"content\":\"" + reply + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Path roles = dir.resolve(".agents/roles");
            Files.createDirectories(roles);
            for (String r : List.of("a", "b", "c")) {
                Files.writeString(roles.resolve(r + ".md"), "---\nname: " + r + "\n---\nPrompt " + r + "\n");
            }
            Path yaml = dir.resolve("relay.yaml");
            Files.writeString(yaml, "endpoint: http://127.0.0.1:" + server.getAddress().getPort() + "/v1\n"
                    + "output_from: [b]\nsteps:\n  - role: a\n  - role: b\n  - role: c\n");
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            int code = Main.run(new String[]{"run", "--config", yaml.toString(), "--task", "t"}, "k",
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
            assertEquals("OUTPUT-B", out.toString(StandardCharsets.UTF_8).strip());
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("* 2. b"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void planFileCarriesOutputFrom() throws Exception {
        Path roles = dir.resolve(".agents/roles");
        Files.createDirectories(roles);
        Files.writeString(roles.resolve("builder.md"), "---\nname: builder\n---\nB\n");
        Path yaml = dir.resolve("relay.yaml");
        Files.writeString(yaml, "dispatcher_model: m\noutput_from: [translator, builder]\nsteps:\n  - role: builder\n");
        RelayConfig cfg = RelayConfig.load(yaml);
        LlmClient fake = (model, system, user) -> new ChatResult("{\"steps\":[{\"role\":\"builder\"}]}", model, null, null, null);
        Dispatcher d = new Dispatcher(cfg, new RoleLoader(cfg.resolve(cfg.rolesDir)), fake);
        Dispatcher.Plan plan = d.plan("t", dir.resolve("plans"));
        Path file = d.write(plan, "t", dir.resolve("plans"));
        assertEquals(List.of("translator", "builder"), RelayConfig.load(file).outputFrom);
    }
}
