package rolerelay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads role files such as {@code .agents/roles/planner.md}:
 *
 * <pre>
 * ---
 * name: planner
 * description: Breaks the task into small steps.
 * model: auto
 * ---
 * You are the planner. ...
 * </pre>
 *
 * Only these three fields are read; unknown fields are rejected so that typos are
 * visible. The loader only reads files. It never writes or generates roles.
 */
public final class RoleLoader {

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    static final int MAX_ROLE_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of("name", "description", "model");

    private final Path rolesDir;

    public RoleLoader(Path rolesDir) {
        this.rolesDir = rolesDir;
    }

    public Role load(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid role name: " + name);
        }
        Path file = rolesDir.resolve(name + ".md");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("role file not found: " + file);
        }
        try {
            if (Files.size(file) > MAX_ROLE_BYTES) {
                throw new IllegalArgumentException("role file is larger than 64 KiB: " + file);
            }
            Role role = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            if (!role.name().equals(name)) {
                throw new IllegalArgumentException(file + ": name '" + role.name() + "' does not match the file name");
            }
            return role;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** Names of all *.md files in the roles directory, sorted. */
    public List<String> list() {
        if (!Files.isDirectory(rolesDir)) return List.of();
        try (Stream<Path> files = Files.list(rolesDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .filter(n -> NAME.matcher(n).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + rolesDir, e);
        }
    }

    static Role parse(String text, String source) {
        String normalized = text.replace("\r\n", "\n");
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException(source + ": must start with a '---' front-matter line");
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            throw new IllegalArgumentException(source + ": front matter is not closed with '---'");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(source + ": front-matter line is not 'key: value': " + line);
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException(source + ": unknown front-matter field '" + key + "'");
            }
            if (fields.put(key, value) != null) {
                throw new IllegalArgumentException(source + ": duplicate field '" + key + "'");
            }
        }
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1).strip();

        String name = fields.get("name");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(source + ": 'name' is missing or invalid (lowercase letters, digits, - and _)");
        }
        String description = fields.getOrDefault("description", "");
        String model = fields.get("model");
        if (model != null && model.isBlank()) model = null;
        if (body.isEmpty()) {
            throw new IllegalArgumentException(source + ": the role prompt (text after the front matter) is empty");
        }
        return new Role(name, description, model, body);
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
