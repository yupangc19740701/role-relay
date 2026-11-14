package rolerelay;

/**
 * One model reply.
 *
 * @param content          the reply text
 * @param requestedModel   the model name role-relay asked for
 * @param responseModel    the "model" field of the response body, or null if absent
 * @param routedVia        the X-Routed-Via header (FreeLLMAPI: platform/model), or null if absent
 * @param fallbackAttempts the X-Fallback-Attempts header, or null if absent
 */
public record ChatResult(
        String content,
        String requestedModel,
        String responseModel,
        String routedVia,
        String fallbackAttempts) {

    /** The best available answer to "which model actually replied?", or "unknown". */
    public String answeredBy() {
        if (routedVia != null && !routedVia.isBlank()) return routedVia;
        if (responseModel != null && !responseModel.isBlank()) return responseModel;
        return "unknown";
    }
}
