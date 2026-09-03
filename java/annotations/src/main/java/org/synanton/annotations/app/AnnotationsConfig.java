package org.synanton.annotations.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.annotations.domain.AnnotationDefinitionService;
import org.synanton.annotations.domain.DependencyGraphService;
import org.synanton.annotations.domain.ProcessingRunService;
import org.synanton.annotations.domain.equalix.EqualixScheduler;
import org.synanton.annotations.domain.equalix.InvalidatingRecalculationExecutor;
import org.synanton.annotations.domain.equalix.RecalculationExecutor;
import org.synanton.annotations.domain.repository.AnnotationDefinitionRepository;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;
import org.synanton.annotations.domain.repository.ProcessingRunRepository;
import org.synanton.annotations.domain.resolutor.AnnotationInstanceStore;
import org.synanton.annotations.domain.resolutor.ResolutorService;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AnnotationsProperties.class)
public class AnnotationsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    AnnotationDefinitionService annotationDefinitionService(
            AnnotationDefinitionRepository definitions,
            AnnotationDefinitionVersionRepository versions,
            Clock clock
    ) {
        return new AnnotationDefinitionService(definitions, versions, clock);
    }

    @Bean
    DependencyGraphService dependencyGraphService(
            DependencyEdgeRepository edges,
            AnnotationDefinitionVersionRepository versions,
            Clock clock
    ) {
        return new DependencyGraphService(edges, versions, clock);
    }

    @Bean
    ProcessingRunService processingRunService(ProcessingRunRepository runs, Clock clock) {
        return new ProcessingRunService(runs, clock);
    }

    @Bean
    ResolutorService resolutorService(DependencyEdgeRepository edges, AnnotationInstanceStore instanceStore) {
        return new ResolutorService(edges, instanceStore);
    }

    @Bean
    RecalculationExecutor recalculationExecutor(AnnotationInstanceStore instanceStore) {
        return new InvalidatingRecalculationExecutor(instanceStore);
    }

    @Bean(destroyMethod = "close")
    EqualixScheduler equalixScheduler(
            AnnotationsProperties props,
            ProcessingRunService processingRuns,
            RecalculationExecutor executor
    ) {
        EqualixScheduler scheduler = new EqualixScheduler(
                props.equalix().poolSize(), props.equalix().pollTimeoutMs(), processingRuns, executor);
        scheduler.start();
        return scheduler;
    }
}
