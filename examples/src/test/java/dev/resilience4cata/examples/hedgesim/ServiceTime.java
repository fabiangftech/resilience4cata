package dev.resilience4cata.examples.hedgesim;

import java.util.SplittableRandom;

/**
 * How long the simulated dependency works on one request: usually a log-normal time around {@code median}, and with
 * probability {@code hiccupChance} a hiccup (a GC pause, a cold cache, a slow disk) that takes uniformly between
 * {@code hiccupMin} and {@code hiccupMax} instead. Each request draws on its own, so a hedge sent to the same service
 * usually does not hit the hiccup of the attempt it races: the case where hedging helps.
 */
final class ServiceTime {

    private final SplittableRandom random;
    private final long medianNanos;
    private final double sigma;
    private final double hiccupChance;
    private final long hiccupMinNanos;
    private final long hiccupMaxNanos;

    ServiceTime(long seed, long medianNanos, double sigma, double hiccupChance, long hiccupMinNanos, long hiccupMaxNanos) {
        this.random = new SplittableRandom(seed);
        this.medianNanos = medianNanos;
        this.sigma = sigma;
        this.hiccupChance = hiccupChance;
        this.hiccupMinNanos = hiccupMinNanos;
        this.hiccupMaxNanos = hiccupMaxNanos;
    }

    /** The model of the report, with its own random sequence. */
    static ServiceTime standard(long seed) {
        return new ServiceTime(seed, 10_000_000L, 0.25, 0.02, 100_000_000L, 300_000_000L);
    }

    long next() {
        if (random.nextDouble() < hiccupChance) {
            return random.nextLong(hiccupMinNanos, hiccupMaxNanos + 1);
        }
        return Math.max(1, Math.round(medianNanos * Math.exp(sigma * gaussian())));
    }

    /** The mean service time, estimated from {@code samples} draws of a fresh model with the same parameters. */
    double meanNanos(long seed, int samples) {
        ServiceTime copy = new ServiceTime(seed, medianNanos, sigma, hiccupChance, hiccupMinNanos, hiccupMaxNanos);
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            sum += copy.next();
        }
        return sum / samples;
    }

    /** A standard normal draw (Box-Muller; one of the pair is dropped, which keeps the sequence simple). */
    private double gaussian() {
        double u = 1.0 - random.nextDouble();
        double v = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
    }
}
