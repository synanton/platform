package org.synanton.mcp.filter;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates the verified identity (and MDC) to async tasks. Currently
 * unwired — the module defines no thread-pool executors yet. Register on
 * every ThreadPoolTaskExecutor when async execution arrives.
 */
public class TenantTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        McpIdentityContext.Identity id = McpIdentityContext.get();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (id != null) {
                    McpIdentityContext.set(id);
                }
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                task.run();
            } finally {
                McpIdentityContext.clear();
                MDC.clear();
            }
        };
    }
}
