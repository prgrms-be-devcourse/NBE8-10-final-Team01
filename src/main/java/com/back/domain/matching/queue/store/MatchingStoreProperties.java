package com.back.domain.matching.queue.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * matching 상태 저장소 선택 설정을 묶는다.
 *
 * 이번 단계에서는 설정 구조만 먼저 도입하고,
 * 실제 구현체 전환은 다음 하위 이슈에서 연결한다.
 */
@ConfigurationProperties(prefix = "matching.store")
public class MatchingStoreProperties {

    /**
     * 기본값은 기존 InMemory 저장소를 유지한다.
     */
    private StoreType type = StoreType.MEMORY;

    public StoreType getType() {
        return type;
    }

    public void setType(StoreType type) {
        this.type = type;
    }

    public enum StoreType {
        MEMORY,
        REDIS
    }
}
