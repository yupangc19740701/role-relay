package rolerelay;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar role-relay.jar run   [--config relay.yaml] (--task "..." | --task-file task.md) [--dry-run]
 * java -jar role-relay.jar roles [--config relay.yaml]
 * </pre>
 *
 * The API key is read from the ROLE_RELAY_API_KEY environment variable.
 */
public final class Main {

    static final String KEY_ENV = "ROLE_RELAY_API_KEY";

    public static void main(String[] args) {
        // Use the JVM's console streams as-is: since Java 18 they encode with the
        // console's code page (stdout.encoding), e.g. CP950 on Traditional Chinese
        // Windows. Forcing UTF-8 here garbles CJK text in such consoles.
        // Run logs are always written as UTF-8 files.
        System.exit(run(args, System.getenv(KEY_ENV), System.out, System.err));
    }

    static int run(String[] args, String apiKey, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage(out);
            return args.length == 0 ? 2 : 0;
        }
        String command = args[0];
        Path config = Path.of("relay.yaml");
        String task = null;
        Path taskFile = null;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(next(args, ++i, "--config"));
                case "--task" -> task = next(args, ++i, "--task");
                case "--task-file" -> taskFile = Path.of(next(args, ++i, "--task-file"));
                case "--dry-run" -> dryRun = true;
                default -> {
                    err.println("unknown option: " + args[i]);
                    return 2;
                }
            }
        }
        try {
            RelayConfig cfg = RelayConfig.load(config);
            RoleLoader roles = new RoleLoader(cfg.resolve(cfg.rolesDir));
            return switch (command) {
                case "roles" -> listRoles(roles, out);
                case "run" -> runRelay(cfg, roles, task, taskFile, dryRun, apiKey, out, err);
                default -> {
                    err.println("unknown command: " + command);
                    usage(err);
                    yield 2;
                }
            };
        } catch (RelayRunner.RelayException e) {
            err.println("relay stopped: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }
    }

    private static int listRoles(RoleLoader roles, PrintStream out) {
        List<String> names = roles.list();
        if (names.isEmpty()) {
            out.println("(no role files found)");
            return 0;
        }
        for (String name : names) {
            Role r = roles.load(name);
            out.println(r.name() + (r.description().isBlank() ? "" : "  - " + r.description()));
        }
        return 0;
    }

    private static int runRelay(RelayConfig cfg, RoleLoader roles, String task, Path taskFile, boolean dryRun,
                                String apiKey, PrintStream out, PrintStream err) {
        if ((task == null) == (taskFile == null)) {
            err.println("give exactly one of --task or --task-file");
            return 2;
        }
        if (taskFile != null) {
            try {
                task = Files.readString(taskFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println("could not read task file: " + taskFile);
                return 2;
            }
        }
        if (dryRun) {
            OpenAiCompatibleClient.validate(cfg.endpoint, cfg.allowRemote);
            RelayRunner planner = new RelayRunner(cfg, roles, null, null);
            List<Role> plan = planner.plan();
            out.println("Dry run: configuration and role files are valid. No model was called.");
            for (int i = 0; i < plan.size(); i++) {
                out.println((i + 1) + ". " + plan.get(i).name() + "  (model: "
                        + planner.modelFor(cfg.steps.get(i), plan.get(i)) + ")");
            }
            return 0;
        }
        LlmClient client = new OpenAiCompatibleClient(cfg.endpoint, apiKey, cfg.allowRemote,
                Duration.ofSeconds(cfg.timeoutSeconds));
        RunLog log = new RunLog(cfg.resolve(cfg.runsDir));
        List<RelayRunner.StepResult> results = new RelayRunner(cfg, roles, client, log).run(task);
        RelayRunner.StepResult last = results.get(results.size() - 1);
        out.println(last.result().content().strip());
        err.println();
        err.println("Run log: " + log.dir());
        for (RelayRunner.StepResult r : results) {
            err.println("  " + r.index() + ". " + r.role().name() + " -> answered by " + r.result().answeredBy());
        }
        return 0;
    }

    private static String next(String[] args, int i, String option) {
        if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static void usage(PrintStream p) {
        p.println("""
                role-relay: relay a task through prompt-defined roles

                Usage:
                  java -jar role-relay.jar run   [--config relay.yaml] (--task "..." | --task-file task.md) [--dry-run]
                  java -jar role-relay.jar roles [--config relay.yaml]

                Environment:
                  ROLE_RELAY_API_KEY   key for the endpoint (FreeLLMAPI: the unified key from its Keys page)
                """);
    }
}
