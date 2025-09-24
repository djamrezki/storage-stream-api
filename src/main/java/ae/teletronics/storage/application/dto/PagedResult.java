package ae.teletronics.storage.application.dto;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class PagedResult<T> {
    private final Flux<T> items;
    private final Mono<Long> total;
    private final int page;
    private final int size;

    public PagedResult(Flux<T> items, Mono<Long> total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public Flux<T> items() { return items; }
    public Mono<Long> total() { return total; }
    public int page() { return page; }
    public int size() { return size; }
}
