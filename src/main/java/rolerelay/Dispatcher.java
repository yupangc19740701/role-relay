package rolerelay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The plan command: asks one fixed model (dispatcher_model) to choose which roles
 * should handle a task and in what order, validates the answer, and writes a plan
 * file that the run command can execute after a person has reviewed it.
 *
 * <p>The dispatcher only sees each role's name and one-line description, not the
 * role prompts, to keep this call small. It chooses roles; it does not choose models.
 * Nothing is executed by this class.
 */
public final class Dispatcher {

    static final String DEFAULT_PROMPT = """
            You assign work to a team of roles. You do not do the work yourself.

            You receive a task and a list of available roles (name and description).
            Choose the roles needed for this task and put them in order. Each role
            receives the task and the previous role's output.

            Rules:
            - Use only role names from the list. Never invent a role.
            - Use as few steps as the task needs. A simple task may need one role.
            - A role may appear more than once, for example to revise after a review.
            - Give each step a short reason.
            - Write every reason and the notes in the same language as the task.

            Reply with JSON only, no other text, in exactly this shape:
            {"steps": [{"role": "<name>", "reason": "<why>"}], "notes": "<anything the person should know>"}
            """;

    public record PlannedStep(String role, String reason) {
    }

    public record Plan(List<PlannedStep> steps, String notes, ChatResult result) {
    }

    private final RelayConfig config;
    private final RoleLoader roles;
    private final LlmClient client;
    private final ObjectMapper json = new ObjectMapper();
    private Retry.Sleeper sleeper = Retry.REAL_SLEEP;

    void setSleeper(Retry.Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public Dispatcher(RelayConfig config, RoleLoader roles, LlmClient client) {
        this.config = config;
        this.roles = roles;
        this.client = client;
    }

    /** The dispatcher's instructions: .agents/dispatcher.md if present, otherwise the built-in prompt. */
    String prompt() {
        Path custom = config.resolve(config.rolesDir).getParent() == null
                ? null : config.resolve(config.rolesDir).getParent().resolve("dispatcher.md");
        if (custom != null && Files.isRegularFile(custom)) {
            try {
                String text = Files.readString(custom, StandardCharsets.UTF_8).strip();
                if (!text.isEmpty()) return text;
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + custom, e);
            }
        }
        return DEFAULT_PROMPT.strip();
    }

    String userContent(String task, List<Role> available) {
        StringBuilder sb = new StringBuilder("## Task\n\n").append(task.strip()).append("\n\n## Available roles\n\n");
        for (Role r : available) {
            sb.append("- ").append(r.name());
            if (!r.description().isBlank()) sb.append(": ").append(r.description());
            sb.append("\n");
        }
        sb.append("\nAt most ").append(config.maxSteps).append(" steps.\n");
        return sb.toString();
    }

    /**
     * Calls the dispatcher and validates its answer. If the answer is rejected, the
     * raw reply is saved to plansDir/&lt;time&gt;-REJECTED.log.md before the error is raised.
     */
    public Plan plan(String task, Path plansDir) {
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task is empty");
        if (config.dispatcherModel == null || config.dispatcherModel.isBlank()) {
            throw new IllegalArgumentException("relay.yaml: set 'dispatcher_model' to a specific model for the plan command");
        }
        List<Role> available = new ArrayList<>();
        for (String name : roles.list()) available.add(roles.load(name));
        if (available.isEmpty()) throw new IllegalArgumentException("no role files found in " + config.rolesDir);

        String system = prompt();
        String user = userContent(task, available);
        ChatResult result;
        try {
            result = Retry.call(() -> client.chat(config.dispatcherModel, system, user), config.retries, sleeper).result();
        } catch (Retry.Exhausted e) {
            String tries = e.attempts > 1 ? " after " + e.attempts + " attempts" : "";
            throw new IllegalStateException("dispatcher call failed" + tries + ": " + e.getMessage());
        }
        Set<String> names = new LinkedHashSet<>();
        for (Role r : available) names.add(r.name());
        Plan parsed;
        try {
            parsed = parse(result.content(), names, config.maxSteps);
        } catch (IllegalStateException e) {
            Path saved = saveRejected(result, e.getMessage(), plansDir);
            throw new IllegalStateException(e.getMessage() + " (reply saved to " + saved + ")");
        }
        return new Plan(parsed.steps(), parsed.notes(), result);
    }

