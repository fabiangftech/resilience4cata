package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeded fuzz test of the processor's contract. Each seed builds random, valid Java: an annotated interface (with an
 * implementation) or an annotated class (subclass style), with random return types (sync, stages, {@code Mono},
 * {@code Flux}), generics, type-use annotations, varargs, overloads, checked exceptions, reserved parameter names,
 * policies at method and type level with random valid attributes, bundles, {@code @PolicyOrder}, classifiers and
 * fallbacks. Some seeds add one deliberately invalid method or type whose error code is known.
 *
 * <p>The contract: a valid source compiles with no diagnostic at all ({@code -Xlint:all -Werror}), and its generated
 * classes run (each method is called once succeeding and once failing); an invalid one fails with exactly the
 * expected {@code [R4C-…]} codes, each on an element of a source file. Never a processor crash, an error in a
 * generated file, or a compiler error that is not ours.
 *
 * <p>Replay one seed with {@code ./gradlew :processor:test --tests '*ProcessorFuzzTest' -PfuzzSeed=<seed>}, or run
 * more with {@code -PfuzzCount=<n>}.
 */
class ProcessorFuzzTest {

    private static final long FIRST_SEED = 1;
    private static final int DEFAULT_COUNT = 400;
    private static final int BATCH = 8;

    @TempDir
    Path workDir;

    private int compilations;

    @Test
    void randomSourcesKeepTheProcessorContract() throws Exception {
        String one = System.getProperty("test.seed");
        int count = Integer.getInteger("test.fuzz.count", DEFAULT_COUNT);
        List<Long> seeds = one != null && !one.isBlank() ? List.of(Long.parseLong(one.trim()))
                : LongStream.range(FIRST_SEED, FIRST_SEED + count).boxed().toList();
        List<Case> cases = seeds.stream().map(seed -> new Generator(seed).generate()).toList();
        List<String> failures = new ArrayList<>();
        // Valid and invalid cases compile in separate batches: an error anywhere stops javac before it writes classes.
        for (boolean valid : new boolean[] {true, false}) {
            List<Case> group = cases.stream().filter(c -> (c.expected == null) == valid).toList();
            for (int i = 0; i < group.size(); i += BATCH) {
                List<Case> batch = group.subList(i, Math.min(group.size(), i + BATCH));
                List<String> problems = check(batch);
                if (!problems.isEmpty() && batch.size() > 1) {
                    // narrow it down to the seeds that fail on their own
                    List<String> alone = new ArrayList<>();
                    for (Case c : batch) {
                        alone.addAll(check(List.of(c)));
                    }
                    problems = alone.isEmpty() ? problems : alone;
                }
                failures.addAll(problems);
                if (failures.size() >= 5) {
                    break;
                }
            }
        }
        long valid = cases.stream().filter(c -> c.expected == null).count();
        System.out.printf(Locale.ROOT, "ProcessorFuzzTest: %d seeds (%d valid, %d invalid) in %d compilations%n",
                cases.size(), valid, cases.size() - valid, compilations);
        assertThat(failures).as("fuzz failures").isEmpty();
    }

    /** Compiles {@code batch} together and returns a description of every broken contract. */
    private List<String> check(List<Case> batch) throws Exception {
        Path dir = Files.createDirectories(workDir.resolve("c" + compilations++)).toRealPath();
        Map<String, String> sources = new LinkedHashMap<>();
        batch.forEach(c -> sources.putAll(c.sources));
        Compilation result = Compilation.compile(dir, sources);
        List<String> problems = new ArrayList<>();
        Map<String, Case> byPackage = new HashMap<>();
        batch.forEach(c -> byPackage.put(c.pkg, c));
        Map<Case, Set<String>> codes = new HashMap<>();
        for (Diagnostic<? extends JavaFileObject> d : result.diagnostics()) {
            String message = d.getMessage(Locale.ROOT);
            String file = d.getSource() == null ? null : Path.of(d.getSource().toUri()).toRealPath().toUri().getPath();
            Case owner = file == null ? null : byPackage.get(packageOf(dir, file));
            boolean generated = file != null && file.startsWith(result.generated().toRealPath().toUri().getPath());
            boolean ours = d.getKind() == Diagnostic.Kind.ERROR && message.startsWith("[R4C-") && message.indexOf(']') > 0;
            if (ours && owner != null && !generated) {
                if (owner.expected == null) {
                    problems.add(report(owner, result, "unexpected processor error: " + where(d) + message));
                } else {
                    codes.computeIfAbsent(owner, k -> new TreeSet<>()).add(message.substring(1, message.indexOf(']')));
                }
                continue;
            }
            String kind = generated ? "a diagnostic in generated code" : message.contains("annotation processor threw")
                    ? "a processor crash" : ours ? "a processor error on no element" : "a diagnostic that is not ours";
            String text = kind + ": " + d.getKind() + " " + where(d) + message;
            if (owner != null) {
                problems.add(report(owner, result, text + (generated || ours ? "" : compilesWithoutProcessor(owner))));
            } else {
                problems.add(text + "\nseeds " + batch.stream().map(c -> String.valueOf(c.seed)).collect(Collectors.joining(", ")));
            }
        }
        for (Case c : batch) {
            if (c.expected != null) {
                Set<String> got = codes.getOrDefault(c, Set.of());
                if (!got.equals(c.expected)) {
                    problems.add(report(c, result, "expected the codes " + c.expected + ", got " + got + ": " + result.errors()));
                }
            }
        }
        if (problems.isEmpty() && !result.success() && batch.stream().allMatch(c -> c.expected == null)) {
            problems.add("the compilation failed without a diagnostic for seeds "
                    + batch.stream().map(c -> String.valueOf(c.seed)).toList());
        }
        if (problems.isEmpty() && result.success()) {
            for (Case c : batch) {
                String failure;
                try {
                    failure = run(c, result);
                } catch (Exception | LinkageError e) {
                    failure = "creating the generated classes threw " + trace(e);
                }
                if (failure != null) {
                    problems.add(report(c, result, failure));
                }
            }
        }
        return problems;
    }

    private static String where(Diagnostic<? extends JavaFileObject> d) {
        return d.getSource() == null ? "" : "(" + Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ") ";
    }

    private static String packageOf(Path dir, String file) {
        for (String root : List.of(dir.resolve("src").toUri().getPath(), dir.resolve("generated").toUri().getPath())) {
            if (file.startsWith(root)) {
                String relative = file.substring(root.length());
                int slash = relative.lastIndexOf('/');
                return slash < 0 ? "" : relative.substring(0, slash).replace('/', '.');
            }
        }
        return "";
    }

