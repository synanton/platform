package org.synanton.common.runtime;

import java.lang.reflect.InvocationTargetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs whether Netty's native epoll transport is available.
 *
 * <p>The check is reflective so services without Netty on the classpath can
 * call it safely. A missing native transport is a warning, not a startup
 * failure: NIO is a valid fallback. The log makes an accidental musl/Alpine
 * runtime visible in deployment evidence (ADR-024).
 */
public final class NativeTransportProbe {

    private static final Logger log = LoggerFactory.getLogger(NativeTransportProbe.class);

    private static final String[] EPOLL_CLASSES = {
            "io.grpc.netty.shaded.io.netty.channel.epoll.Epoll",
            "io.netty.channel.epoll.Epoll"
    };

    private NativeTransportProbe() {
    }

    public static void logStatus() {
        for (String className : EPOLL_CLASSES) {
            try {
                Class<?> epoll = Class.forName(className);
                Object available = epoll.getMethod("isAvailable").invoke(null);
                if (Boolean.TRUE.equals(available)) {
                    log.info("Netty native epoll transport available via {}", className);
                } else {
                    log.warn("Netty native epoll transport unavailable via {}; falling back to NIO. "
                            + "Verify the runtime image is glibc-based (ADR-024).", className);
                }
                return;
            } catch (ClassNotFoundException ignored) {
                // Try the next class name; the service may not use Netty.
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                log.warn("Could not determine Netty epoll availability via {}: {}",
                        className, e.toString());
                return;
            }
        }
        log.debug("No Netty epoll class on classpath; no native transport check needed");
    }
}
