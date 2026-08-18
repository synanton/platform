package org.synanton.ingestioncache.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

@Configuration
@EnableConfigurationProperties(IngestionCacheProperties.class)
public class IngestionCacheAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public CqlSession cqlSession(IngestionCacheProperties props) {
        CqlSessionBuilder builder = CqlSession.builder()
            .withLocalDatacenter(props.localDc());
        for (String cp : props.contactPoints()) {
            builder.addContactPoint(new InetSocketAddress(cp, props.port()));
        }
        CqlSession session = builder.build();
        SchemaInstaller.install(session);
        return session;
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestionCacheClient ingestionCacheClient(CqlSession session) {
        return new IngestionCacheClient(session);
    }
}
