package dev.resilience4cata;

/**
 * Information about the failed call, passed to a {@link FallbackHandler}. Implemented by the library only, not
 * meant to be implemented or mocked: methods may be added in minor versions.
 */
public interface FallbackContext {

    /** The failure that triggered the fallback, including rejections by a policy. */
    Throwable failure();

    /** Number of attempts made, including the first one. */
    int attempts();

    /** Number of arguments of the original call. */
    int argumentCount();

    /**
     * Returns one argument of the original call.
     *
     * @param index zero-based position of the argument
     * @param type the expected type of the argument
     * @param <A> the expected type of the argument
     * @return the argument, which may be {@code null} if the caller passed {@code null}
     * @throws IndexOutOfBoundsException if there is no argument at {@code index}
     * @throws ClassCastException if the argument is not of type {@code type}
     */
    <A> @org.jspecify.annotations.Nullable A argument(int index, Class<A> type);

    /**
     * Id of the policy instance: the derived {@code <type>#<method>(<parameter types>)}, with fully
     * qualified, erased parameter types, for example
     * {@code com.acme.PaymentClient#charge(com.acme.Order)} or, for a nested interface,
     * {@code com.acme.Clients.Payments#charge(java.lang.String,int)}; for a {@link Pipeline}, the pipeline's id.
     */
    String id();
}
