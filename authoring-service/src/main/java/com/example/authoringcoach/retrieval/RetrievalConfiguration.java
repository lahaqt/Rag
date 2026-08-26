package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.service.StorageRetrievalClient;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RetrievalConfiguration {
    @Bean
    CourseRelationProvider courseRelationProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcCourseRelationProvider(jdbcTemplate);
    }

    @Bean
    RetrievalScopePlanner retrievalScopePlanner(CourseRelationProvider relationProvider) {
        return new RetrievalScopePlanner(relationProvider);
    }

    @Bean
    CourseSearchGateway courseSearchGateway(StorageRetrievalClient retrievalClient) {
        return new StorageRetrievalCourseSearchGateway(retrievalClient);
    }

    @Bean("courseRetrievalExecutor")
    TaskExecutor courseRetrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("course-retrieval-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    TieredCourseRetrievalService tieredCourseRetrievalService(
            CourseSearchGateway searchGateway,
            @org.springframework.beans.factory.annotation.Qualifier("courseRetrievalExecutor") Executor executor
    ) {
        return new TieredCourseRetrievalService(searchGateway, executor);
    }
}
