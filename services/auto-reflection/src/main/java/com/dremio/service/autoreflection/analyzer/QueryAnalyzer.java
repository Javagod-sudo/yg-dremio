package com.dremio.service.autoreflection.analyzer;

import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import com.dremio.service.autoreflection.model.QueryRecord;
import com.dremio.service.autoreflection.model.ReflectionCandidate;
import com.dremio.service.autoreflection.util.SqlAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询分析器 - 从历史查询中提取反射候选
 */
public class QueryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(QueryAnalyzer.class);

    private final AutoReflectionConfig config;

    public QueryAnalyzer(AutoReflectionConfig config) {
        this.config = config;
    }

    /**
     * 分析查询历史，生成候选反射
     */
    public List<ReflectionCandidate> analyze(List<QueryRecord> queries) {
        logger.info("Starting analysis of {} queries", queries.size());
        
        if (queries.isEmpty()) {
            logger.info("No queries to analyze");
            return Collections.emptyList();
        }

        // 1. 识别聚合反射候选
        List<ReflectionCandidate> aggregateCandidates = identifyAggregateCandidates(queries);
        
        // 2. 识别Join反射候选
        List<ReflectionCandidate> joinCandidates = identifyJoinCandidates(queries);
        
        // 3. 识别原始数据反射候选（高频SELECT）
        List<ReflectionCandidate> rawCandidates = identifyRawCandidates(queries);
        
        // 4. 合并去重
        List<ReflectionCandidate> allCandidates = new ArrayList<>();
        allCandidates.addAll(aggregateCandidates);
        allCandidates.addAll(joinCandidates);
        allCandidates.addAll(rawCandidates);
        
        // 5. 计算收益评估
        calculateBenefitScores(allCandidates, queries);

        logger.info("Generated {} reflection candidates", allCandidates.size());
        return allCandidates;
    }

    /**
     * 识别聚合反射候选
     */
    private List<ReflectionCandidate> identifyAggregateCandidates(List<QueryRecord> queries) {
        List<ReflectionCandidate> candidates = new ArrayList<>();
        
        // 按 (表 + GROUP BY字段) 分组
        Map<String, List<QueryRecord>> grouped = queries.stream()
                .filter(q -> q.getQueryType() == QueryRecord.QueryType.AGGREGATE)
                .collect(Collectors.groupingBy(q -> 
                        (q.getDataset() != null ? q.getDataset() : "unknown") + "|" + 
                        String.join(",", q.getGroupByFields() != null ? q.getGroupByFields() : Collections.emptyList())));

        for (Map.Entry<String, List<QueryRecord>> entry : grouped.entrySet()) {
            List<QueryRecord> groupQueries = entry.getValue();
            if (groupQueries.size() < config.getMinCandidateQueries()) {
                continue;
            }

            QueryRecord first = groupQueries.get(0);
            
            ReflectionCandidate candidate = new ReflectionCandidate();
            candidate.setCandidateId(UUID.randomUUID().toString());
            candidate.setCandidateHash(SqlAnalyzer.computeCandidateHash(
                    first.getDataset(), 
                    first.getGroupByFields(), 
                    first.getSelectFields(),
                    "AGGREGATE"));
            candidate.setTables(first.getAllDatasets());
            candidate.setDatasetPath(first.getDataset());
            candidate.setDisplayFields(first.getSelectFields());
            candidate.setGroupByFields(first.getGroupByFields());
            candidate.setAggregationType(ReflectionCandidate.AggregationType.GROUP_BY);
            candidate.setQueryCount(groupQueries.size());
            candidate.setCreatedAt(System.currentTimeMillis());
            candidate.setStatus(ReflectionCandidate.CandidateStatus.PENDING);
            candidate.setReason(ReflectionCandidate.RecommendationReason.AGGREGATE_ACCEL);

            // 计算平均执行时间减少（假设反射可减少50%）
            double avgDuration = groupQueries.stream()
                    .mapToLong(QueryRecord::getDuration)
                    .average()
                    .orElse(0);
            candidate.setAvgExecutionTimeReduction(avgDuration * 0.5);

            candidates.add(candidate);
        }

        return candidates;
    }

    /**
     * 识别Join反射候选
     */
    private List<ReflectionCandidate> identifyJoinCandidates(List<QueryRecord> queries) {
        List<ReflectionCandidate> candidates = new ArrayList<>();
        
        Map<String, List<QueryRecord>> grouped = queries.stream()
                .filter(q -> q.getQueryType() == QueryRecord.QueryType.JOIN)
                .collect(Collectors.groupingBy(q -> {
                    List<String> tables = q.getAllDatasets() != null ? q.getAllDatasets() : Collections.emptyList();
                    Collections.sort(tables);
                    return String.join(",", tables);
                }));

        for (Map.Entry<String, List<QueryRecord>> entry : grouped.entrySet()) {
            List<QueryRecord> groupQueries = entry.getValue();
            if (groupQueries.size() < config.getMinCandidateQueries()) {
                continue;
            }

            QueryRecord first = groupQueries.get(0);
            
            ReflectionCandidate candidate = new ReflectionCandidate();
            candidate.setCandidateId(UUID.randomUUID().toString());
            candidate.setCandidateHash(SqlAnalyzer.computeCandidateHash(
                    first.getDataset(), 
                    null, 
                    first.getSelectFields(),
                    "JOIN"));
            candidate.setTables(first.getAllDatasets());
            candidate.setDatasetPath(first.getDataset());
            candidate.setDisplayFields(first.getSelectFields());
            candidate.setJoinTables(first.getJoinTables());
            candidate.setJoinConditions(first.getJoinConditions());
            candidate.setAggregationType(ReflectionCandidate.AggregationType.JOIN);
            candidate.setQueryCount(groupQueries.size());
            candidate.setCreatedAt(System.currentTimeMillis());
            candidate.setStatus(ReflectionCandidate.CandidateStatus.PENDING);
            candidate.setReason(ReflectionCandidate.RecommendationReason.JOIN_OPTIMIZATION);

            double avgDuration = groupQueries.stream()
                    .mapToLong(QueryRecord::getDuration)
                    .average()
                    .orElse(0);
            candidate.setAvgExecutionTimeReduction(avgDuration * 0.3);

            candidates.add(candidate);
        }

        return candidates;
    }

    /**
     * 识别原始数据反射候选（高频SELECT）
     */
    private List<ReflectionCandidate> identifyRawCandidates(List<QueryRecord> queries) {
        List<ReflectionCandidate> candidates = new ArrayList<>();
        
        // 按表分组，统计高频访问
        Map<String, Long> tableCounts = queries.stream()
                .filter(q -> q.getQueryType() == QueryRecord.QueryType.SELECT)
                .flatMap(q -> q.getAllDatasets() != null ? q.getAllDatasets().stream() : Collections.emptyList())
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        for (Map.Entry<String, Long> entry : tableCounts.entrySet()) {
            if (entry.getValue() >= config.getMinCandidateQueries()) {
                ReflectionCandidate candidate = new ReflectionCandidate();
                candidate.setCandidateId(UUID.randomUUID().toString());
                candidate.setCandidateHash(SqlAnalyzer.computeCandidateHash(
                        entry.getKey(), 
                        null, 
                        null,
                        "RAW"));
                candidate.setTables(Collections.singletonList(entry.getKey()));
                candidate.setDatasetPath(entry.getKey());
                candidate.setAggregationType(ReflectionCandidate.AggregationType.NONE);
                candidate.setQueryCount(entry.getValue().intValue());
                candidate.setCreatedAt(System.currentTimeMillis());
                candidate.setStatus(ReflectionCandidate.CandidateStatus.PENDING);
                candidate.setReason(ReflectionCandidate.RecommendationReason.HIGH_FREQUENCY);
                
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    /**
     * 计算候选的收益得分
     */
    private void calculateBenefitScores(List<ReflectionCandidate> candidates, List<QueryRecord> queries) {
        for (ReflectionCandidate candidate : candidates) {
            // 频率得分 (0-40)
            double frequencyScore = Math.min(candidate.getQueryCount() * 5, 40);
            candidate.setFrequencyScore(frequencyScore);

            // 执行时间减少得分 (0-40)
            double durationScore = Math.min(candidate.getAvgExecutionTimeReduction() / 100, 40);
            candidate.setEstimatedBenefit(durationScore);

            // 类型加成
            switch (candidate.getAggregationType()) {
                case GROUP_BY:
                    candidate.setEstimatedBenefit(candidate.getEstimatedBenefit() + 15);
                    break;
                case JOIN:
                    candidate.setEstimatedBenefit(candidate.getEstimatedBenefit() + 10);
                    break;
                case NONE:
                    candidate.setEstimatedBenefit(candidate.getEstimatedBenefit() + 5);
                    break;
            }

            // 置信度得分 (基于样本数量)
            double confidence = Math.min(candidate.getQueryCount() / 20.0, 1.0);
            candidate.setConfidenceScore(confidence);

            // 存储成本估算 (简化版：基于字段数量)
            int fieldCount = 0;
            if (candidate.getDisplayFields() != null) fieldCount += candidate.getDisplayFields().size();
            if (candidate.getGroupByFields() != null) fieldCount += candidate.getGroupByFields().size();
            candidate.setEstimatedStorageCost(fieldCount * 0.1);  // 每个字段约0.1MB

            // 推荐理由
            if (candidate.getQueryCount() > 10) {
                candidate.setReason(ReflectionCandidate.RecommendationReason.HIGH_FREQUENCY);
            } else if (candidate.getAvgExecutionTimeReduction() > 5000) {
                candidate.setReason(ReflectionCandidate.RecommendationReason.SLOW_QUERY);
            }
        }
    }
}
