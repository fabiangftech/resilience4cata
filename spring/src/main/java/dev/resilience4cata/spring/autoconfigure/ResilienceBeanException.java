package dev.resilience4cata.spring.autoconfigure;

/**
 * A bean that resilience4cata cannot protect as declared. Startup fails with it (wrapped by Spring in a
 * {@code BeanCreationException}), and {@link ResilienceFailureAnalyzer} shows its description and action.
 */
final class ResilienceBeanException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** What to do, for the failure analysis. */
    private final String action;

    ResilienceBeanException(ErrorCode code, String description, String action) {
        super(code.message(description) + " " + action);
        this.action = action;
    }

    String action() {
        return action;
    }

    /** The message without the action. */
    String description() {
        String message = String.valueOf(getMessage());
        return message.substring(0, message.length() - action.length() - 1);
    }
}
