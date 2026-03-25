package com.dremio.service.autoreflection.collector;

import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import com.dremio.service.autoreflection.model.QueryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 查询历史收集器实现
 */
public class QueryHistoryCollectorImpl implements QueryHistoryCollector {

    private static final Logger logger = LoggerFactory.getLogger(QueryHistoryCollectorImpl.class);

    private final AutoReflectionConfig config;
    private final Map<String, QueryRecord> queryHistory = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> datasetQueryCount = new ConcurrentHashMap<>();
    private final AtomicInteger totalQueries = new AtomicInteger(0);
    private final Map<String, List<QueryRecord>> datasetHistory = new ConcurrentHashMap<>();
    private final Object cleanupLock = new Object();
    private long lastCleanupTime = 0;

    public QueryHistoryCollectorImpl(AutoReflectionConfig config) {
        this.config = config;
    }

    @Override
    public void onQuerySubmitted(QueryRecord record) {
        if (!config.isEnabled()) {
            return;
        }

        // 频率过滤
        if (record.getDuration() > 0 && record.getDuration() < config.getMinExecutionTimeMs()) {
            logger.debug("Query {} filtered by execution time threshold", record.getQueryId());
            return;
        }

        queryHistory.put(record.getQueryId(), record);
        totalQueries.incrementAndGet();

        // 更新数据集统计
        if (record.getDataset() != null) {
            datasetQueryCount.computeIfAbsent(record.getDataset(), k -> new AtomicInteger(0)).incrementAndGet();
            datasetHistory.computeIfAbsent(record.getDataset(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(record);
        }

        logger.debug("Recorded query: {} on dataset: {}", record.getQueryId(), record.getDataset());

        // 定期清理过期数据
        maybeCleanup();
    }

    @Override
    public void onQueryCompleted(QueryRecord record) {
        QueryRecord existing = queryHistory.get(record.getQueryId());
        if (existing != null) {
            existing.setDuration(record.getDuration());
            existing.setRowsScanned(record.getRowsScanned());
            existing.setRowsReturned(record.getRowsReturned());
            existing.setAccelerated(record.isAccelerated());
            existing.setAccelerationUsed(record.getAccelerationUsed());
            existing.setQueryPlan(record.getQueryPlan());
        }
    }

    @Override
    public void onQueryFailed(QueryRecord record) {
        logger.warn("Query failed: {} - {}", record.getQueryId(), record.getSql());
    }

    @Override
    public List<QueryRecord> getRecentQueries(int limit) {
        return queryHistory.values().stream()
                .sorted((r1, r2) -> Long.compare(r2.getStartTime(), r1.getStartTime()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<QueryRecord> getQueriesByTimeRange(long startTime, long endTime) {
        return queryHistory.values().stream()
                .filter(r -> r.getStartTime() >= startTime && r.getStartTime() <= endTime)
                .collect(Collectors.toList());
    }

    @Override
    public List<QueryRecord> getQueriesByDataset(String dataset) {
        return new ArrayList<>(datasetHistory.getOrDefault(dataset, Collections.emptyList()));
    }

    @Override
    public void cleanupExpiredData() {
        synchronized (cleanupLock) {
            long expirationTime = System.currentTimeMillis() - 
                    (config.getHistoryRetentionDays() * 24L * 60L * 60L * 1000L);
            
            int beforeSize = queryHistory.size();
            
            queryHistory.entrySet().removeIf(entry -> entry.getValue().getStartTime() < expirationTime);
            
            datasetHistory.values().forEach(list -> 
                    list.removeIf(record -> record.getStartTime() < expirationTime));
            
            // 清理空的数据集条目
            datasetHistory.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            
            int removed = beforeSize - queryHistory.size();
            logger.info("Cleaned up {} expired query records. Current size: {}", removed, queryHistory.size());
            lastCleanupTime = System.currentTimeMillis();
        }
    }

    private void maybeCleanup() {
        long timeSinceLastCleanup = System.currentTimeMillis() - lastCleanupTime;
        if (timeSinceLastCleanup > 60 * 60 * 1000) { // 每小时最多清理一次
            cleanupExpiredData();
        }
    }

    @Override
    public int getTotalQueryCount() {
        return totalQueries.get();
    }

    @Override
    public Map<String, Integer> getDatasetStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        datasetQueryCount.forEach((dataset, count) -> stats.put(dataset, count.get()));
        return stats;
    }

    @Override
    public List<String> getTopFrequentDatasets(int topN) {
        return datasetQueryCount.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
