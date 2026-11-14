package rolerelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleLoaderTest {

    @TempDir
    Path dir;

    @Test
    void parsesFrontMatterAndBody() {
        Role r = RoleLoader.parse("---\nname: planner\ndescription: \"Plans\"\nmodel: auto\n---\nYou plan.\n", "t");
        assertEquals("planner", r.name());
        assertEquals("Plans", r.description());
        assertEquals("auto", r.model());
        assertEquals("You plan.", r.prompt());
    }

    @Test
    void acceptsWindowsLineEndingsAndBom() {
        Role r = RoleLoader.parse("\uFEFF---\r\nname: a\r\n---\r\nBody\r\n", "t");
        assertEquals("a", r.name());
        assertNull(r.model());
        assertEquals("Body", r.prompt());
    }

    @Test
    void rejectsUnknownField() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> RoleLoader.parse("---\nname: a\ntools: Bash\n---\nBody\n", "t"));
        assertTrue(e.getMessage().contains("unknown front-matter field"));
    }

    @Test
    void rejectsEmptyBodyAndMissingFrontMatter() {
        assertThrows(IllegalArgumentException.class, () -> RoleLoader.parse("---\nname: a\n---\n", "t"));
        assertThrows(IllegalArgumentException.class, () -> RoleLoader.parse("name: a\nBody", "t"));
        assertThrows(IllegalArgumentException.class, () -> RoleLoader.parse("---\nname: a\nBody", "t"));
    }

    @Test
    void loadChecksFileNameMatchesName() throws Exception {
        Files.writeString(dir.resolve("builder.md"), "---\nname: planner\n---\nBody\n");
        var e = assertThrows(IllegalArgumentException.class, () -> new RoleLoader(dir).load("builder"));
        assertTrue(e.getMessage().contains("does not match"));
    }

    @Test
    void loadRejectsPathTraversalNames() {
        RoleLoader loader = new RoleLoader(dir);
        assertThrows(IllegalArgumentException.class, () -> loader.load("../secret"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("Upper"));
    }

    @Test
    void listReturnsSortedMarkdownRoles() throws Exception {
        Files.writeString(dir.resolve("b.md"), "---\nname: b\n---\nB\n");
        Files.writeString(dir.resolve("a.md"), "---\nname: a\n---\nA\n");
        Files.writeString(dir.resolve("notes.txt"), "x");
        assertEquals(List.of("a", "b"), new RoleLoader(dir).list());
    }

    @Test
    void shippedExampleRolesAreValid() {
        RoleLoader loader = new RoleLoader(Path.of(".agents/roles"));
        for (String name : List.of("planner", "builder", "reviewer")) {
            assertEquals(name, loader.load(name).name());
        }
    }
}
