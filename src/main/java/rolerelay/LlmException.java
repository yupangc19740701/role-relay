package rolerelay;

/**
 * A failed model call. {@link #retryable()} is true for failures that may succeed
 * on a second attempt: rate limits (429), upstream errors (500, 502, 503, 504),
 * network errors and timeouts, and a 200 reply with no usable content. It is false
 * for problems a retry cannot fix, such as a wrong key (401/403) or a bad request.
 */
public final class LlmException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    static boolean retryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }
}
