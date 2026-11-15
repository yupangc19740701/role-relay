package rolerelay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes one folder per run:
 *
 * <pre>
 * runs/20261115-143000/
 *   00-task.md        the task and the planned roles
 *   01-planner.md     model requested, model reported, prompt sent, reply
 *   02-builder.md
 *   summary.md        one line per step, or the failure
 * </pre>
 *
 * The API key is never written.
 */
public final class RunLog {

    private final Path dir;
    private final StringBuilder summary = new StringBuilder();

    public RunLog(Path runsDir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path candidate = runsDir.resolve(stamp);
        int n = 1;
        while (Files.exists(candidate)) {
            candidate = runsDir.resolve(stamp + "-" + n++);
        }
        this.dir = candidate;
    }

    public Path dir() {
        return dir;
    }

    void start(String task, RelayConfig config, List<Role> plan) {
        StringBuilder sb = new StringBuilder("# Task\n\n").append(task.strip()).append("\n\n## Plan\n\n");
        for (int i = 0; i < plan.size(); i++) {
            sb.append(i + 1).append(". ").append(plan.get(i).name());
            if (!plan.get(i).description().isBlank()) sb.append(": ").append(plan.get(i).description());
            sb.append("\n");
        }
        sb.append("\nEndpoint: ").append(config.endpoint).append("\nHand-off: ").append(config.handoff).append("\n");
        write("00-task.md", sb.toString());
        summary.append("# Summary\n\n| Step | Role | Requested model | Answered by | Fallback attempts | Attempts | Seconds |\n|---|---|---|---|---|---|---|\n");
    }

    void step(RelayRunner.StepResult s) {
        ChatResult r = s.result();
        String text = "# Step " + s.index() + ": " + s.role().name() + "\n\n"
                + "- Requested model: " + r.requestedModel() + "\n"
                + "- Answered by: " + r.answeredBy() + "\n"
                + "- Response model field: " + nullable(r.responseModel()) + "\n"
                + "- X-Routed-Via: " + nullable(r.routedVia()) + "\n"
                + "- X-Fallback-Attempts: " + nullable(r.fallbackAttempts()) + "\n"
                + "- Attempts: " + s.attempts() + "\n"
                + retryLines(s.retryErrors())
                + "- Duration: " + s.millis() + " ms\n\n"
                + "## Role prompt (system)\n\n" + fence(s.role().prompt()) + "\n"
                + "## Input (user)\n\n" + fence(s.userContent()) + "\n"
                + "## Reply\n\n" + r.content().strip() + "\n";
        write(String.format("%02d-%s.md", s.index(), s.role().name()), text);
        summary.append("| ").append(s.index()).append(" | ").append(s.role().name())
               .append(" | ").append(r.requestedModel()).append(" | ").append(r.answeredBy())
               .append(" | ").append(nullable(r.fallbackAttempts()))
               .append(" | ").append(s.attempts())
               .append(" | ").append(String.format("%.1f", s.millis() / 1000.0)).append(" |\n");
    }

    void failed(int index, Role role, String model, String userContent, String error, int attempts, List<String> errors) {
        write(String.format("%02d-%s-FAILED.md", index, role.name()),
                "# Step " + index + ": " + role.name() + " (FAILED)\n\n- Requested model: " + model
                        + "\n- Attempts: " + attempts + "\n" + retryLines(errors)
                        + "- Final error: " + error + "\n\n## Input (user)\n\n" + fence(userContent));
        summary.append("\nStopped at step ").append(index).append(" (").append(role.name()).append("): ").append(error).append("\n");
        write("summary.md", summary.toString());
    }

    void finish(List<RelayRunner.StepResult> steps) {
        summary.append("\nCompleted ").append(steps.size()).append(" step(s).\n");
        write("summary.md", summary.toString());
    }

    private void write(String name, String content) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write run log " + dir.resolve(name), e);
        }
    }

    private static String retryLines(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            sb.append("- Attempt ").append(i + 1).append(" failed: ").append(errors.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String nullable(String s) {
        return s == null ? "(not reported)" : s;
    }

    private static String fence(String s) {
        String f = s.contains("```") ? "~~~~" : "```";
        return f + "\n" + s.strip() + "\n" + f + "\n";
    }
}
