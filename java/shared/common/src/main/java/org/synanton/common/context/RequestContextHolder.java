package org.synanton.common.context;

/**
 * Thread-local holder for the current {@link RequestContext}.
 * Must be cleared at the end of each request to prevent memory leaks in thread pools.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    /**
     * Returns the current RequestContext, or {@code null} if none has been set.
     */
    public static RequestContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