    /** Tells whether a source with a foreign diagnostic compiles without the processor: if not, the generator is wrong. */
    private String compilesWithoutProcessor(Case c) {
        try {
            Path dir = Files.createDirectories(workDir.resolve("plain" + compilations++));
            Compilation plain = Compilation.compileWithOptions(dir, c.sources, "-proc:none");
            return plain.diagnostics().isEmpty() ? " (the sources compile cleanly without the processor)"
                    : " (the generator is wrong: without the processor too: " + plain.diagnostics().stream()
                            .map(d -> d.getMessage(Locale.ROOT)).toList() + ")";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String report(Case c, Compilation result, String problem) {
        StringBuilder out = new StringBuilder("seed ").append(c.seed).append(": ").append(problem)
                .append("\n  replay: ./gradlew --no-daemon :processor:test --tests '*ProcessorFuzzTest' -PfuzzSeed=")
                .append(c.seed).append('\n');
        c.sources.forEach((name, source) -> out.append("---- ").append(name).append(".java\n").append(source));
        Path generated = result.generated().resolve(c.pkg.replace('.', '/'));
        if (Files.isDirectory(generated)) {
            try (Stream<Path> files = Files.list(generated)) {
                for (Path file : files.sorted().toList()) {
                    out.append("---- generated ").append(file.getFileName()).append('\n').append(Files.readString(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Running the generated classes

    /** Calls every generated method once succeeding and once failing; returns a description of what went wrong. */
    private static String run(Case c, Compilation result) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                ProcessorFuzzTest.class.getClassLoader())) {
            Class<?> ctl = loader.loadClass(c.pkg + ".Ctl");
            Object registry = dev.resilience4cata.ResilienceRegistry.builder().build();
            List<Target> targets = new ArrayList<>();
            for (Wrap wrap : c.wraps) {
                Class<?> type = loader.loadClass(wrap.binaryName);
                if (wrap.optional && loader.getResource(wrap.binaryName.replace('.', '/') + "$Resilient.class") == null) {
                    continue;
                }
                Object instance;
                if (wrap.implementation != null) {
                    Object impl = newInstance(loader.loadClass(wrap.implementation), List.of());
                    instance = wrapInterface(type, impl, registry);
                } else {
                    Class<?> generated = loader.loadClass(wrap.binaryName + "$Resilient");
                    instance = newInstance(generated, List.of(registry));
                }
                targets.add(new Target(wrap, type, instance));
            }
            for (boolean fail : new boolean[] {false, true}) {
                ctl.getField("fail").setBoolean(null, fail);
                for (Target target : targets) {
                    for (Method method : methods(target.type)) {
                        String problem = invoke(target, method, fail);
                        if (problem != null) {
                            return problem;
                        }
                    }
                }
            }
            return null;
        }
    }

    private record Target(Wrap wrap, Class<?> type, Object instance) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object wrapInterface(Class<?> type, Object impl, Object registry) {
        return dev.resilience4cata.Resilient.of((Class) type, impl, (dev.resilience4cata.ResilienceRegistry) registry);
    }

    /** Creates {@code type} through its first accessible constructor, after {@code leading}, with default arguments. */
    private static Object newInstance(Class<?> type, List<Object> leading) throws Exception {
        Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                .filter(k -> !Modifier.isPrivate(k.getModifiers()) && k.getParameterCount() >= leading.size())
                .min(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        constructor.setAccessible(true);
        Class<?>[] parameters = constructor.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = i < leading.size() ? leading.get(i) : defaultValue(parameters[i]);
        }
        return constructor.newInstance(arguments);
    }

    /** The fuzzed methods ({@code m<i>}) of {@code type} and its supertypes. */
    private static List<Method> methods(Class<?> type) {
        Map<String, Method> found = new LinkedHashMap<>();
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Method m : t.getDeclaredMethods()) {
                if (m.getName().startsWith("m") && !Modifier.isStatic(m.getModifiers()) && !Modifier.isPrivate(m.getModifiers())
                        && !m.isBridge() && !m.isSynthetic()) {
                    found.putIfAbsent(m.getName() + Arrays.toString(m.getParameterTypes()), m);
                }
            }
        }
        for (Class<?> i : type.getInterfaces()) {
            for (Method m : methods(i)) {
                found.putIfAbsent(m.getName() + Arrays.toString(m.getParameterTypes()), m);
            }
        }
        return List.copyOf(found.values());
    }

    private static String invoke(Target target, Method method, boolean fail) {
        Object[] arguments = Arrays.stream(method.getParameterTypes()).map(ProcessorFuzzTest::defaultValue).toArray();
        String call = target.type.getName() + "." + method.getName() + Arrays.toString(method.getParameterTypes())
                + (fail ? " (failing)" : " (succeeding)");
        Throwable failure;
        try {
            method.setAccessible(true);
            Object value = method.invoke(target.instance, arguments);
            await(value);
            return null;
        } catch (InvocationTargetException e) {
            failure = e.getCause();
        } catch (Throwable e) {
            failure = e;
        }
        while ((failure instanceof ExecutionException || failure instanceof CompletionException
                || failure instanceof UndeclaredCheckedException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof dev.resilience4cata.CallRejectedException) {
            return null; // a shared rate limiter or an opened breaker may reject
        }
        if (fail && failure instanceof IllegalStateException && "fz-boom".equals(failure.getMessage())) {
            return null;
        }
        return "calling " + call + " threw " + trace(failure);
    }

    /** The exception and its causes, each with the top of its stack. */
    private static String trace(Throwable failure) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            out.append(t == failure ? "" : "caused by ").append(t).append('\n');
            Arrays.stream(t.getStackTrace()).limit(8).forEach(frame -> out.append("    at ").append(frame).append('\n'));
        }
        return out.toString();
    }

    /** Wraps a checked exception thrown by a reactive block(). */
    private static final class UndeclaredCheckedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UndeclaredCheckedException(Throwable cause) {
            super(cause);
        }
    }

    private static void await(Object value) throws Exception {
        if (value instanceof CompletionStage<?> stage) {
            stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } else if (value instanceof reactor.core.publisher.Mono<?> mono) {
            block(() -> mono.block(Duration.ofSeconds(10)));
        } else if (value instanceof reactor.core.publisher.Flux<?> flux) {
            block(() -> flux.collectList().block(Duration.ofSeconds(10)));
        }
    }

    private static void block(Supplier<?> block) {
        try {
            block.get();
        } catch (RuntimeException e) {
            Throwable unwrapped = reactor.core.Exceptions.unwrap(e);
            throw unwrapped instanceof RuntimeException r ? r : new UndeclaredCheckedException(unwrapped);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return switch (type.getName()) {
            case "boolean" -> false;
            case "char" -> '\0';
            case "byte" -> (byte) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0f;
            default -> 0d;
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The generator

    /** One seed: its sources, the error codes it must produce (null when valid) and what to run. */
    private record Case(long seed, String pkg, Map<String, String> sources, Set<String> expected, List<Wrap> wraps) {
    }

    /** A type to run: an interface with its implementation class, or an annotated class (implementation null). */
    private record Wrap(String binaryName, String implementation, boolean optional) {
    }

    private enum Kind {
        RETRY, HEDGE, CIRCUIT_BREAKER, RATE_LIMITER, ADAPTIVE_LIMIT, TIMEOUT, BULKHEAD
    }

    private enum Shape {
        SYNC, STAGE, FUTURE, MONO, FLUX;

        boolean async() {
            return this != SYNC;
        }
    }

    /**
     * A return type: its source, shape, element type for async shapes (the bound of a wildcard), and whether it uses
     * the method's type variable {@code T}.
     */
    private record Ret(String source, Shape shape, String element, boolean usesT) {

        boolean isVoid() {
            return source.equals("void");
        }

        String defaultValue() {
            return switch (source) {
                case "int", "long", "short", "byte" -> "0";
                case "double", "float" -> "0.0";
                case "boolean" -> "false";
                case "char" -> "'x'";
                default -> "null";
            };
        }
    }

    private record Param(String type, String name, boolean usesT) {
    }

    /** The policies of one annotation site (method, type or bundle). */
    private static final class Site {
        final Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        boolean classifier;
    }

    private static final class Generator {

        private final long seed;
        private final SplittableRandom random;
        private final String pkg;
        private final Map<String, String> sources = new LinkedHashMap<>();
        private final List<String> holderMembers = new ArrayList<>();
        private final Map<String, String> sharedNames = new HashMap<>();
        private final List<Site> bundles = new ArrayList<>();
        private final List<String> bundleNames = new ArrayList<>();
        private int handlers;
        private int methodCount;
        private final boolean timeWindows;

        Generator(long seed) {
            this.seed = seed;
            this.random = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 7);
            this.pkg = "fz.s" + seed;
            this.timeWindows = random.nextBoolean();
        }

        private boolean chance(double p) {
            return random.nextDouble() < p;
        }

        private <T> T pick(List<T> values) {
            return values.get(random.nextInt(values.size()));
        }

        @SafeVarargs
        private <T> T pick(T... values) {
            return values[random.nextInt(values.length)];
        }

        private int between(int low, int high) {
            return random.nextInt(low, high + 1);
        }

        Case generate() {
            sources.put(pkg + ".Ctl", ctl());
            sources.put(pkg + ".FzException", "package " + pkg + ";\n\npublic class FzException extends Exception {\n"
                    + "    private static final long serialVersionUID = 1L;\n}\n");
            sources.put(pkg + ".Holder", "package " + pkg + ";\n\npublic class Holder<V> {\n    public class Inner {\n    }\n}\n");
            for (int i = 0, n = between(0, 2); i < n; i++) {
                bundle();
            }
            List<Wrap> wraps = new ArrayList<>();
            Invalid invalid = chance(0.3) ? pick(Invalid.values()) : null;
            boolean iface = chance(0.5);
            Main main = iface ? new Main(true, "Api") : new Main(false, "Svc");
            invalid = main.generate(invalid);
            wraps.add(main.wrap());
            if (main.contract != null) {
                wraps.add(new Wrap(pkg + ".Contract", main.binaryName(), true));
            }
            if (main.base != null) {
                wraps.add(new Wrap(pkg + ".Base", null, true));
            }
            Set<String> expected = null;
            if (invalid != null) {
                expected = new TreeSet<>(Set.of(invalid.code));
                if (invalid == Invalid.APT_001) {
                    sources.put(pkg + ".Mode", "package " + pkg + ";\n\nimport dev.resilience4cata.annotation.*;\n\npublic enum Mode {\n"
                            + "    ON;\n\n    @Timeout(\"1s\")\n    public String label() {\n        return name();\n    }\n}\n");
                }
                if (invalid == Invalid.CMP_001) {
                    holderMembers.add("    @Retry(maxAttempts = 2)\n    public @interface Unmarked {\n    }\n");
                }
            }
            sources.put(pkg + ".Handlers", holder());
            return new Case(seed, pkg, Map.copyOf(sources), expected, wraps);
        }

        private String ctl() {
            return "package " + pkg + ";\n\n"
                    + "public final class Ctl {\n"
                    + "    public static boolean fail;\n\n"
                    + "    private Ctl() {\n    }\n\n"
                    + "    public static void call() {\n        if (fail) {\n            throw boom();\n        }\n    }\n\n"
                    + "    public static IllegalStateException boom() {\n        return new IllegalStateException(\"fz-boom\");\n    }\n\n"
                    + "    public static <V> java.util.concurrent.CompletableFuture<V> stage() {\n"
                    + "        return fail ? java.util.concurrent.CompletableFuture.failedFuture(boom())\n"
                    + "                : java.util.concurrent.CompletableFuture.completedFuture(null);\n    }\n\n"
                    + "    public static <V> reactor.core.publisher.Mono<V> mono() {\n"
                    + "        return reactor.core.publisher.Mono.defer(() -> fail ? reactor.core.publisher.Mono.<V>error(boom())\n"
                    + "                : reactor.core.publisher.Mono.<V>empty());\n    }\n\n"
                    + "    public static <V> reactor.core.publisher.Flux<V> flux() {\n"
                    + "        return reactor.core.publisher.Flux.defer(() -> fail ? reactor.core.publisher.Flux.<V>error(boom())\n"
                    + "                : reactor.core.publisher.Flux.<V>empty());\n    }\n\n"
                    + "    public static <V> java.util.concurrent.CompletableFuture<V> done() {\n"
                    + "        return java.util.concurrent.CompletableFuture.completedFuture(null);\n    }\n\n"
                    + "    public static <V> reactor.core.publisher.Mono<V> noMono() {\n"
                    + "        return reactor.core.publisher.Mono.empty();\n    }\n\n"
                    + "    public static <V> reactor.core.publisher.Flux<V> noFlux() {\n"
                    + "        return reactor.core.publisher.Flux.empty();\n    }\n"
                    + "}\n";
        }

        private String holder() {
            StringBuilder out = new StringBuilder("package ").append(pkg).append(";\n\n")
                    .append("import dev.resilience4cata.Classifier.Outcome;\n")
                    .append("import dev.resilience4cata.annotation.*;\n")
                    .append("import org.jspecify.annotations.Nullable;\n\n")
                    .append("public final class Handlers {\n\n    private Handlers() {\n    }\n\n")
                    .append("    public static final class Cls implements dev.resilience4cata.Classifier<@Nullable Object> {\n")
                    .append("        @Override\n        public Outcome classify(@Nullable Object result, @Nullable Throwable failure) {\n")
                    .append("            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;\n        }\n    }\n");
            for (String member : holderMembers) {
                out.append('\n').append(member);
            }
            return out.append("}\n").toString();
        }

        /** A {@code @ResiliencePolicy} bundle in {@code Handlers}, without names (shared ones need equal configs). */
        private void bundle() {
            Site site = new Site();
            List<Kind> candidates = List.of(Kind.RETRY, Kind.CIRCUIT_BREAKER, Kind.RATE_LIMITER, Kind.ADAPTIVE_LIMIT,
                    Kind.TIMEOUT, Kind.BULKHEAD);
            StringBuilder annotations = new StringBuilder();
            for (int i = 0, n = between(1, 3); i < n; i++) {
                Kind kind = pick(candidates);
                if (site.kinds.add(kind)) {
                    annotations.append("    ").append(annotation(kind, false, null)).append('\n');
                }
            }
            String name = "Bundle" + bundles.size();
            bundles.add(site);
            bundleNames.add(name);
            holderMembers.add("    @ResiliencePolicy\n" + annotations + "    public @interface " + name + " {\n    }\n");
        }

        // ---- attribute values

        private String duration(String... values) {
            return "\"" + pick(values) + "\"";
        }

        private List<String> common() {
            List<String> values = new ArrayList<>();
            if (chance(0.2)) {
                values.add("locked = " + random.nextBoolean());
            }
            if (chance(0.15)) {
                values.add("enabled = " + random.nextBoolean());
            }
            return values;
        }

        /** The text of one policy annotation with random valid attributes. */
        private String annotation(Kind kind, boolean classifierAllowed, String name) {
            List<String> values = new ArrayList<>(common());
            switch (kind) {
                case RETRY -> {
                    values.add("maxAttempts = " + between(1, 4));
                    values.add("delay = " + duration("0", "1ms", "2ms", "PT0.003S", "500us"));
                    values.add("backoff = Backoff." + pick("FIXED", "EXPONENTIAL"));
                    values.add("multiplier = " + pick("1", "1.5", "2.0", "1e0"));
                    values.add("maxDelay = " + duration("1s", "2s")); // never below the default delay (200 ms)
                    values.add("jitter = Jitter." + pick("NONE", "FULL", "EQUAL"));
                    values.add("maxDuration = " + duration("2s", "PT5S", "1m"));
                    values.add("abortOn = " + pick("IllegalArgumentException.class",
                            "{IllegalArgumentException.class, java.util.NoSuchElementException.class}"));
                    values.add("budgetPercent = " + pick("0", "10", "12.5", "50"));
                    values.add("budgetMinRetriesPerSecond = " + between(0, 20));
                    values.add("budgetWindow = " + duration("1s", "10s", "PT1M"));
                    if (classifierAllowed && chance(0.4)) {
                        values.removeIf(v -> v.startsWith("retryOn"));
                        values.add("classifier = Handlers.Cls.class");
                        if (chance(0.5)) {
                            values.add("onExhaustedResult = ExhaustedResult." + pick("RETURN", "THROW"));
                        }
                    } else {
                        values.add("retryOn = " + pick("java.io.IOException.class", "IllegalStateException.class",
                                "{IllegalStateException.class, java.io.UncheckedIOException.class}"));
                    }
                }
                case HEDGE -> {
                    values.add("maxAttempts = " + between(2, 4));
                    values.add("delay = " + duration("0", "1ms", "5ms", "20ms"));
                    values.add("hedgeOn = " + pick("IllegalStateException.class", "java.io.IOException.class"));
                    values.add("abortOn = IllegalArgumentException.class");
                    values.add("budgetPercent = " + pick("0", "5", "20"));
                    values.add("budgetMinHedgesPerSecond = " + between(0, 10));
                    values.add("budgetWindow = " + duration("1s", "30s"));
                }
                case CIRCUIT_BREAKER -> {
                    // one window type per seed: the processor checks the window attributes merged from every site
                    String type = chance(0.5) ? "windowType = WindowType." + (timeWindows ? "TIME" : "COUNT") + ", " : "";
                    values.add(type + (timeWindows ? "windowDuration = " + duration("1s", "10s", "PT1M", "2m")
                            : "windowSize = " + between(1, 100)));
                    values.add("minimumCalls = " + between(1, 50));
                    values.add("failureRateThreshold = " + pick("50", "1", "100", "33.3"));
                    values.add("slowCallRateThreshold = " + pick("100", "80.5"));
                    values.add("slowCallDuration = " + duration("1s", "250ms"));
                    values.add("waitInOpen = " + duration("1s", "30s"));
                    values.add("halfOpenCalls = " + between(1, 5));
                    values.add("maxWaitInHalfOpen = " + duration("0", "10s"));
                    values.add("ignoreOn = IllegalArgumentException.class");
                    if (classifierAllowed && chance(0.3)) {
                        values.add("classifier = Handlers.Cls.class");
                    } else {
                        values.add("recordOn = " + pick("RuntimeException.class", "{IllegalStateException.class, java.io.IOException.class}"));
                    }
                }
                case RATE_LIMITER -> {
                    values.add("permits = " + between(1, 100));
                    values.add("period = " + duration("1s", "100ms", "PT1M"));
                    values.add("maxWait = " + duration("0", "1ms", "5ms"));
                }
                case ADAPTIVE_LIMIT -> {
                    // ranges that fit together and with the defaults, whichever site sets which bound
                    int min = between(1, 4);
                    int max = between(21, 100);
                    values.add("algorithm = LimitAlgorithm." + pick("GRADIENT2", "AIMD"));
                    values.add("minLimit = " + min);
                    values.add("maxLimit = " + max);
                    values.add("initialLimit = " + between(5, 20));
                    values.add("window = " + duration("10ms", "1s", "1h"));
                    values.add("dropOn = IllegalStateException.class");
                    values.add("ignoreOn = IllegalArgumentException.class");
                }
                case TIMEOUT -> {
                    String value = duration("1s", "2s", "PT3S", "5000ms");
                    if (chance(0.5) && values.isEmpty()) {
                        return "@Timeout(" + value + ")";
                    }
                    values.add("value = " + value);
                }
                case BULKHEAD -> {
                    values.add("maxConcurrent = " + between(1, 20));
                    values.add("maxWait = " + duration("0", "1ms", "10ms"));
                }
            }
            String simple = simpleName(kind);
            List<String> chosen = new ArrayList<>();
            for (String value : values) {
                // a retry always sets a short delay, so that failing calls stay fast
                if (value.startsWith("classifier") || value.startsWith("onExhaustedResult") || value.startsWith("value =")
                        || kind == Kind.RETRY && value.startsWith("delay =")
                        || chance(0.35)) {
                    chosen.add(value);
                }
            }
            if (name != null) {
                chosen.add(0, "name = \"" + name + "\"");
            }
            return "@" + simple + (chosen.isEmpty() ? "" : "(" + String.join(", ", chosen) + ")");
        }

        private static String simpleName(Kind kind) {
            return switch (kind) {
                case RETRY -> "Retry";
                case HEDGE -> "Hedge";
                case CIRCUIT_BREAKER -> "CircuitBreaker";
                case RATE_LIMITER -> "RateLimiter";
                case ADAPTIVE_LIMIT -> "AdaptiveLimit";
                case TIMEOUT -> "Timeout";
                case BULKHEAD -> "Bulkhead";
            };
        }

        private static String layer(Kind kind) {
            return "Layer." + kind.name();
        }

        // ---- types

        private String element(boolean generic) {
            List<String> options = new ArrayList<>(List.of("String", "Integer", "@Nullable String", "java.util.List<String>",
                    "Void", "java.util.Map<String, java.util.@Nullable List<Integer>>", "String[]"));
            if (generic) {
                options.add("T");
                options.add("T");
            }
            return pick(options);
        }

        private Ret returnType(boolean generic, boolean asyncAllowed) {
            int roll = random.nextInt(asyncAllowed ? 10 : 5);
            if (roll < 5) {
                List<String> options = new ArrayList<>(List.of("void", "int", "long", "boolean", "double", "char", "String",
                        "@Nullable String", "String[]", "int[]", "String @Nullable []", "java.util.List<String>",
                        "java.util.List<@Nullable String>", "java.util.@Nullable List<String>",
                        "java.util.Map<String, java.util.List<Integer>>", "Holder<String>.Inner", "java.util.List<int[]>"));
                if (generic) {
                    options.addAll(List.of("T", "@Nullable T", "java.util.List<? extends T>", "T[]", "java.util.Optional<T>"));
                }
                String source = pick(options);
                return new Ret(source, Shape.SYNC, null, mentionsT(source));
            }
            Shape shape = pick(Shape.STAGE, Shape.STAGE, Shape.FUTURE, Shape.MONO, Shape.FLUX);
            if (shape == Shape.STAGE && chance(0.3)) {
                String bound = pick("Number", "@Nullable String", "java.util.List<String>");
                return chance(0.2) ? new Ret("java.util.concurrent.CompletionStage<?>", shape, "Object", false)
                        : new Ret("java.util.concurrent.CompletionStage<? extends " + bound + ">", shape, bound, false);
            }
            if (shape == Shape.MONO && chance(0.2)) {
                return new Ret("reactor.core.publisher.Mono<? extends Number>", shape, "Number", false);
            }
            String element = element(generic);
            String container = switch (shape) {
                case STAGE -> "java.util.concurrent.CompletionStage";
                case FUTURE -> "java.util.concurrent.CompletableFuture";
                case MONO -> "reactor.core.publisher.Mono";
                default -> "reactor.core.publisher.Flux";
            };
            return new Ret(container + "<" + element + ">", shape, element, mentionsT(element));
        }

        private static boolean mentionsT(String type) {
            return type.matches(".*\\bT\\b.*");
        }

        private static final List<String> NAMES = List.of("id", "value", "java", "dev", "registry", "delegate", "r4c$x",
                "r4c$e", "reactor", "fz", "org", "e", "attempts", "context", "result", "start", "p0", "p1", "fallback0",
                "retry0", "String", "T2");

        private List<Param> parameters(boolean generic, Set<String> taken) {
            List<Param> params = new ArrayList<>();
            List<String> types = new ArrayList<>(List.of("int", "long", "String", "@Nullable String", "Object",
                    "java.util.List<? extends Number>", "java.util.Map<String, Integer>", "int[]", "String[]", "double",
                    "char", "boolean", "byte", "java.util.List<@Nullable String>", "Holder<Integer>.Inner",
                    "java.util.function.Function<? super String, ? extends Number>", "final String"));
            if (generic) {
                types.addAll(List.of("T", "@Nullable T", "java.util.List<T>", "java.util.Collection<? super T>"));
            }
            for (int i = 0, n = between(0, 3); i < n; i++) {
                String type = pick(types);
                params.add(new Param(type, name(taken), mentionsT(type)));
            }
            if (chance(0.15)) {
                params.add(new Param(pick("String...", "int...", "@Nullable String...", "Object...", "String @Nullable ..."),
                        name(taken), false));
            }
            return params;
        }

        private String name(Set<String> taken) {
            for (int attempt = 0; ; attempt++) {
                String name = attempt < 5 ? pick(NAMES) : "a" + attempt;
                if (taken.add(name)) {
                    return name;
                }
            }
        }

        private static String declare(List<Param> params) {
            return params.stream().map(p -> p.type + " " + p.name).collect(Collectors.joining(", "));
        }

        // ---- the main type

        private final class Main {
            final boolean iface;
            final String simple;
            final boolean nested;
            final boolean classGeneric;
            final Site typeSite;
            final List<String> typeAnnotations = new ArrayList<>();
            final List<String> members = new ArrayList<>();
            final List<String> implMembers = new ArrayList<>();
            boolean anyFlux;
            boolean anyPolicy;
            Main contract;
            String base;

            Main(boolean iface, String simple) {
                this(iface, simple, chance(0.2), chance(0.2));
            }

            Main(boolean iface, String simple, boolean nested, boolean classGeneric) {
                this(iface, simple, nested, classGeneric, new Site());
            }

            Main(boolean iface, String simple, boolean nested, boolean classGeneric, Site typeSite) {
                this.iface = iface;
                this.simple = simple;
                this.nested = nested;
                this.classGeneric = classGeneric;
                this.typeSite = typeSite;
            }

            String binaryName() {
                return pkg + "." + (nested ? "Outer$" : "") + simple;
            }

            Wrap wrap() {
                return new Wrap(binaryName(), iface ? pkg + "." + simple + "Impl" : null, false);
            }

            /** Generates the type; returns {@code invalid}, or null if it does not apply to this type. */
            Invalid generate(Invalid invalid) {
                // type-level policies first: they decide where a hedge may go
                if (chance(0.35)) {
                    for (int i = 0, n = between(1, 2); i < n; i++) {
                        Kind kind = pick(Kind.RETRY, Kind.CIRCUIT_BREAKER, Kind.RATE_LIMITER, Kind.ADAPTIVE_LIMIT,
                                Kind.TIMEOUT, Kind.BULKHEAD);
                        if (typeSite.kinds.add(kind)) {
                            typeAnnotations.add(annotation(kind, false, null));
                        }
                    }
                }
                if (!bundles.isEmpty() && chance(0.2)) {
                    int b = random.nextInt(bundles.size());
                    typeSite.kinds.addAll(bundles.get(b).kinds);
                    typeAnnotations.add("@Handlers." + bundleNames.get(b));
                }
                if (invalid != null && !invalid.applies(this)) {
                    invalid = null;
                }
                if (!iface) {
                    // the class's type-level policies apply to inherited methods too
                    if (chance(0.3)) {
                        base = "Base";
                        Main baseMain = new Main(false, "Base", false, false, typeSite);
                        for (int i = 0, n = between(1, 2); i < n; i++) {
                            baseMain.method(true);
                        }
                        anyFlux |= baseMain.anyFlux;
                        sources.put(pkg + ".Base", "package " + pkg + ";\n\n" + imports() + "public class Base {\n"
                                + String.join("", baseMain.members) + "}\n");
                    }
                    if (chance(0.3)) {
                        contract = new Main(true, "Contract", false, false, typeSite);
                        for (int i = 0, n = between(1, 2); i < n; i++) {
                            contract.method(true);
                        }
                        anyFlux |= contract.anyFlux;
                        sources.put(pkg + ".Contract", "package " + pkg + ";\n\n" + imports() + "public interface Contract {\n"
                                + String.join("", contract.members) + "}\n");
                    }
                }
                for (int i = 0, n = between(1, 4); i < n; i++) {
                    method(true);
                }
                if (invalid != null) {
                    invalid(invalid);
                }
                if (!anyFlux && chance(0.15)) {
                    List<Kind> order = new ArrayList<>(EnumSet.complementOf(EnumSet.of(Kind.HEDGE)));
                    java.util.Collections.shuffle(order, new java.util.Random(random.nextLong()));
                    typeAnnotations.add("@PolicyOrder({" + order.subList(0, between(2, order.size())).stream()
                            .map(Generator::layer).collect(Collectors.joining(", ")) + "})");
                }
                write(invalid);
                return invalid;
            }

            private String imports() {
                return "import dev.resilience4cata.annotation.*;\nimport org.jspecify.annotations.Nullable;\n\n";
            }

            /** One method, maybe overloaded, with its policies and fallback siblings. */
            void method(boolean overloadAllowed) {
                int index = methodCount++;
                boolean generic = chance(0.3);
                boolean throwsE = generic && chance(0.2);
                String typeParams = !generic ? "" : "<" + pick("T", "T extends Number", "T extends Comparable<? super T>",
                        "T extends Number & Comparable<T>", "T extends java.io.Serializable") + (throwsE ? ", E extends Exception" : "")
                        + "> ";
                boolean useClassT = classGeneric && !generic && chance(0.5);
                Ret ret = returnType(generic || useClassT, true);
                Set<String> taken = new LinkedHashSet<>();
                List<Param> params = parameters(generic || useClassT, taken);
                List<String> thrown = new ArrayList<>();
                for (String t : List.of("java.io.IOException", "FzException", "java.util.concurrent.TimeoutException", "InterruptedException")) {
                    if (chance(0.15)) {
                        thrown.add(t);
                    }
                }
                if (throwsE) {
                    thrown.add("E");
                }
                Site site = new Site();
                List<String> annotations = new ArrayList<>();
                if (!bundles.isEmpty() && chance(0.25)) {
                    int b = random.nextInt(bundles.size());
                    site.kinds.addAll(bundles.get(b).kinds);
                    annotations.add("@Handlers." + bundleNames.get(b));
                }
                boolean flux = ret.shape == Shape.FLUX;
                boolean retryAround = typeSite.kinds.contains(Kind.RETRY) || site.kinds.contains(Kind.RETRY);
                Set<Kind> direct = EnumSet.noneOf(Kind.class);
                for (Kind kind : Kind.values()) {
                    if (chance(0.3)) {
                        direct.add(kind);
                    }
                }
                if (direct.contains(Kind.HEDGE)) {
                    if (!ret.shape.async() || flux || retryAround) {
                        direct.remove(Kind.HEDGE);
                    } else {
                        direct.remove(Kind.RETRY);
                    }
                }
                Set<Kind> inherited = EnumSet.copyOf(site.kinds);
                inherited.addAll(typeSite.kinds);
                for (Kind kind : direct) {
                    // a shared name needs the same configuration everywhere: only where nothing else merges into it
                    boolean named = kind != Kind.TIMEOUT && !inherited.contains(kind) && chance(0.2);
                    String text;
                    if (named) {
                        String shared = "shared" + between(0, 2);
                        text = sharedNames.computeIfAbsent(kind + ":" + shared, k -> annotation(kind, false, shared));
                    } else {
                        text = annotation(kind, !flux && !inherited.contains(kind), null); // a merged retryOn/recordOn would clash
                        site.classifier |= text.contains("classifier");
                    }
                    annotations.add(text);
                }
                site.kinds.addAll(direct);
                Set<Kind> effective = EnumSet.copyOf(site.kinds);
                effective.addAll(typeSite.kinds);
                if (!flux && effective.size() >= 2 && chance(0.3)) {
                    List<Kind> order = new ArrayList<>(effective);
                    java.util.Collections.shuffle(order, new java.util.Random(random.nextLong()));
                    if (chance(0.3)) {
                        order.add(pick(Kind.values()));
                        order = new ArrayList<>(new LinkedHashSet<>(order));
                    }
                    annotations.add("@PolicyOrder({" + order.stream().map(Generator::layer).collect(Collectors.joining(", ")) + "})");
                }
                anyFlux |= flux;
                anyPolicy |= !annotations.isEmpty();
                String name = "m" + index;
                List<List<Param>> overloads = new ArrayList<>(List.of(params));
                if (overloadAllowed && chance(0.2) && (params.isEmpty() || !params.get(params.size() - 1).type.endsWith("..."))) {
                    List<Param> more = new ArrayList<>(params);
                    more.add(new Param("long", name(taken), false));
                    overloads.add(more);
                }
                boolean fallback = chance(0.35) && !ret.source.contains("Holder");
                String fallbackName = "fb" + index;
                boolean handler = fallback && !ret.usesT && (ret.shape == Shape.SYNC
                        || ret.shape == Shape.STAGE || ret.shape == Shape.FUTURE) && chance(0.25);
                String handlerName = handler ? handler(ret) : null;
                if (fallback) {
                    List<String> fallbackValues = new ArrayList<>();
                    fallbackValues.add(handler ? "value = " + handlerName + ".class" : "method = \"" + fallbackName + "\"");
                    if (chance(0.2)) {
                        fallbackValues.add("applyOn = " + pick("RuntimeException.class", "{IllegalStateException.class, java.io.IOException.class}",
                                "java.io.IOException.class"));
                    }
                    if (chance(0.15)) {
                        fallbackValues.add("skipOn = IllegalArgumentException.class");
                    }
                    String value = handler && fallbackValues.size() == 1 ? handlerName + ".class" : String.join(", ", fallbackValues);
                    annotations.add("@Fallback(" + value + ")");
                }
                String access = iface ? "" : pick("public ", "public ", "protected ", "");
                boolean interfaceDefault = iface && chance(0.1);
                String classT = useClassT ? "T" : null;
                for (List<Param> overload : overloads) {
                    String signature = typeParams + ret.source + " " + name + "(" + declare(overload) + ")"
                            + (thrown.isEmpty() ? "" : " throws " + String.join(", ", thrown));
                    StringBuilder out = new StringBuilder("\n");
                    for (String annotation : annotations) {
                        out.append("    ").append(annotation).append('\n');
                    }
                    if (iface && !interfaceDefault) {
                        out.append("    ").append(signature).append(";\n");
                        implMembers.add("\n    @Override\n    public " + signature + " {\n" + body(ret) + "    }\n");
                    } else {
                        out.append("    ").append(iface ? "default " : access).append(signature).append(" {\n").append(body(ret))
                                .append("    }\n");
                    }
                    members.add(out.toString());
                    if (fallback && !handler) {
                        siblings(fallbackName, typeParams, ret, overload, thrown, classT, taken);
                    }
                }
            }

            private String body(Ret ret) {
                return switch (ret.shape) {
                    case SYNC -> "        Ctl.call();\n" + (ret.isVoid() ? "" : "        return " + ret.defaultValue() + ";\n");
                    case STAGE, FUTURE -> "        return Ctl.stage();\n";
                    case MONO -> "        return Ctl.mono();\n";
                    case FLUX -> "        return Ctl.flux();\n";
                };
            }

            /** Sibling fallbacks of one method: one to three overloads, by exception type. */
            private void siblings(String name, String typeParams, Ret ret, List<Param> params, List<String> thrown, String classT,
                    Set<String> taken) {
                List<String> exceptions = new ArrayList<>(Arrays.asList(null, "Throwable", "Exception", "RuntimeException",
                        "java.io.IOException", "IllegalStateException"));
                java.util.Collections.shuffle(exceptions, new java.util.Random(random.nextLong()));
                List<String> chosen = exceptions.subList(0, between(1, 3));
                boolean isStatic = !iface && chance(0.2) || iface && chance(0.15);
                if (isStatic && (classT != null || params.stream().anyMatch(p -> p.usesT) && typeParams.isEmpty())) {
                    isStatic = false;
                }
                for (String exception : chosen) {
                    List<String> declared = new ArrayList<>();
                    for (Param p : params) {
                        // varargs must be last: before an exception parameter, the sibling takes the array
                        boolean array = exception != null && p.type.endsWith("...");
                        declared.add((array ? p.type.substring(0, p.type.length() - 3) + "[]" : p.type) + " " + p.name);
                    }
                    if (exception != null) {
                        String failure = pick("failure", "e", "r4c$e", "t");
                        declared.add(exception + " " + (taken.contains(failure) ? "x9" : failure));
                    }
                    List<String> siblingThrows = thrown.stream().filter(t -> !t.equals("E") && chance(0.5)).toList();
                    String result = siblingResult(ret);
                    String modifiers = iface ? (isStatic ? "static " : "default ") : (isStatic ? "static " : pick("public ", "protected ", ""));
                    String value = result.equals("void") ? "" : "        return " + siblingValue(ret, result) + ";\n";
                    members.add("\n    " + modifiers + typeParams + result + " " + name + "(" + String.join(", ", declared) + ")"
                            + (siblingThrows.isEmpty() ? "" : " throws " + String.join(", ", siblingThrows)) + " {\n" + value + "    }\n");
                }
            }

            private String siblingResult(Ret ret) {
                String element = ret.element;
                return switch (ret.shape) {
                    case SYNC -> ret.source;
                    case STAGE, FUTURE -> pick(element, "java.util.concurrent.CompletionStage<" + element + ">",
                            "java.util.concurrent.CompletionStage<? extends " + element + ">");
                    case MONO -> pick(element, "reactor.core.publisher.Mono<" + element + ">",
                            "reactor.core.publisher.Mono<? extends " + element + ">");
                    case FLUX -> pick(element, "reactor.core.publisher.Flux<" + element + ">",
                            "reactor.core.publisher.Flux<? extends " + element + ">");
                };
            }

            private String siblingValue(Ret ret, String result) {
                if (result.startsWith("java.util.concurrent.CompletionStage")) {
                    return "Ctl.done()";
                }
                if (result.startsWith("reactor.core.publisher.Mono")) {
                    return "Ctl.noMono()";
                }
                if (result.startsWith("reactor.core.publisher.Flux")) {
                    return "Ctl.noFlux()";
                }
                return ret.shape == Shape.SYNC ? ret.defaultValue() : "null";
            }

            private String handler(Ret ret) {
                String name = "H" + handlers++;
                String result = switch (ret.shape) {
                    case SYNC -> switch (ret.source) {
                        case "void" -> "Void";
                        case "int" -> "Integer";
                        case "long" -> "Long";
                        case "boolean" -> "Boolean";
                        case "double" -> "Double";
                        case "char" -> "Character";
                        default -> ret.source;
                    };
                    default -> chance(0.5) ? ret.element : "java.util.concurrent.CompletionStage<" + ret.element + ">";
                };
                String value = switch (result) {
                    case "Integer" -> "0";
                    case "Long" -> "0L";
                    case "Boolean" -> "false";
                    case "Double" -> "0.0";
                    case "Character" -> "'x'";
                    default -> result.startsWith("java.util.concurrent.CompletionStage")
                            ? "Ctl.done()" : "null";
                };
                holderMembers.add("    public static final class " + name + " implements dev.resilience4cata.FallbackHandler<" + result + "> {\n"
                        + "        @Override\n        public " + result + " apply(dev.resilience4cata.FallbackContext context) {\n"
                        + "            return " + value + ";\n        }\n    }\n");
                return "Handlers." + name;
            }

            /** One deliberately wrong member whose error code is known. */
            private void invalid(Invalid invalid) {
                String body = iface ? ";\n" : " {\n        " + (invalid.result.equals("void") ? "" : "return " + defaultFor(invalid.result) + ";")
                        + "\n    }\n";
                String modifiers = iface ? "" : "public ";
                switch (invalid) {
                    case APT_002 -> {
                        modifiers = "static ";
                        body = " {\n        return \"\";\n    }\n";
                    }
                    case SUB_007 -> modifiers = "public final ";
                    case SUB_001, CMP_001, APT_001 -> {
                        return;
                    }
                    case CMP_002 -> {
                        holderMembers.add("    @ResiliencePolicy\n    @Timeout(\"1s\")\n    public @interface Fast {\n    }\n");
                        holderMembers.add("    @ResiliencePolicy\n    @Timeout(\"2s\")\n    public @interface Slow {\n    }\n");
                    }
                    default -> {
                    }
                }
                members.add("\n    " + invalid.annotations + "\n    " + modifiers + invalid.result + " bad()" + body);
            }

            private String defaultFor(String result) {
                return switch (result) {
                    case "int" -> "0";
                    default -> result.contains("CompletableFuture") || result.contains("CompletionStage")
                            ? "Ctl.done()" : result.contains("Flux") ? "Ctl.noFlux()"
                            : result.equals("String") ? "\"\"" : "null";
                };
            }

            private void write(Invalid invalid) {
                StringBuilder out = new StringBuilder("package ").append(pkg).append(";\n\n").append(imports());
                String typeParameters = classGeneric ? "<T>" : "";
                StringBuilder declaration = new StringBuilder();
                for (String annotation : typeAnnotations) {
                    declaration.append(annotation).append('\n');
                }
                String modifiers = "public " + (nested ? "static " : "") + (invalid == Invalid.SUB_001 ? "final " : "");
                if (iface) {
                    declaration.append("public ").append("interface ").append(simple).append(typeParameters).append(" {\n");
                } else {
                    declaration.append(modifiers).append("class ").append(simple).append(typeParameters);
                    if (base != null) {
                        declaration.append(" extends ").append(base);
                    }
                    boolean serializable = chance(0.15);
                    List<String> interfaces = new ArrayList<>();
                    if (contract != null) {
                        interfaces.add("Contract");
                    }
                    if (serializable) {
                        interfaces.add("java.io.Serializable");
                    }
                    if (!interfaces.isEmpty()) {
                        declaration.append(" implements ").append(String.join(", ", interfaces));
                    }
                    declaration.append(" {\n");
                    if (serializable) {
                        declaration.append("\n    private static final long serialVersionUID = 1L;\n");
                    }
                    declaration.append(constructors());
                    if (contract != null) {
                        for (String implemented : contract.implMembers) {
                            declaration.append(implemented);
                        }
                    }
                }
                for (String member : members) {
                    declaration.append(member);
                }
                if (iface && !anyPolicy && typeSite.kinds.isEmpty()) {
                    declaration.append("\n    @Timeout(\"1s\")\n    void mPlain();\n");
                    implMembers.add("\n    @Override\n    public void mPlain() {\n    }\n");
                }
                if (!iface && !anyPolicy && typeSite.kinds.isEmpty()) {
                    declaration.append("\n    @Timeout(\"1s\")\n    public void mPlain() {\n    }\n");
                }
                declaration.append("}\n");
                if (nested) {
                    out.append("public final class Outer {\n\n    private Outer() {\n    }\n\n")
                            .append(declaration.toString().indent(4)).append("}\n");
                    sources.put(pkg + ".Outer", out.toString());
                } else {
                    out.append(declaration);
                    sources.put(pkg + "." + simple, out.toString());
                }
                if (iface) {
                    String implName = simple + "Impl";
                    String reference = (nested ? "Outer." : "") + simple + typeParameters;
                    sources.put(pkg + "." + implName, "package " + pkg + ";\n\n" + imports() + "public class " + implName
                            + typeParameters + " implements " + reference + " {\n" + String.join("", implMembers) + "}\n");
                }
            }

            private String constructors() {
                StringBuilder out = new StringBuilder();
                switch (random.nextInt(4)) {
                    case 0 -> {
                        // the implicit one
                    }
                    case 1 -> out.append("\n    public ").append(simple).append("() {\n    }\n");
                    case 2 -> out.append("\n    ").append(pick("public ", "protected ", "")).append(simple).append("(")
                            .append(pick("String registry, int java", "@Nullable String fz, long dev", "int... sizes",
                                    "String r4c$x, java.util.List<String> reactor"))
                            .append(")").append(chance(0.3) ? " throws java.io.IOException" : "").append(" {\n    }\n");
                    default -> out.append("\n    public ").append(simple).append("() {\n    }\n\n    protected ").append(simple)
                            .append("(String id, Object... rest) {\n    }\n\n    private ").append(simple).append("(long hidden) {\n    }\n");
                }
                return out.toString();
            }
        }
    }

    /** A deliberately invalid member or type, and the one code it must produce. */
    private enum Invalid {
        HDG_002("R4C-HDG-002", "@Hedge @Retry", "java.util.concurrent.CompletableFuture<String>", m -> true),
        HDG_001("R4C-HDG-001", "@Hedge", "String", m -> !m.typeSite.kinds.contains(Kind.RETRY)),
        ATR_010("R4C-ATR-010", "@CircuitBreaker(windowType = WindowType.TIME, windowSize = 5)", "String", m -> true),
        ATR_003("R4C-ATR-003", "@Retry(maxAttempts = 0)", "String", m -> true),
        ATR_001("R4C-ATR-001", "@Timeout(\"5 parsecs\")", "String", m -> true),
        ATR_002("R4C-ATR-002", "@RateLimiter(period = \"0\")", "void", m -> true),
        ATR_004("R4C-ATR-004", "@Retry(multiplier = 0.5)", "int", m -> true),
        ATR_005("R4C-ATR-005", "@CircuitBreaker(failureRateThreshold = 150)", "String", m -> true),
        ATR_007("R4C-ATR-007", "@Retry(budgetPercent = -5)", "String", m -> true),
        ATR_008("R4C-ATR-008", "@Hedge(maxAttempts = 11)", "java.util.concurrent.CompletableFuture<String>",
                m -> !m.typeSite.kinds.contains(Kind.RETRY)),
        ATR_009("R4C-ATR-009", "@AdaptiveLimit(minLimit = 10, maxLimit = 5)", "String", m -> true),
        FB_001("R4C-FB-001", "@Fallback", "String", m -> true),
        FB_002("R4C-FB-002", "@Fallback(method = \"nowhere\")", "String", m -> true),
        ORD_001("R4C-ORD-001", "@PolicyOrder({Layer.TIMEOUT, Layer.TIMEOUT}) @Timeout(\"1s\")", "String", m -> true),
        APT_002("R4C-APT-002", "@Timeout(\"1s\")", "String", m -> true),
        SUB_007("R4C-SUB-007", "@Timeout(\"1s\")", "String", m -> !m.iface),
        SUB_001("R4C-SUB-001", "", "String", m -> !m.iface),
        CLS_011("R4C-CLS-011", "@Retry(classifier = Handlers.Cls.class, retryOn = java.io.IOException.class)", "String", m -> true),
        CLS_012("R4C-CLS-012", "@Retry(onExhaustedResult = ExhaustedResult.THROW)", "String", m -> true),
        ASY_004("R4C-ASY-004", "@Hedge", "reactor.core.publisher.Flux<String>", m -> !m.typeSite.kinds.contains(Kind.RETRY)),
        ASY_001("R4C-ASY-001", "@Timeout(\"1s\")", "java.util.concurrent.CompletionStage<? super String>", m -> true),
        ASY_002("R4C-ASY-002", "@Timeout(\"1s\")", "java.util.concurrent.Flow.Publisher<String>", m -> true),
        CMP_001("R4C-CMP-001", "", "String", m -> true),
        CMP_002("R4C-CMP-002", "@Handlers.Fast @Handlers.Slow", "String", m -> true),
        APT_001("R4C-APT-001", "", "String", m -> true);

        final String code;
        final String annotations;
        final String result;
        private final Predicate<Generator.Main> applies;

        Invalid(String code, String annotations, String result, Predicate<Generator.Main> applies) {
            this.code = code;
            this.annotations = annotations;
            this.result = result;
            this.applies = applies;
        }

        boolean applies(Generator.Main main) {
            return applies.test(main);
        }
    }
}
