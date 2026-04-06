package com.back.domain.matching.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MatchingStorePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(MatchingStorePropertiesConfig.class);

    @Test
    @DisplayName("matching.store.type 기본값은 memory 로 바인딩된다")
    void defaultTypeIsMemory() {
        contextRunner.run(context -> {
            MatchingStoreProperties properties = context.getBean(MatchingStoreProperties.class);

            assertThat(properties.getType()).isEqualTo(MatchingStoreProperties.StoreType.MEMORY);
        });
    }

    @Test
    @DisplayName("matching.store.type 설정값을 redis 로 바인딩할 수 있다")
    void bindsExplicitRedisType() {
        contextRunner.withPropertyValues("matching.store.type=redis").run(context -> {
            MatchingStoreProperties properties = context.getBean(MatchingStoreProperties.class);

            assertThat(properties.getType()).isEqualTo(MatchingStoreProperties.StoreType.REDIS);
        });
    }
}
