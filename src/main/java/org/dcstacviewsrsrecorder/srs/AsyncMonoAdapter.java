package org.dcstacviewsrsrecorder.srs;

import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Consumer;

/*
    Adapts a future invocation as a Mono
 */
public class AsyncMonoAdapter<T> {
    volatile Optional<T> published = null;
    volatile Consumer<T> publisher = t -> published = Optional.ofNullable(t);

    public void publish(T t) {
        publisher.accept(t);
    }

    public Mono<T> create() {
        return Mono.create(sink -> {
            publisher = sink::success;

            if(published != null) {
                publisher.accept(published.orElse(null));
            }
        });
    }
}
