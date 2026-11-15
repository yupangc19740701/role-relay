package rolerelay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Calls a model, retrying only {@link LlmException}s marked retryable, up to
 * {@code retries} extra attempts, with a short pause between attempts. Every
 * failed attempt is kept so the run log can show what happened.
 */
final class Retry {

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final Sleeper REAL_SLEEP = Thread::sleep;

    /** The successful result, how many attempts it took, and the errors of the failed attempts. */
    record Outcome(ChatResult result, int attempts, List<String> errors) {
    }

    /** Thrown when every attempt failed (or the failure was not retryable). */
    static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int attempts;
        final List<String> errors;

        Exhausted(RuntimeException last, int attempts, List<String> errors) {
            super(last.getMessage(), last);
            this.attempts = attempts;
            this.errors = List.copyOf(errors);
        }
    }

    private Retry() {
    }

    static Outcome call(Supplier<ChatResult> call, int retries, Sleeper sleeper) {
        List<String> errors = new ArrayList<>();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return new Outcome(call.get(), attempt, errors);
            } catch (RuntimeException e) {
                errors.add(e.getMessage());
                boolean canRetry = e instanceof LlmException le && le.retryable() && attempt <= retries;
                if (!canRetry) throw new Exhausted(e, attempt, errors);
                try {
                    sleeper.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exhausted(e, attempt, errors);
                }
            }
        }
    }
}
