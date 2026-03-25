package com.dremio.service.autoreflection.config;

import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators;
import com.dremio.service.autoreflection.AutoReflectionService;

/**
 * 自动反射系统配置
 * 
 * 使用 Dremio 的选项系统进行配置管理
 */
public class AutoReflectionConfig {

    public static final String AUTO_REFLECTION_ENABLED = "auto.reflection.enabled";
    public static final String MIN_QUERY_FREQUENCY = "auto.reflection.collector.min_query_frequency";
    public static final String MIN_EXECUTION_TIME_MS = "auto.reflection.collector.min_execution_time_ms";
    public static final String HISTORY_RETENTION_DAYS = "auto.reflection.collector.history_retention_days";
    public static final String AGGREGATION_WINDOW_DAYS = "auto.reflection.analyzer.aggregation_window_days";
    public static final String MIN_CANDIDATE_QUERIES = "auto.reflection.analyzer.min_candidate_queries";
    public static final String BENEFIT_THRESHOLD = "auto.reflection.recommender.benefit_threshold";
    public static final String STORAGE_BUDGET_GB = "auto.reflection.recommender.storage_budget_gb";
    public static final String AUTO_APPROVE_ENABLED = "auto.reflection.recommender.auto_approve";
    public static final String HIGH_CONFIDENCE_THRESHOLD = "auto.reflection.recommender.high_confidence_threshold";
    public static final String TRIGGER_MODE = "auto.reflection.scheduler.trigger_mode";
    public static final String SCHEDULE_CRON = "auto.reflection.scheduler.cron";

    private final OptionManager optionManager;

    public AutoReflectionConfig(OptionManager optionManager) {
        this.optionManager = optionManager;
    }

    /**
     * 检查是否启用自动反射
     */
    public boolean isEnabled() {
        return optionManager.getOption(new TypeValidators.BooleanValidator(AUTO_REFLECTION_ENABLED, true));
    }

    public void setEnabled(boolean enabled) {
        optionManager.setOption(AUTO_REFLECTION_ENABLED, enabled);
    }

    /**
     * 最小查询频率 - 最少出现次数才记录
     */
    public int getMinQueryFrequency() {
        return optionManager.getOption(new TypeValidators.IntValidator(MIN_QUERY_FREQUENCY, 3));
    }

    public void setMinQueryFrequency(int frequency) {
        optionManager.setOption(MIN_QUERY_FREQUENCY, frequency);
    }

    /**
     * 最小执行时间阈值(ms) - 只记录超过此时间的查询
     */
    public long getMinExecutionTimeMs() {
        return optionManager.getOption(new TypeValidators.LongValidator(MIN_EXECUTION_TIME_MS, 1000L));
    }

    public void setMinExecutionTimeMs(long timeMs) {
        optionManager.setOption(MIN_EXECUTION_TIME_MS, timeMs);
    }

    /**
     * 历史保留天数
     */
    public int getHistoryRetentionDays() {
        return optionManager.getOption(new TypeValidators.IntValidator(HISTORY_RETENTION_DAYS, 30));
    }

    public void setHistoryRetentionDays(int days) {
        optionManager.setOption(HISTORY_RETENTION_DAYS, days);
    }

    /**
     * 分析窗口天数 - 分析最近多少天的数据
     */
    public int getAggregationWindowDays() {
        return optionManager.getOption(new TypeValidators.IntValidator(AGGREGATION_WINDOW_DAYS, 7));
    }

    public void setAggregationWindowDays(int days) {
        optionManager.setOption(AGGREGATION_WINDOW_DAYS, days);
    }

    /**
     * 最少涉及查询数 - 最少涉及多少条查询才生成候选
     */
    public int getMinCandidateQueries() {
        return optionManager.getOption(new TypeValidators.IntValidator(MIN_CANDIDATE_QUERIES, 3));
    }

    public void setMinCandidateQueries(int count) {
        optionManager.setOption(MIN_CANDIDATE_QUERIES, count);
    }

    /**
     * 收益阈值 - 超过此阈值才创建反射
     */
    public double getBenefitThreshold() {
        return optionManager.getOption(new TypeValidators.DoubleValidator(BENEFIT_THRESHOLD, 70.0));
    }

    public void setBenefitThreshold(double threshold) {
        optionManager.setOption(BENEFIT_THRESHOLD, threshold);
    }

    /**
     * 存储预算(GB)
     */
    public double getStorageBudgetGb() {
        return optionManager.getOption(new TypeValidators.DoubleValidator(STORAGE_BUDGET_GB, 10.0));
    }

    public void setStorageBudgetGb(double budgetGb) {
        optionManager.setOption(STORAGE_BUDGET_GB, budgetGb);
    }

    /**
     * 是否启用高置信度自动批准
     */
    public boolean isAutoApproveEnabled() {
        return optionManager.getOption(new TypeValidators.BooleanValidator(AUTO_APPROVE_ENABLED, true));
    }

    public void setAutoApproveEnabled(boolean enabled) {
        optionManager.setOption(AUTO_APPROVE_ENABLED, enabled);
    }

    /**
     * 高置信度阈值
     */
    public double getHighConfidenceThreshold() {
        return optionManager.getOption(new TypeValidators.DoubleValidator(HIGH_CONFIDENCE_THRESHOLD, 0.9));
    }

    public void setHighConfidenceThreshold(double threshold) {
        optionManager.setOption(HIGH_CONFIDENCE_THRESHOLD, threshold);
    }

    /**
     * 触发模式: SCHEDULED, THRESHOLD, MANUAL
     */
    public TriggerMode getTriggerMode() {
        String mode = optionManager.getOption(new TypeValidators.StringValidator(TRIGGER_MODE, "SCHEDULED"));
        return TriggerMode.valueOf(mode);
    }

    public void setTriggerMode(TriggerMode mode) {
        optionManager.setOption(TRIGGER_MODE, mode.name());
    }

    /**
     * Cron 表达式
     */
    public String getScheduleCron() {
        return optionManager.getOption(new TypeValidators.StringValidator(SCHEDULE_CRON, "0 0 2 * * ?"));
    }

    public void setScheduleCron(String cron) {
        optionManager.setOption(SCHEDULE_CRON, cron);
    }

    public enum TriggerMode {
        SCHEDULED,   // 定时
        THRESHOLD,   // 阈值触发
        MANUAL       // 手动
    }

    @Override
    public String toString() {
        return "AutoReflectionConfig{" +
                "enabled=" + isEnabled() +
                ", minQueryFrequency=" + getMinQueryFrequency() +
                ", minExecutionTimeMs=" + getMinExecutionTimeMs() +
                ", benefitThreshold=" + getBenefitThreshold() +
                ", storageBudgetGb=" + getStorageBudgetGb() +
                ", triggerMode=" + getTriggerMode() +
                '}';
    }
}
