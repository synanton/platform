package org.synanton.controlplane.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.controlplane.api.AdminAuthFilter;
import org.synanton.controlplane.infra.HttpService;

@Configuration
public class AppConfig {

    @Bean
    public HttpService httpService(ControlPlaneProperties props) {
        return new HttpService(props);
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(ControlPlaneProperties props) {
        FilterRegistrationBean<AdminAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new AdminAuthFilter(props));
        bean.addUrlPatterns("/admin/*", "/auth/*");
        bean.setOrder(1);
        return bean;
    }
}
