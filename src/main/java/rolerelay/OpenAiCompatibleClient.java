package rolerelay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls an OpenAI-compatible /chat/completions endpoint, such as FreeLLMAPI
 * (http://localhost:3001/v1).
 *
 * <p>Safety rules:
 * <ul>
 *   <li>Plain http is accepted only for loopback hosts.</li>
 *   <li>A non-loopback endpoint needs https and an explicit allow_remote: true.</li>
 *   <li>Redirects are not followed; responses over 4 MiB are rejected.</li>
 *   <li>There is no fallback to another endpoint, and the API key is never logged.</li>
 *   <li>Failures are thrown as {@link LlmException}, marked retryable or not; this class never retries itself.</li>
 * </ul>
 */
public final class OpenAiCompatibleClient implements LlmClient {

    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final URI chatUri;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiCompatibleClient(String endpoint, String apiKey, boolean allowRemote, Duration timeout) {
        this.chatUri = validate(endpoint, allowRemote);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key is required (set the ROLE_RELAY_API_KEY environment variable)");
        }
        this.apiKey = apiKey;
        this.timeout = timeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10));
        if (isLoopback(chatUri.getHost())) {
            builder.proxy(HttpClient.Builder.NO_PROXY);
        }
        this.http = builder.build();
    }

    static URI validate(String endpoint, boolean allowRemote) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        URI base;
        try {
            base = URI.create(endpoint.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("endpoint is not a valid URI");
        }
        String scheme = base.getScheme();
        String host = base.getHost();
        if (host == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("endpoint must be a plain http(s) URL without credentials, query or fragment");
        }
        boolean loopback = isLoopback(host);
        if ("http".equals(scheme)) {
            if (!loopback) {
                throw new IllegalArgumentException("plain http is allowed only for localhost; use https for remote endpoints");
            }
        } else if ("https".equals(scheme)) {
            if (!loopback && !allowRemote) {
                throw new IllegalArgumentException("remote endpoint refused; set allow_remote: true in relay.yaml if you intend this");
            }
        } else {
            throw new IllegalArgumentException("endpoint scheme must be http or https");
        }
        String path = base.getPath() == null ? "" : base.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return base.resolve(path + "/chat/completions");
    }

    static boolean isLoopback(String host) {
        return host != null && LOOPBACK.contains(host.toLowerCase());
    }

    URI chatUri() {
        return chatUri;
    }

    @Override
    public ChatResult chat(String model, String systemPrompt, String userContent) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("system prompt is required");
        if (userContent == null || userContent.isBlank()) throw new IllegalArgumentException("user content is required");

        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of(
                    "model", model,
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userContent))));
        } catch (IOException e) {
            throw new IllegalStateException("could not encode request", e);
        }

        HttpRequest request = HttpRequest.newBuilder(chatUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<InputStream> response;
        byte[] data;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                data = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        } catch (IOException e) {
            throw new LlmException("request to " + chatUri + " failed: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted");
        }

        if (data.length > MAX_RESPONSE_BYTES) {
            throw new LlmException("response exceeds " + MAX_RESPONSE_BYTES + " bytes", false);
        }
        int status = response.statusCode();
        if (status != 200) {
            throw new LlmException("HTTP " + status + " from " + chatUri + ": " + errorMessage(data),
                    LlmException.retryableStatus(status));
        }

        JsonNode root;
        try {
            root = json.readTree(data);
        } catch (IOException e) {
            throw new LlmException("response is not valid JSON", true);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new LlmException("response has no text in choices[0].message.content", true);
        }
        String responseModel = root.path("model").isTextual() ? root.path("model").asText() : null;
        String routedVia = response.headers().firstValue("X-Routed-Via").orElse(null);
        String fallbacks = response.headers().firstValue("X-Fallback-Attempts").orElse(null);
        return new ChatResult(content.asText(), model, responseModel, routedVia, fallbacks);
    }

    private String errorMessage(byte[] data) {
        try {
            JsonNode node = json.readTree(data).path("error").path("message");
            if (node.isTextual()) return truncate(node.asText());
        } catch (IOException ignored) {
            // fall through
        }
        return "(no error message)";
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
    }
}
