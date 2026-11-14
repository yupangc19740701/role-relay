package rolerelay;

/**
 * Sends one chat turn to a model. Implementations must not fall back to another
 * endpoint silently: a failure is reported as an exception.
 */
public interface LlmClient {

    /**
     * @param model        model name to request, for example "auto" or "gemini-2.5-flash"
     * @param systemPrompt the role's instructions
     * @param userContent  the task and any hand-off from earlier roles
     * @return the reply, plus what the server reported about which model answered
     */
    ChatResult chat(String model, String systemPrompt, String userContent);
}
