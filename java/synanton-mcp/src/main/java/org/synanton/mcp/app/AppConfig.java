package org.synanton.mcp.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.mcp.filter.McpAuthFilter;
import org.synanton.mcp.filter.SecurityProviderClient;

@Configuration
public class AppConfig {
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(
            McpProperties props, SecurityProviderClient provider) {
        FilterRegistrationBean<McpAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new McpAuthFilter(provider, props));
        bean.addUrlPatterns("/mcp");
        bean.setOrder(1);
        return bean;
    }
}
