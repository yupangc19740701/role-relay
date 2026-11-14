package rolerelay;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String key, String... args) {
        return Main.run(args, key, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void dryRunValidatesShippedConfigWithoutKeyOrNetwork() {
        assertEquals(0, run(null, "run", "--task", "hello", "--dry-run"));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1. planner"));
        assertTrue(text.contains("3. reviewer"));
    }

    @Test
    void rolesListsShippedRoles() {
        assertEquals(0, run(null, "roles"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("builder"));
    }

    @Test
    void runWithoutKeyFailsClearly() {
        assertEquals(2, run(null, "run", "--task", "hello"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("ROLE_RELAY_API_KEY"));
    }

    @Test
    void requiresExactlyOneTaskSource() {
        assertEquals(2, run("k", "run"));
        assertEquals(2, run("k", "run", "--task", "a", "--task-file", "b.md"));
    }

    @Test
    void unknownOptionAndCommand() {
        assertEquals(2, run("k", "run", "--bogus"));
        assertEquals(2, run("k", "fly"));
    }
}
