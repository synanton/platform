package org.synanton.mcp.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.mcp.filter.McpAuthFilter;

@Configuration
public class AppConfig {
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(McpProperties props) {
        FilterRegistrationBean<McpAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new McpAuthFilter(props));
        bean.addUrlPatterns("/mcp");
        bean.setOrder(1);
        return bean;
    }
}
