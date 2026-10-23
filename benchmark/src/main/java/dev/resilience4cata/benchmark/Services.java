package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Retry;

/**
 * Four unrelated services, as in an application with several clients. Each one has its own
 * interface and implementation class, so the JIT sees four different types.
 */
public final class Services {

    private Services() {
    }

    /** First service. */
    public interface Users {
        @Retry(config = "bench")
        String call();

        @Retry(config = "bench")
        String failOnce();
    }

    /** Second service. */
    public interface Orders {
        @Retry(config = "bench")
        String call();

        @Retry(config = "bench")
        String failOnce();
    }

    /** Third service. */
    public interface Payments {
        @Retry(config = "bench")
        String call();

        @Retry(config = "bench")
        String failOnce();
    }

    /** Fourth service. */
    public interface Shipping {
        @Retry(config = "bench")
        String call();

        @Retry(config = "bench")
        String failOnce();
    }

    // Separate classes with their own method bodies, so that no call site is shared by accident.

    static final class UsersImpl implements Users {
        private final String value = "users";
        private boolean failNext = true;

        @Override
        public String call() {
            return value;
        }

        @Override
        public String failOnce() {
            boolean fail = failNext;
            failNext = !fail;
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }
    }

    static final class OrdersImpl implements Orders {
        private final String value = "orders";
        private boolean failNext = true;

        @Override
        public String call() {
            return value;
        }

        @Override
        public String failOnce() {
            boolean fail = failNext;
            failNext = !fail;
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }
    }

    static final class PaymentsImpl implements Payments {
        private final String value = "payments";
        private boolean failNext = true;

        @Override
        public String call() {
            return value;
        }

        @Override
        public String failOnce() {
            boolean fail = failNext;
            failNext = !fail;
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }
    }

    static final class ShippingImpl implements Shipping {
        private final String value = "shipping";
        private boolean failNext = true;

        @Override
        public String call() {
            return value;
        }

        @Override
        public String failOnce() {
            boolean fail = failNext;
            failNext = !fail;
            if (fail) {
                throw FakeService.FAILURE;
            }
            return value;
        }
    }
}
