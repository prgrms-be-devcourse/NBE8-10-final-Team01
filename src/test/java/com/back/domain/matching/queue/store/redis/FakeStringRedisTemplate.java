package com.back.domain.matching.queue.store.redis;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis matching store 테스트를 빠르게 검증하기 위한 테스트 전용 템플릿이다.
 *
 * StringRedisTemplate 이 사용하는 최소 기능만 흉내 내고,
 * store 가 기대하는 Lua script 결과를 메모리 자료구조로 재현한다.
 */
public class FakeStringRedisTemplate extends StringRedisTemplate {

    private final Object monitor = new Object();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, LinkedList<String>> lists = new HashMap<>();
    private final Map<String, Map<String, Double>> zsets = new HashMap<>();
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    public FakeStringRedisTemplate() {
        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            synchronized (monitor) {
                return values.get(invocation.getArgument(0));
            }
        });
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            synchronized (monitor) {
                String key = invocation.getArgument(0);
                long nextValue = Long.parseLong(values.getOrDefault(key, "0")) + 1L;
                values.put(key, String.valueOf(nextValue));
                return nextValue;
            }
        });
        when(listOperations.size(anyString())).thenAnswer(invocation -> {
            synchronized (monitor) {
                return (long) list(invocation.getArgument(0)).size();
            }
        });
        when(listOperations.index(anyString(), anyLong())).thenAnswer(invocation -> {
            synchronized (monitor) {
                LinkedList<String> queue = list(invocation.getArgument(0));
                long index = invocation.getArgument(1);
                return index >= 0 && index < queue.size() ? queue.get((int) index) : null;
            }
        });
        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
            synchronized (monitor) {
                zset(invocation.getArgument(0)).put(invocation.getArgument(1), invocation.getArgument(2));
                return true;
            }
        });
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble())).thenAnswer(invocation -> {
            synchronized (monitor) {
                String key = invocation.getArgument(0);
                double min = invocation.getArgument(1);
                double max = invocation.getArgument(2);

                Set<String> result = new LinkedHashSet<>();
                zset(key).entrySet().stream()
                        .filter(entry -> entry.getValue() >= min && entry.getValue() <= max)
                        .sorted(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .forEach(result::add);
                return result;
            }
        });
        when(zSetOperations.remove(anyString(), anyString())).thenAnswer(invocation -> {
            synchronized (monitor) {
                Map<String, Double> zset = zset(invocation.getArgument(0));
                long removed = 0L;

                Object[] arguments = invocation.getArguments();
                for (int i = 1; i < arguments.length; i++) {
                    if (zset.remove(String.valueOf(arguments[i])) != null) {
                        removed++;
                    }
                }

                return removed;
            }
        });
        when(zSetOperations.score(anyString(), anyString())).thenAnswer(invocation -> {
            synchronized (monitor) {
                Object member = invocation.getArgument(1);
                return zset(invocation.getArgument(0)).get(String.valueOf(member));
            }
        });
        doAnswer(invocation -> {
                    synchronized (monitor) {
                        values.put(invocation.getArgument(0), invocation.getArgument(1));
                    }
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
    public ZSetOperations<String, String> opsForZSet() {
        return zSetOperations;
    }

    @Override
    public Boolean delete(String key) {
        synchronized (monitor) {
            boolean removed = values.remove(key) != null;
            removed = lists.remove(key) != null || removed;
            removed = zsets.remove(key) != null || removed;
            return removed;
        }
    }

    @Override
    public Long delete(Collection<String> keys) {
        synchronized (monitor) {
            long removedCount = 0L;

            for (String key : keys) {
                if (Boolean.TRUE.equals(delete(key))) {
                    removedCount++;
                }
            }

            return removedCount;
        }
    }

    @Override
    public Set<String> keys(String pattern) {
        synchronized (monitor) {
            Set<String> result = new LinkedHashSet<>();
            values.keySet().stream().filter(key -> matches(key, pattern)).forEach(result::add);
            lists.keySet().stream().filter(key -> matches(key, pattern)).forEach(result::add);
            zsets.keySet().stream().filter(key -> matches(key, pattern)).forEach(result::add);
            return result;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        synchronized (monitor) {
            String scriptText = script.getScriptAsString();

            if (scriptText.contains("MATCHING:QUEUE_ENQUEUE")) {
                return (T) handleEnqueue(keys, args);
            }

            if (scriptText.contains("MATCHING:QUEUE_CONTAINS")) {
                return (T) handleQueueContains(keys, args);
            }

            if (scriptText.contains("MATCHING:QUEUE_POLL")) {
                return (T) handlePoll(keys, args);
            }

            if (scriptText.contains("MATCHING:QUEUE_CANCEL")) {
                return (T) handleCancel(keys, args);
            }

            if (scriptText.contains("MATCHING:QUEUE_ROLLBACK")) {
                return (T) handleRollback(keys, args);
            }

            if (scriptText.contains("MATCHING:MATCH_MARK_ACCEPT_PENDING")) {
                return (T) handleMarkAcceptPending(keys, args);
            }

            if (scriptText.contains("MATCHING:MATCH_COMPARE_AND_SET")) {
                return (T) handleCompareAndSet(keys, args);
            }

            if (scriptText.contains("MATCHING:DELETE_IF_VALUE")) {
                return (T) handleDeleteIfValue(keys, args);
            }

            if (scriptText.contains("MATCHING:MATCH_CLEAR_TERMINAL")) {
                return (T) handleClearTerminal(keys, args);
            }

            throw new UnsupportedOperationException("테스트 템플릿이 처리하지 않는 Redis script 호출입니다.");
        }
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

    private String handleMarkAcceptPending(List<String> keys, Object[] args) {
        String sessionJson = String.valueOf(args[0]);
        String matchId = String.valueOf(args[1]);
        int participantCount = Integer.parseInt(String.valueOf(args[2]));
        double deadlineScore = Double.parseDouble(String.valueOf(args[3]));

        values.put(keys.get(0), sessionJson);
        zset(keys.get(1)).put(matchId, deadlineScore);

        for (int i = 1; i <= participantCount; i++) {
            values.put(keys.get(i + 1), matchId);
        }

        for (int i = participantCount + 2; i < keys.size(); i++) {
            values.remove(keys.get(i));
        }

        return sessionJson;
    }

    private Long handleCompareAndSet(List<String> keys, Object[] args) {
        String key = keys.get(0);
        String expected = String.valueOf(args[0]);
        String updated = String.valueOf(args[1]);
        String current = values.get(key);

        if (!expected.equals(current)) {
            return 0L;
        }

        values.put(key, updated);
        return 1L;
    }

    private Long handleDeleteIfValue(List<String> keys, Object[] args) {
        String key = keys.get(0);
        String expected = String.valueOf(args[0]);
        String current = values.get(key);

        if (!expected.equals(current)) {
            return 0L;
        }

        values.remove(key);
        return 1L;
    }

    private Long handleClearTerminal(List<String> keys, Object[] args) {
        String expected = String.valueOf(args[0]);

        for (int i = 1; i < keys.size(); i++) {
            if (expected.equals(values.get(keys.get(i)))) {
                values.remove(keys.get(i));
            }
        }

        values.remove(keys.get(0));
        return 1L;
    }

    private boolean matches(String key, String pattern) {
        int wildcardIndex = pattern.indexOf('*');
        if (wildcardIndex < 0) {
            return key.equals(pattern);
        }

        String prefix = pattern.substring(0, wildcardIndex);
        String suffix = pattern.substring(wildcardIndex + 1);
        return key.startsWith(prefix) && key.endsWith(suffix);
    }

    private LinkedList<String> list(String key) {
        return lists.computeIfAbsent(key, ignored -> new LinkedList<>());
    }

    private Map<String, Double> zset(String key) {
        return zsets.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
    }
}
