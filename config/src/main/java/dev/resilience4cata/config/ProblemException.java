package dev.resilience4cata.config;

/** One configuration problem, with its stable code. Collected into a {@link ConfigException}. */
final class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final String UNKNOWN_KEY = "R4C-CFG-001";
    static final String INVALID_VALUE = "R4C-CFG-002";
    static final String UNKNOWN_CLASS = "R4C-CFG-003";
    static final String INVALID_NAME = "R4C-CFG-004";
    static final String DUPLICATE_KEY = "R4C-CFG-005";
    static final String INVALID_CONFIG = "R4C-CFG-006";
    static final String BAD_EXTENDS = "R4C-CFG-007";
    static final String NOT_SUPPORTED = "R4C-CFG-008";
    static final String UNREADABLE = "R4C-CFG-009";
    static final String AMBIGUOUS = "R4C-CFG-010";
    /** No longer reported: a named config set both in code and externally is merged, external winning. */
    static final String REGISTERED_TWICE = "R4C-CFG-011";
    static final String INVALID_INSTANCE_ID = "R4C-CFG-012";
    static final String UNKNOWN_INSTANCE = "R4C-CFG-013";

    private final String code;

    ProblemException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }

    /** Returns the problem as one line: {@code [code] message}. */
    String render() {
        return "[" + code + "] " + getMessage();
    }
}