    /** Validates the dispatcher's JSON. Any problem is an error; nothing is guessed or repaired. */
    Plan parse(String reply, Set<String> roleNames, int maxSteps) {
        String body = extractJson(reply);
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("dispatcher reply is not valid JSON");
        }
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) throw new IllegalStateException("dispatcher reply has no 'steps' list");
        if (steps.size() > maxSteps) {
            throw new IllegalStateException("dispatcher proposed " + steps.size() + " steps; max_steps is " + maxSteps);
        }
        List<PlannedStep> out = new ArrayList<>();
        for (JsonNode s : steps) {
            String role = s.path("role").asText("");
            if (!roleNames.contains(role)) {
                throw new IllegalStateException("dispatcher chose a role that does not exist: '" + role + "'");
            }
            String reason = s.path("reason").isTextual() ? s.path("reason").asText().strip() : "";
            out.add(new PlannedStep(role, reason));
        }
        String notes = root.path("notes").isTextual() ? root.path("notes").asText().strip() : "";
        return new Plan(out, notes, null);
    }

    /** Accepts a bare JSON object or one wrapped in a ```json fence; nothing else. */
    static String extractJson(String reply) {
        if (reply == null) throw new IllegalStateException("dispatcher reply is empty");
        String t = reply.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int closing = t.lastIndexOf("```");
            if (firstNewline < 0 || closing <= firstNewline) throw new IllegalStateException("dispatcher reply has an unclosed code fence");
            t = t.substring(firstNewline + 1, closing).strip();
        }
        if (!t.startsWith("{") || !t.endsWith("}")) {
            throw new IllegalStateException("dispatcher reply must be a single JSON object");
        }
        return t;
    }

    /**
     * Writes plans/&lt;timestamp&gt;.yaml (runnable with run --config) and a matching
     * .log.md with the dispatcher call. Returns the plan file path.
     */
    public Path write(Plan plan, String task, Path plansDir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = plansDir.resolve(stamp + ".yaml");
        int n = 1;
        while (Files.exists(file)) file = plansDir.resolve(stamp + "-" + n++ + ".yaml");

        Path rolesAbs = config.resolve(config.rolesDir).toAbsolutePath();
        Path runsAbs = config.resolve(config.runsDir).toAbsolutePath();
        Path base = plansDir.toAbsolutePath();
        StringBuilder y = new StringBuilder();
        y.append("# Plan written by role-relay plan. Review and edit before running:\n");
        y.append("#   java -jar target/role-relay.jar run --config ").append(rel(Path.of("."), file)).append("\n");
        y.append("# Dispatcher model requested: ").append(config.dispatcherModel)
         .append("; answered by: ").append(plan.result().answeredBy()).append("\n");
        if (!plan.notes().isBlank()) {
            for (String line : plan.notes().split("\n")) y.append("# Note: ").append(line).append("\n");
        }
        y.append("\nendpoint: ").append(q(config.endpoint)).append("\n");
        y.append("default_model: ").append(q(config.defaultModel)).append("\n");
        y.append("allow_remote: ").append(config.allowRemote).append("\n");
        y.append("timeout_seconds: ").append(config.timeoutSeconds).append("\n");
        y.append("retries: ").append(config.retries).append("\n");
        if (!config.outputFrom.isEmpty()) {
            y.append("output_from: [").append(String.join(", ", config.outputFrom)).append("]\n");
        }
        y.append("roles_dir: ").append(q(rel(base, rolesAbs))).append("\n");
        y.append("runs_dir: ").append(q(rel(base, runsAbs))).append("\n");
        y.append("handoff: ").append(config.handoff).append("\n");
        y.append("task: ").append(q(task.strip())).append("\n");
        y.append("steps:\n");
        for (PlannedStep s : plan.steps()) {
            y.append("  - role: ").append(s.role()).append("\n");
            if (!s.reason().isBlank()) y.append("    reason: ").append(q(s.reason())).append("\n");
        }

        StringBuilder log = new StringBuilder("# Dispatcher call\n\n")
                .append("- Requested model: ").append(plan.result().requestedModel()).append("\n")
                .append("- Answered by: ").append(plan.result().answeredBy()).append("\n")
                .append("- X-Fallback-Attempts: ").append(plan.result().fallbackAttempts() == null
                        ? "(not reported)" : plan.result().fallbackAttempts()).append("\n\n")
                .append("## Reply\n\n").append(plan.result().content().strip()).append("\n");
        try {
            Files.createDirectories(plansDir);
            Files.writeString(file, y.toString(), StandardCharsets.UTF_8);
            String logName = file.getFileName().toString().replace(".yaml", ".log.md");
            Files.writeString(plansDir.resolve(logName), log.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write plan " + file, e);
        }
        return file;
    }

    private static Path saveRejected(ChatResult result, String reason, Path plansDir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = plansDir.resolve(stamp + "-REJECTED.log.md");
        String text = "# Dispatcher reply rejected\n\n- Reason: " + reason
                + "\n- Requested model: " + result.requestedModel()
                + "\n- Answered by: " + result.answeredBy() + "\n\n## Reply\n\n" + result.content().strip() + "\n";
        try {
            Files.createDirectories(plansDir);
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
        return file;
    }

    private static String rel(Path from, Path to) {
        try {
            return from.toAbsolutePath().normalize().relativize(to.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return to.toAbsolutePath().toString().replace('\\', '/');
        }
    }

    /** A JSON string literal is also a valid YAML double-quoted scalar. */
    private String q(String s) {
        try {
            return json.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
