package com.back.domain.matching.queue.store;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * matching 저장소 설정 바인딩을 연다.
 *
 * 구현체 선택은 `matching.store.type` 값으로 제어하고,
 * 각 저장소 구현체는 조건부 빈으로 연결한다.
 */
@Configuration
@EnableConfigurationProperties(MatchingStoreProperties.class)
public class MatchingStorePropertiesConfig {}
