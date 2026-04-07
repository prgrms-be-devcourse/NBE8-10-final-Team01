package com.back.domain.matching.queue.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * matching 상태 저장소 선택 설정을 묶는다.
 *
 * 기본값은 기존 InMemory 저장소를 유지하고,
 * dev 등 필요한 환경에서 Redis 저장소로 전환할 수 있게 한다.
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
