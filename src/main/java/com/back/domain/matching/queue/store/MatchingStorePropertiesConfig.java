package com.back.domain.matching.queue.store;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * matching 저장소 설정 바인딩만 먼저 연다.
 *
 * 이번 단계에서는 구현체 스위칭까지 연결하지 않고,
 * 설정 키와 바인딩 구조만 안전하게 고정한다.
 */
@Configuration
@EnableConfigurationProperties(MatchingStoreProperties.class)
public class MatchingStorePropertiesConfig {}
