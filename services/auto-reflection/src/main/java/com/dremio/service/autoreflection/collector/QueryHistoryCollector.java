package com.dremio.service.autoreflection.collector;

import com.dremio.service.autoreflection.model.QueryRecord;
import java.util.List;

/**
 * 查询历史收集器接口
 */
public interface QueryHistoryCollector {

    /**
     * 记录查询提交
     */
    void onQuerySubmitted(QueryRecord record);

    /**
     * 记录查询完成
     */
    void onQueryCompleted(QueryRecord record);

    /**
     * 记录查询失败
     */
    void onQueryFailed(QueryRecord record);

    /**
     * 获取最近N条查询记录
     */
    List<QueryRecord> getRecentQueries(int limit);

    /**
     * 获取指定时间范围内的查询记录
     */
    List<QueryRecord> getQueriesByTimeRange(long startTime, long endTime);

    /**
     * 获取指定表的查询记录
     */
    List<QueryRecord> getQueriesByDataset(String dataset);

    /**
     * 清理过期数据
     */
    void cleanupExpiredData();

    /**
     * 获取查询总数
     */
    int getTotalQueryCount();

    /**
     * 获取数据集统计
     */
    java.util.Map<String, Integer> getDatasetStatistics();

    /**
     * 获取频率最高的N个数据集
     */
    List<String> getTopFrequentDatasets(int topN);
}
