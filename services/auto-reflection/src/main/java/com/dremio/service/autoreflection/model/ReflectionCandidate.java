package com.dremio.service.autoreflection.model;

import java.util.List;
import java.util.Objects;

/**
 * 反射候选 - 代表一个可能的反射配置
 * 集成 Dremio 的反射类型
 */
public class ReflectionCandidate {

    private String candidateId;
    private String candidateHash;       // 用于去重的hash
    private String datasetPath;         // 数据集路径
    private String datasetId;           // 数据集ID
    private List<String> tables;       // 涉及的表
    private List<String> displayFields;  // 展示字段
    private List<String> groupByFields;  // 分组字段
    private List<String> orderByFields;  // 排序字段
    private List<String> partitionFields; // 分区字段
    private List<String> filterFields;   // 过滤字段
    private AggregationType aggregationType;  // 聚合类型
    private double estimatedBenefit;     // 预估收益 (0-100)
    private double estimatedStorageCost;  // 预估存储成本
    private double frequencyScore;       // 频率得分
    private double avgExecutionTimeReduction; // 平均执行时间减少
    private double confidenceScore;       // 置信度
    private int queryCount;             // 涉及此候选的查询数量
    private long createdAt;
    private CandidateStatus status;
    private RecommendationReason reason;

    public enum AggregationType {
        NONE,           // 原始数据反射 (RAW)
        GROUP_BY,       // 聚合反射 (AGGREGATION)
        JOIN,           // Join反射
        MATERIALIZED_VIEW  // 物化视图
    }

    public enum CandidateStatus {
        PENDING,        // 待评估
        APPROVED,       // 批准
        REJECTED,      // 拒绝
        CREATED,       // 已创建反射
        FAILED         // 创建失败
    }

    public enum RecommendationReason {
        HIGH_FREQUENCY,       // 高频访问
        SLOW_QUERY,           // 慢查询
        AGGREGATE_ACCEL,     // 聚合加速
        JOIN_OPTIMIZATION,    // JOIN优化
        SORT_OPTIMIZATION,    // 排序优化
        COMPOSITE            // 复合原因
    }

    // Getters and Setters
    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public String getCandidateHash() { return candidateHash; }
    public void setCandidateHash(String candidateHash) { this.candidateHash = candidateHash; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public List<String> getTables() { return tables; }
    public void setTables(List<String> tables) { this.tables = tables; }

    public List<String> getDisplayFields() { return displayFields; }
    public void setDisplayFields(List<String> displayFields) { this.displayFields = displayFields; }

    public List<String> getGroupByFields() { return groupByFields; }
    public void setGroupByFields(List<String> groupByFields) { this.groupByFields = groupByFields; }

    public List<String> getOrderByFields() { return orderByFields; }
    public void setOrderByFields(List<String> orderByFields) { this.orderByFields = orderByFields; }

    public List<String> getPartitionFields() { return partitionFields; }
    public void setPartitionFields(List<String> partitionFields) { this.partitionFields = partitionFields; }

    public List<String> getFilterFields() { return filterFields; }
    public void setFilterFields(List<String> filterFields) { this.filterFields = filterFields; }

    public AggregationType getAggregationType() { return aggregationType; }
    public void setAggregationType(AggregationType aggregationType) { this.aggregationType = aggregationType; }

    public double getEstimatedBenefit() { return estimatedBenefit; }
    public void setEstimatedBenefit(double estimatedBenefit) { this.estimatedBenefit = estimatedBenefit; }

    public double getEstimatedStorageCost() { return estimatedStorageCost; }
    public void setEstimatedStorageCost(double estimatedStorageCost) { this.estimatedStorageCost = estimatedStorageCost; }

    public double getFrequencyScore() { return frequencyScore; }
    public void setFrequencyScore(double frequencyScore) { this.frequencyScore = frequencyScore; }

    public double getAvgExecutionTimeReduction() { return avgExecutionTimeReduction; }
    public void setAvgExecutionTimeReduction(double avgExecutionTimeReduction) { this.avgExecutionTimeReduction = avgExecutionTimeReduction; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public int getQueryCount() { return queryCount; }
    public void setQueryCount(int queryCount) { this.queryCount = queryCount; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public CandidateStatus getStatus() { return status; }
    public void setStatus(CandidateStatus status) { this.status = status; }

    public RecommendationReason getReason() { return reason; }
    public void setReason(RecommendationReason reason) { this.reason = reason; }

    /**
     * 计算综合得分
     */
    public double getCompositeScore() {
        return (estimatedBenefit * 0.6) + (frequencyScore * 0.2) + (confidenceScore * 100 * 0.2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReflectionCandidate that = (ReflectionCandidate) o;
        return Objects.equals(candidateHash, that.candidateHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidateHash);
    }

    @Override
    public String toString() {
        return "ReflectionCandidate{" +
                "candidateId='" + candidateId + '\'' +
                ", datasetPath='" + datasetPath + '\'' +
                ", aggregationType=" + aggregationType +
                ", estimatedBenefit=" + estimatedBenefit +
                ", frequencyScore=" + frequencyScore +
                ", status=" + status +
                ", reason=" + reason +
                '}';
    }
}
