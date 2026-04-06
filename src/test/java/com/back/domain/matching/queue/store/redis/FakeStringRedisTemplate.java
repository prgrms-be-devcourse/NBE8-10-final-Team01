package com.back.domain.matching.queue.store.redis;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis queue 전환 로직을 빠르게 검증하기 위한 테스트 전용 템플릿이다.
 *
 * 이번 단계에서는 StringRedisTemplate이 사용하는 최소 기능만 흉내 내고,
 * Lua script는 store가 기대하는 결과만 메모리 자료구조로 재현한다.
 */
public class FakeStringRedisTemplate extends StringRedisTemplate {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, LinkedList<String>> lists = new HashMap<>();
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);

    public FakeStringRedisTemplate() {
        when(valueOperations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        when(listOperations.size(anyString()))
                .thenAnswer(invocation -> (long) list(invocation.getArgument(0)).size());
        when(listOperations.index(anyString(), anyLong())).thenAnswer(invocation -> {
            LinkedList<String> queue = list(invocation.getArgument(0));
            long index = invocation.getArgument(1);
            return index >= 0 && index < queue.size() ? queue.get((int) index) : null;
        });
        doAnswer(invocation -> {
                    values.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(valueOperations)
                .set(anyString(), anyString());
    }

    @Override
    public ValueOperations<String, String> opsForValue() {
        return valueOperations;
    }

    @Override
    public ListOperations<String, String> opsForList() {
        return listOperations;
    }

    @Override
    public Boolean delete(String key) {
        boolean removed = values.remove(key) != null;
        removed = lists.remove(key) != null || removed;
        return removed;
    }

    @Override
    public Long delete(Collection<String> keys) {
        long removedCount = 0L;

        for (String key : keys) {
            if (Boolean.TRUE.equals(delete(key))) {
                removedCount++;
            }
        }

        return removedCount;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        Class<T> resultType = script.getResultType();

        if (Long.class.equals(resultType) && keys.size() == 2 && args.length == 1) {
            return (T) handleEnqueue(keys, args);
        }

        if (Long.class.equals(resultType) && keys.size() == 1 && args.length == 1) {
            return (T) handleQueueContains(keys, args);
        }

        if (List.class.equals(resultType) && keys.size() == 1 && args.length == 1) {
            return (T) handlePoll(keys, args);
        }

        if (List.class.equals(resultType) && keys.size() == 2 && args.length == 1) {
            return (T) handleCancel(keys, args);
        }

        if (Long.class.equals(resultType) && keys.size() >= 2 && args.length >= 1) {
            return (T) handleRollback(keys, args);
        }

        throw new UnsupportedOperationException("테스트 템플릿이 처리하지 않는 Redis script 호출입니다.");
    }

    private Long handleEnqueue(List<String> keys, Object[] args) {
        String userQueueKey = keys.get(0);
        String queueKey = keys.get(1);
        String payload = String.valueOf(args[0]);

        if (values.containsKey(userQueueKey)) {
            return -1L;
        }

        values.put(userQueueKey, payload);
        list(queueKey).addLast(payload);
        return (long) list(queueKey).size();
    }

    private Long handleQueueContains(List<String> keys, Object[] args) {
        return list(keys.get(0)).contains(String.valueOf(args[0])) ? 1L : 0L;
    }

    private List<String> handlePoll(List<String> keys, Object[] args) {
        LinkedList<String> queue = list(keys.get(0));
        int count = Integer.parseInt(String.valueOf(args[0]));

        if (queue.size() < count) {
            return List.of();
        }

        List<String> result = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            result.add(queue.removeFirst());
        }

        if (queue.isEmpty()) {
            lists.remove(keys.get(0));
        }

        return result;
    }

    private List<Long> handleCancel(List<String> keys, Object[] args) {
        String userQueueKey = keys.get(0);
        String queueKey = keys.get(1);
        String payload = String.valueOf(args[0]);

        LinkedList<String> queue = list(queueKey);
        long removed = queue.removeFirstOccurrence(payload) ? 1L : 0L;
        values.remove(userQueueKey);

        if (queue.isEmpty()) {
            lists.remove(queueKey);
        }

        return List.of(removed, (long) queue.size());
    }

    private Long handleRollback(List<String> keys, Object[] args) {
        LinkedList<String> queue = list(keys.get(0));

        for (int i = args.length - 1; i >= 0; i--) {
            queue.addFirst(String.valueOf(args[i]));
        }

        for (int i = 1; i < keys.size(); i++) {
            values.put(keys.get(i), String.valueOf(args[i - 1]));
        }

        return (long) queue.size();
    }

    private LinkedList<String> list(String key) {
        return lists.computeIfAbsent(key, ignored -> new LinkedList<>());
    }
}
