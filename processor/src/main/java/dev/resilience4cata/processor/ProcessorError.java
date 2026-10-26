package dev.resilience4cata.processor;

/**
 * Every error the processor reports, with its stable code. Each message starts with {@code [code]}, and
 * {@code docs/en/errors.md} has one section per code (what triggers it, a wrong example, the fix).
 *
 * <p>Codes are grouped by area: {@code APT} placement and general, {@code ATR} attribute values,
 * {@code CMP} composed annotations, {@code ASY} asynchronous methods, {@code HDG} hedges, {@code CLS}
 * classifiers, {@code FB} fallbacks, {@code SUB} annotated classes. A code is never renumbered or reused: a new error takes the
 * next free number of its area, and a retired one stays here, marked as no longer reported.
 */
enum ProcessorError {

    ENUM_OR_RECORD("R4C-APT-001", "annotations on an enum or a record"),
    STATIC_OR_PRIVATE_METHOD("R4C-APT-002", "annotations on a static or private method"),
    PRIVATE_INTERFACE("R4C-APT-003", "annotations on a private interface"),
    UNKNOWN_ATTRIBUTE("R4C-APT-004", "attribute unknown to the processor: mismatched versions"),
    WRITE_FAILED("R4C-APT-005", "a generated file could not be written"),
    INVALID_INJECT_OPTION("R4C-APT-006", "invalid -Ar4c.inject option, or Micronaut missing for it"),

    INVALID_DURATION("R4C-ATR-001", "invalid duration string"),
    DURATION_NOT_POSITIVE("R4C-ATR-002", "duration must be greater than zero"),
    COUNT_BELOW_ONE("R4C-ATR-003", "count must be at least 1"),
    INVALID_MULTIPLIER("R4C-ATR-004", "multiplier must be a finite number >= 1"),
    INVALID_PERCENTAGE("R4C-ATR-005", "rate threshold must be a percentage > 0 and <= 100"),
    INVALID_ENABLED("R4C-ATR-006", "enabled must be \"\", \"true\" or \"false\""),
    INVALID_BUDGET("R4C-ATR-007", "retry or hedge budget value out of range"),
    HEDGE_ATTEMPTS("R4C-ATR-008", "hedge maxAttempts must be between 2 and 10"),
    ADAPTIVE_LIMIT_RANGE("R4C-ATR-009", "adaptive limit bounds out of order, or window outside 10ms to 1h"),

    BUNDLE_NOT_MARKED("R4C-CMP-001", "composed annotation not marked with @ResiliencePolicy"),
    BUNDLE_CONFLICT("R4C-CMP-002", "two composed annotations set an attribute to different values"),

    UNSUPPORTED_ASYNC_TYPE("R4C-ASY-001", "unsupported asynchronous return type"),

    HEDGE_NOT_ASYNC("R4C-HDG-001", "@Hedge on a synchronous method"),
    HEDGE_WITH_RETRY("R4C-HDG-002", "@Hedge and @Retry on the same method"),

    CLASSIFIER_MISSING_CORE("R4C-CLS-001", "dev.resilience4cata.Classifier is not on the classpath"),
    CLASSIFIER_NOT_CLASS("R4C-CLS-002", "the classifier is not a class"),
    CLASSIFIER_ABSTRACT("R4C-CLS-003", "the classifier class is abstract"),
    CLASSIFIER_GENERIC("R4C-CLS-004", "the classifier class is generic"),
    CLASSIFIER_INACCESSIBLE("R4C-CLS-005", "the classifier class is not accessible to the generated class"),
    CLASSIFIER_INNER("R4C-CLS-006", "the classifier is a non-static nested class"),
    CLASSIFIER_NO_CONSTRUCTOR("R4C-CLS-007", "the classifier has no accessible no-argument constructor"),
    CLASSIFIER_NOT_CLASSIFIER("R4C-CLS-008", "the classifier does not implement Classifier<T>"),
    CLASSIFIER_RAW("R4C-CLS-009", "the classifier implements the raw type Classifier"),
    CLASSIFIER_WRONG_TYPE("R4C-CLS-010", "the classifier's type does not match the method's result"),
    CLASSIFIER_WITH_FILTER("R4C-CLS-011", "classifier combined with retryOn or recordOn"),
    EXHAUSTED_WITHOUT_CLASSIFIER("R4C-CLS-012", "onExhaustedResult without a classifier"),

    FALLBACK_TARGET("R4C-FB-001", "@Fallback needs exactly one of a handler class or a method name"),
    FALLBACK_METHOD_NOT_FOUND("R4C-FB-002", "no method with the fallback method's name"),
    FALLBACK_METHOD_UNUSABLE("R4C-FB-003", "no method with the fallback method's name has a usable signature"),
    HANDLER_MISSING_CORE("R4C-FB-004", "dev.resilience4cata.FallbackHandler is not on the classpath"),
    HANDLER_NOT_CLASS("R4C-FB-005", "the fallback handler is not a class"),
    HANDLER_ABSTRACT("R4C-FB-006", "the fallback handler class is abstract"),
    HANDLER_GENERIC("R4C-FB-007", "the fallback handler class is generic"),
    HANDLER_INACCESSIBLE("R4C-FB-008", "the fallback handler class is not accessible to the generated class"),
    HANDLER_INNER("R4C-FB-009", "the fallback handler is a non-static nested class"),
    HANDLER_NO_CONSTRUCTOR("R4C-FB-010", "the fallback handler has no accessible no-argument constructor"),
    HANDLER_NOT_HANDLER("R4C-FB-011", "the fallback handler does not implement FallbackHandler<T>"),
    HANDLER_RAW("R4C-FB-012", "the fallback handler implements the raw type FallbackHandler"),
    HANDLER_WRONG_TYPE("R4C-FB-013", "the fallback handler's type does not match the method's result"),
    HANDLER_ON_GENERIC_METHOD("R4C-FB-014", "a handler class on a method whose result depends on its type parameters"),

    SUBCLASS_FINAL("R4C-SUB-001", "annotated class is final"),
    SUBCLASS_SEALED("R4C-SUB-002", "annotated class is sealed"),
    SUBCLASS_ABSTRACT("R4C-SUB-003", "annotated class is abstract"),
    SUBCLASS_PRIVATE("R4C-SUB-004", "annotated class is private, or nested in a private class"),
    SUBCLASS_INNER("R4C-SUB-005", "annotated class is an inner (non-static nested) class"),
    SUBCLASS_PRIVATE_CONSTRUCTORS("R4C-SUB-006", "annotated class has only private constructors"),
    SUBCLASS_FINAL_METHOD("R4C-SUB-007", "annotated method is final"),
    SUBCLASS_HIDDEN_METHOD("R4C-SUB-008", "annotated method is package-private in another package");

    private final String code;
    private final String summary;

    ProcessorError(String code, String summary) {
        this.code = code;
        this.summary = summary;
    }

    /** The stable code, for example {@code R4C-FB-002}. */
    String code() {
        return code;
    }

    /** One line saying what the error is about, for listings. */
    String summary() {
        return summary;
    }

    /** The diagnostic text: {@code [code] } followed by {@code detail}, which says where, what and how to fix. */
    String message(String detail) {
        return "[" + code + "] " + detail;
    }
}
