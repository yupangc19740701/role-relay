package rolerelay;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The contents of relay.yaml.
 *
 * <pre>
 * endpoint: http://localhost:3001/v1
 * default_model: auto
 * roles_dir: .agents/roles
 * handoff: previous        # previous | all
 * steps:
 *   - role: planner
 *   - role: builder
 *   - role: reviewer
 * </pre>
 *
 * Unknown keys are rejected so that typos are visible.
 */
public final class RelayConfig {

    public enum Handoff { previous, all }

    public static final class Step {
        public String role;
        /** Optional per-step model; overrides the role file and default_model. */
        public String model;
    }

    private static final Set<String> KEYS = Set.of("endpoint", "default_model", "allow_remote", "timeout_seconds",
            "roles_dir", "runs_dir", "handoff", "steps");
    private static final Set<String> STEP_KEYS = Set.of("role", "model");

    public String endpoint = "http://localhost:3001/v1";
    public String defaultModel = "auto";
    public boolean allowRemote = false;
    public int timeoutSeconds = 180;
    public String rolesDir = ".agents/roles";
    public String runsDir = "runs";
    public Handoff handoff = Handoff.previous;
    public List<Step> steps = new ArrayList<>();

    /** Directory of the relay.yaml file; relative paths resolve against it. */
    private Path baseDir = Path.of(".");

    public static RelayConfig load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read " + file);
        }
        RelayConfig config = parse(text, file.toString());
        Path parent = file.toAbsolutePath().getParent();
        config.baseDir = parent == null ? Path.of(".") : parent;
        return config;
    }

    static RelayConfig parse(String text, String source) {
        Object root;
        try {
            // SafeConstructor: plain maps, lists and scalars only; no arbitrary Java types.
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(source + ": not valid YAML: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) throw new IllegalArgumentException(source + ": must be a YAML mapping");

        RelayConfig c = new RelayConfig();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (!KEYS.contains(key)) throw new IllegalArgumentException(source + ": unknown key '" + key + "'");
            switch (key) {
                case "endpoint" -> c.endpoint = str(v, key, source);
                case "default_model" -> c.defaultModel = str(v, key, source);
                case "allow_remote" -> {
                    if (!(v instanceof Boolean b)) throw new IllegalArgumentException(source + ": 'allow_remote' must be true or false");
                    c.allowRemote = b;
                }
                case "timeout_seconds" -> {
                    if (!(v instanceof Integer i)) throw new IllegalArgumentException(source + ": 'timeout_seconds' must be a whole number");
                    c.timeoutSeconds = i;
                }
                case "roles_dir" -> c.rolesDir = str(v, key, source);
                case "runs_dir" -> c.runsDir = str(v, key, source);
                case "handoff" -> {
                    try {
                        c.handoff = Handoff.valueOf(str(v, key, source));
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(source + ": 'handoff' must be 'previous' or 'all'");
                    }
                }
                case "steps" -> c.steps = steps(v, source);
                default -> throw new IllegalStateException();
            }
        }
        c.validate();
        return c;
    }

    private static List<Step> steps(Object v, String source) {
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException(source + ": 'steps' must be a list");
        List<Step> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException(source + ": each step must look like '- role: name'");
            Step s = new Step();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!STEP_KEYS.contains(key)) throw new IllegalArgumentException(source + ": unknown step key '" + key + "'");
                if (key.equals("role")) s.role = str(e.getValue(), "role", source);
                else s.model = str(e.getValue(), "model", source);
            }
            out.add(s);
        }
        return out;
    }

    private static String str(Object v, String key, String source) {
        if (!(v instanceof String s)) throw new IllegalArgumentException(source + ": '" + key + "' must be text");
        return s.strip();
    }

    void validate() {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("relay.yaml: 'steps' must list at least one role");
        if (steps.size() > 20) throw new IllegalArgumentException("relay.yaml: at most 20 steps are supported");
        for (Step s : steps) {
            if (s == null || s.role == null || !RoleLoader.NAME.matcher(s.role).matches()) {
                throw new IllegalArgumentException("relay.yaml: every step needs a valid 'role'");
            }
        }
        if (defaultModel == null || defaultModel.isBlank()) throw new IllegalArgumentException("relay.yaml: 'default_model' is required");
        if (timeoutSeconds < 5 || timeoutSeconds > 1800) throw new IllegalArgumentException("relay.yaml: 'timeout_seconds' must be 5-1800");
    }

    public Path resolve(String path) {
        return baseDir.resolve(path).normalize();
    }
}
