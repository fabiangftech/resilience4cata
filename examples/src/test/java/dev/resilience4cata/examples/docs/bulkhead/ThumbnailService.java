package dev.resilience4cata.examples.docs.bulkhead;

import dev.resilience4cata.annotation.Bulkhead;
import java.util.concurrent.CompletableFuture;

/** Bulkheads whose callers may wait for a slot. */
public interface ThumbnailService {

    // tag::bulkhead-wait[]
    @Bulkhead(name = "thumbnails", maxConcurrent = 4, maxWait = "250ms")
    byte[] thumbnail(String imageId);
    // end::bulkhead-wait[]

    // tag::bulkhead-async[]
    @Bulkhead(name = "thumbnails-async", maxConcurrent = 2, maxWait = "1s")
    CompletableFuture<byte[]> thumbnailAsync(String imageId);
    // end::bulkhead-async[]
}
