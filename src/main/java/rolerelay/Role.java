package rolerelay;

/**
 * A role defined by a Markdown file in the roles directory.
 *
 * @param name        file name without .md; must match the "name" field
 * @param description one line on what the role does
 * @param model       model to request, or null to use the relay's default_model
 * @param prompt      the Markdown body, sent as the system prompt
 */
public record Role(String name, String description, String model, String prompt) {
}
