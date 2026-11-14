package rolerelay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int status, String body, String routedVia, AtomicReference<String> seenAuth,
                         AtomicReference<String> seenBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            if (seenAuth != null) seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (seenBody != null) seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (routedVia != null) ex.getResponseHeaders().add("X-Routed-Via", routedVia);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void sendsSystemAndUserAndReadsReply() throws Exception {
        var auth = new AtomicReference<String>();
        var sent = new AtomicReference<String>();
        String url = serve(200, "{\"model\":\"auto\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}",
                "groq/llama-x", auth, sent);
        var client = new OpenAiCompatibleClient(url, "freellmapi-test", false, Duration.ofSeconds(5));

        ChatResult r = client.chat("auto", "SYS", "USER");

        assertEquals("hi", r.content());
        assertEquals("groq/llama-x", r.routedVia());
        assertEquals("groq/llama-x", r.answeredBy());
        assertEquals("Bearer freellmapi-test", auth.get());
        JsonNode req = new ObjectMapper().readTree(sent.get());
        assertEquals("auto", req.path("model").asText());
        assertEquals("system", req.path("messages").path(0).path("role").asText());
        assertEquals("SYS", req.path("messages").path(0).path("content").asText());
        assertEquals("USER", req.path("messages").path(1).path("content").asText());
        assertTrue(req.path("response_format").isMissingNode(), "must not force JSON output");
    }

    @Test
    void answeredByFallsBackToModelFieldThenUnknown() {
        assertEquals("m", new ChatResult("x", "auto", "m", null, null).answeredBy());
        assertEquals("unknown", new ChatResult("x", "auto", null, " ", null).answeredBy());
    }

    @Test
    void reportsHttpErrorWithoutRetrying() throws Exception {
        String url = serve(429, "{\"error\":{\"message\":\"quota exhausted\"}}", null, null, null);
        var client = new OpenAiCompatibleClient(url, "k", false, Duration.ofSeconds(5));
        var e = assertThrows(IllegalStateException.class, () -> client.chat("auto", "s", "u"));
        assertTrue(e.getMessage().contains("429"));
        assertTrue(e.getMessage().contains("quota exhausted"));
    }

    @Test
    void rejectsEmptyReply() throws Exception {
        String url = serve(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", null, null, null);
        var client = new OpenAiCompatibleClient(url, "k", false, Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, () -> client.chat("auto", "s", "u"));
    }

    @Test
    void endpointRules() {
        assertEquals("http://localhost:3001/v1/chat/completions",
                OpenAiCompatibleClient.validate("http://localhost:3001/v1", false).toString());
        assertEquals("http://127.0.0.1:3001/v1/chat/completions",
                OpenAiCompatibleClient.validate("http://127.0.0.1:3001/v1/", false).toString());
        // plain http to a remote host is never allowed
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleClient.validate("http://example.com/v1", true));
        // https remote needs allow_remote
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleClient.validate("https://example.com/v1", false));
        assertDoesNotThrow(() -> OpenAiCompatibleClient.validate("https://example.com/v1", true));
        // credentials, queries and other schemes are refused
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleClient.validate("http://u:p@localhost:3001/v1", false));
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleClient.validate("http://localhost:3001/v1?x=1", false));
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleClient.validate("file:///etc/passwd", false));
    }

    @Test
    void requiresApiKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleClient("http://localhost:3001/v1", " ", false, Duration.ofSeconds(5)));
    }
}
