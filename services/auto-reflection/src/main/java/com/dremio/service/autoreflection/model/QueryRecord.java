package com.dremio.service.autoreflection.model;

import java.util.List;
import java.util.Objects;

/**
 * 查询记录 - 保存每次查询的关键信息
 * 用于自动反射系统的查询历史收集
 */
public class QueryRecord {

    private String queryId;
    private String sql;
    private String sqlNormalized;      // 标准化后的SQL，用于去重
    private String user;
    private String dataset;            // 主要访问的表/视图
    private List<String> allDatasets;  // 所有涉及的表
    private List<String> selectFields;  // SELECT 字段
    private List<String> groupByFields; // GROUP BY 字段
    private List<String> orderByFields;  // ORDER BY 字段
    private List<String> filterFields;   // WHERE 条件字段
    private List<String> joinTables;     // JOIN 的表
    private List<String> joinConditions; // JOIN 条件
    private QueryType queryType;        // 查询类型
    private long startTime;
    private long duration;              // 执行时间(ms)
    private long rowsScanned;           // 扫描行数
    private long rowsReturned;           // 返回行数
    private boolean accelerated;         // 是否被反射加速
    private String accelerationUsed;     // 使用的反射ID
    private String queryPlan;           // 执行计划

    public enum QueryType {
        SELECT,
        JOIN,
        AGGREGATE,
        UNION,
        INSERT,
        OTHER
    }

    // Getters and Setters
    public String getQueryId() { return queryId; }
    public void setQueryId(String queryId) { this.queryId = queryId; }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public String getSqlNormalized() { return sqlNormalized; }
    public void setSqlNormalized(String sqlNormalized) { this.sqlNormalized = sqlNormalized; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }

    public List<String> getAllDatasets() { return allDatasets; }
    public void setAllDatasets(List<String> allDatasets) { this.allDatasets = allDatasets; }

    public List<String> getSelectFields() { return selectFields; }
    public void setSelectFields(List<String> selectFields) { this.selectFields = selectFields; }

    public List<String> getGroupByFields() { return groupByFields; }
    public void setGroupByFields(List<String> groupByFields) { this.groupByFields = groupByFields; }

    public List<String> getOrderByFields() { return orderByFields; }
    public void setOrderByFields(List<String> orderByFields) { this.orderByFields = orderByFields; }

    public List<String> getFilterFields() { return filterFields; }
    public void setFilterFields(List<String> filterFields) { this.filterFields = filterFields; }

    public List<String> getJoinTables() { return joinTables; }
    public void setJoinTables(List<String> joinTables) { this.joinTables = joinTables; }

    public List<String> getJoinConditions() { return joinConditions; }
    public void setJoinConditions(List<String> joinConditions) { this.joinConditions = joinConditions; }

    public QueryType getQueryType() { return queryType; }
    public void setQueryType(QueryType queryType) { this.queryType = queryType; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public long getRowsScanned() { return rowsScanned; }
    public void setRowsScanned(long rowsScanned) { this.rowsScanned = rowsScanned; }

    public long getRowsReturned() { return rowsReturned; }
    public void setRowsReturned(long rowsReturned) { this.rowsReturned = rowsReturned; }

    public boolean isAccelerated() { return accelerated; }
    public void setAccelerated(boolean accelerated) { this.accelerated = accelerated; }

    public String getAccelerationUsed() { return accelerationUsed; }
    public void setAccelerationUsed(String accelerationUsed) { this.accelerationUsed = accelerationUsed; }

    public String getQueryPlan() { return queryPlan; }
    public void setQueryPlan(String queryPlan) { this.queryPlan = queryPlan; }

    /**
     * 计算查询的选择性（返回行数/扫描行数）
     */
    public double getSelectivity() {
        if (rowsScanned == 0) return 0;
        return (double) rowsReturned / rowsScanned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryRecord that = (QueryRecord) o;
        return Objects.equals(sqlNormalized, that.sqlNormalized);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sqlNormalized);
    }

    @Override
    public String toString() {
        return "QueryRecord{" +
                "queryId='" + queryId + '\'' +
                ", dataset='" + dataset + '\'' +
                ", queryType=" + queryType +
                ", duration=" + duration + "ms" +
                ", rowsScanned=" + rowsScanned +
                ", accelerated=" + accelerated +
                '}';
    }
}
