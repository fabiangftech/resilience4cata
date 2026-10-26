package dev.resilience4cata.micronaut;

import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.HedgeConfig;
import dev.resilience4cata.HedgeSnapshot;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutConfig;
import dev.resilience4cata.TimeoutSnapshot;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link ResilienceSnapshot} records into maps, lists, strings, numbers and booleans, which every
 * Micronaut serializer writes as JSON without bean introspection or {@code @SerdeImport} of the records: record
 * components become keys in declaration order, enums their name, classes their name, durations their ISO-8601
 * text, and non-finite rates {@code null}.
 *
 * <p>The records are read through bean introspections that Micronaut's processor generates when this module is
 * compiled ({@link Introspected#classes()}), so no reflection is needed, in a native image either; a record without
 * one (a snapshot type added to core after this module was built) is read reflectively.
 */
@Introspected(classes = {ResilienceSnapshot.class, CircuitBreakerSnapshot.class, RetrySnapshot.class,
        RetrySnapshot.Budget.class, RateLimiterSnapshot.class, TimeoutSnapshot.class, BulkheadSnapshot.class,
        HedgeSnapshot.class, HedgeSnapshot.Budget.class, AdaptiveLimitSnapshot.class, RetryConfig.class, TimeoutConfig.class,
        HedgeConfig.class})
final class Snapshots {

    private Snapshots() {
    }

    /** The whole snapshot. */
    static Map<String, Object> of(ResilienceSnapshot snapshot) {
        return map(snapshot);
    }

    /** Only the instances whose id is {@code instance}, or {@code null} if there is none. */
    static @Nullable Map<String, Object> only(ResilienceSnapshot snapshot, String instance) {
        Map<String, Object> all = map(snapshot);
        Map<String, Object> one = new LinkedHashMap<>();
        boolean found = false;
        for (Map.Entry<String, Object> kind : all.entrySet()) {
            List<Object> matching = new ArrayList<>();
            if (kind.getValue() instanceof List<?> instances) {
                for (Object element : instances) {
                    if (element instanceof Map<?, ?> map && instance.equals(map.get("instance"))) {
                        matching.add(element);
                    }
                }
            }
            found |= !matching.isEmpty();
            one.put(kind.getKey(), matching);
        }
        return found ? one : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the introspection is of the record's own class
    private static Map<String, Object> map(Record record) {
        Optional<BeanIntrospection<Record>> introspection =
                BeanIntrospector.SHARED.findIntrospection((Class<Record>) record.getClass());
        Map<String, Object> map = new LinkedHashMap<>();
        if (introspection.isPresent()) {
            for (BeanProperty<Record, Object> property : introspection.get().getBeanProperties()) {
                map.put(property.getName(), value(property.get(record)));
            }
            return map;
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                map.put(component.getName(), value(component.getAccessor().invoke(record)));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot read " + component + " of " + record.getClass().getName(), e);
            }
        }
        return map;
    }

    private static @Nullable Object value(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Record record -> map(record);
            case Collection<?> collection -> collection.stream().map(Snapshots::value).toList();
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), value(v)));
                yield copy;
            }
            case Enum<?> constant -> constant.name();
            case Class<?> type -> type.getName();
            case Duration duration -> duration.toString();
            case Double d -> Double.isFinite(d) ? d : null;
            case Float f -> Float.isFinite(f) ? f : null;
            case Number n -> n;
            case Boolean b -> b;
            default -> value.toString();
        };
    }
}
