package org.synanton.synvault.config;

import org.synanton.synvault.adapter.FilesystemAdapter;
import org.synanton.synvault.adapter.MinioObjectStoreAdapter;
import org.synanton.synvault.port.ObjectStorePort;
import org.synanton.synvault.spi.ContentAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SynvaultObjectStoreProperties.class)
public class SynvaultConfig {

    @Bean
    public ContentAdapter filesystemAdapter() {
        return new FilesystemAdapter();
    }

    @Bean
    public ObjectStorePort objectStorePort(SynvaultObjectStoreProperties props) {
        return new MinioObjectStoreAdapter(props);
    }
}
