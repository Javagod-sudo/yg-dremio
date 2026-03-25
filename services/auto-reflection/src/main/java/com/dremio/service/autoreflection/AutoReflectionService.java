package com.dremio.service.autoreflection;

import com.dremio.service.autoreflection.analyzer.QueryAnalyzer;
import com.dremio.service.autoreflection.collector.QueryHistoryCollector;
import com.dremio.service.autoreflection.collector.QueryHistoryCollectorImpl;
import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import com.dremio.service.autoreflection.executor.ReflectionExecutor;
import com.dremio.service.autoreflection.model.QueryRecord;
import com.dremio.service.autoreflection.model.ReflectionCandidate;
import com.dremio.service.autoreflection.recommender.ReflectionRecommender;
import com.dremio.service.autoreflection.scheduler.AutoReflectionScheduler;
import com.dremio.service.Service;
import com.dremio.service.reflection.ReflectionAdministrationService;
import com.dremio.service.reflection.ReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动反射服务 - 整合所有模块的主服务
 * 
 * 这是一个 Dremio Service，负责：
 * 1. 收集查询历史
 * 2. 分析查询模式
 * 3. 推荐并创建反射
 */
public class AutoReflectionService implements Service {

    private static final Logger logger = LoggerFactory.getLogger(AutoReflectionService.class);

    private final AutoReflectionConfig config;
    private final QueryHistoryCollector collector;
    private final QueryAnalyzer analyzer;
    private final ReflectionRecommender recommender;
    private ReflectionExecutor executor;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AutoReflectionScheduler scheduler;

    public AutoReflectionService(AutoReflectionConfig config, 
                                 ReflectionAdministrationService reflectionAdminService) {
        this.config = config;
        this.collector = new QueryHistoryCollectorImpl(config);
        this.analyzer = new QueryAnalyzer(config);
        this.recommender = new ReflectionRecommender(config);
        this.executor = new ReflectionExecutor(config, reflectionAdminService);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting Auto Reflection Service");
            
            // 初始化已有反射列表
            refreshExistingReflections();
            
            // 启动调度器
            scheduler = new AutoReflectionScheduler(config, this);
            scheduler.start();
            
            logger.info("Auto Reflection Service started successfully. Config: {}", config);
        } else {
            logger.warn("Auto Reflection Service is already running");
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 停止服务
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping Auto Reflection Service");
            
            if (scheduler != null) {
                scheduler.stop();
            }
            
            logger.info("Auto Reflection Service stopped");
        }
    }

    /**
     * 记录查询
     */
    public void recordQuery(QueryRecord record) {
        if (!config.isEnabled()) {
            return;
        }
        collector.onQuerySubmitted(record);
    }

    /**
     * 记录查询完成
     */
    public void recordQueryCompletion(QueryRecord record) {
        if (!config.isEnabled()) {
            return;
        }
        collector.onQueryCompleted(record);
    }

    /**
     * 手动触发一次分析循环
     */
    public AnalysisResult triggerAnalysis() {
        logger.info("Manual analysis triggered");
        return runAnalysisCycle();
    }

    /**
     * 执行一次完整的分析循环
     */
    public synchronized AnalysisResult runAnalysisCycle() {
        long startTime = System.currentTimeMillis();
        
        logger.info("Starting analysis cycle");

        try {
            // 1. 获取历史查询数据
            long historyStartTime = System.currentTimeMillis() - 
                    (config.getAggregationWindowDays() * 24L * 60L * 60L * 1000L);
            List<QueryRecord> historicalQueries = collector.getQueriesByTimeRange(historyStartTime, System.currentTimeMillis());
            
            logger.info("Retrieved {} historical queries", historicalQueries.size());
            
            if (historicalQueries.isEmpty()) {
                return new AnalysisResult(AnalysisResult.Status.NO_DATA, 0, 0, 0, 0, System.currentTimeMillis() - startTime);
            }

            // 2. 分析查询，生成候选
            List<ReflectionCandidate> candidates = analyzer.analyze(historicalQueries);
            logger.info("Generated {} candidates", candidates.size());

            // 3. 评估候选
            List<ReflectionCandidate> evaluatedCandidates = recommender.evaluateCandidates(candidates);
            logger.info("Evaluated {} candidates", evaluatedCandidates.size());

            // 4. 获取待创建的候选
            List<ReflectionCandidate> toCreate = recommender.getCandidatesToCreate(evaluatedCandidates);
            
            // 5. 执行创建
            List<ReflectionExecutor.CreationResult> results = executor.execute(toCreate);
            
            // 6. 统计结果
            long successCount = results.stream().filter(r -> r.isSuccess()).count();
            long failureCount = results.stream().filter(r -> !r.isSuccess()).count();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Analysis cycle complete. Duration: {}ms, Candidates: {}, Created: {}", 
                    duration, evaluatedCandidates.size(), successCount);

            return new AnalysisResult(
                    AnalysisResult.Status.SUCCESS,
                    historicalQueries.size(),
                    candidates.size(),
                    evaluatedCandidates.size(),
                    (int) successCount,
                    duration
            );

        } catch (Exception e) {
            logger.error("Analysis cycle failed", e);
            return new AnalysisResult(
                    AnalysisResult.Status.ERROR,
                    0, 0, 0, 0,
                    System.currentTimeMillis() - startTime
            );
        }
    }

    /**
     * 刷新已有反射列表
     */
    public void refreshExistingReflections() {
        // TODO: 从 ReflectionAdministrationService 获取已有反射列表
        // 并更新 recommender 的 existingReflectionHashes
    }

    /**
     * 获取当前状态
     */
    public ServiceStatus getStatus() {
        ServiceStatus status = new ServiceStatus();
        status.enabled = config.isEnabled();
        status.running = running.get();
        status.totalQueries = collector.getTotalQueryCount();
        status.datasetStatistics = collector.getDatasetStatistics();
        return status;
    }

    // Getters
    public QueryHistoryCollector getCollector() { return collector; }
    public AutoReflectionConfig getConfig() { return config; }
    public boolean isRunning() { return running.get(); }

    /**
     * 分析结果封装
     */
    public static class AnalysisResult {
        public enum Status {
            SUCCESS,
            NO_DATA,
            ERROR
        }

        private final Status status;
        private final int queryCount;
        private final int candidateCount;
        private final int evaluatedCount;
        private final int createdCount;
        private final long durationMs;

        public AnalysisResult(Status status, int queryCount, int candidateCount, 
                            int evaluatedCount, int createdCount, long durationMs) {
            this.status = status;
            this.queryCount = queryCount;
            this.candidateCount = candidateCount;
            this.evaluatedCount = evaluatedCount;
            this.createdCount = createdCount;
            this.durationMs = durationMs;
        }

        public Status getStatus() { return status; }
        public int getQueryCount() { return queryCount; }
        public int getCandidateCount() { return candidateCount; }
        public int getEvaluatedCount() { return evaluatedCount; }
        public int getCreatedCount() { return createdCount; }
        public long getDurationMs() { return durationMs; }

        @Override
        public String toString() {
            return String.format("AnalysisResult{status=%s, queries=%d, candidates=%d, evaluated=%d, created=%d, duration=%dms}",
                    status, queryCount, candidateCount, evaluatedCount, createdCount, durationMs);
        }
    }

    /**
     * 服务状态
     */
    public static class ServiceStatus {
        public boolean enabled;
        public boolean running;
        public int totalQueries;
        public java.util.Map<String, Integer> datasetStatistics;
    }
}
