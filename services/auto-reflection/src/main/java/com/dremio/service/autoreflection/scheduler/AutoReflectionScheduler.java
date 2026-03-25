package com.dremio.service.autoreflection.scheduler;

import com.dremio.service.autoreflection.AutoReflectionService;
import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 自动反射调度器
 */
public class AutoReflectionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AutoReflectionScheduler.class);

    private final AutoReflectionConfig config;
    private final AutoReflectionService service;
    private ScheduledExecutorService scheduler;

    public AutoReflectionScheduler(AutoReflectionConfig config, AutoReflectionService service) {
        this.config = config;
        this.service = service;
    }

    /**
     * 启动调度器
     */
    public void start() {
        AutoReflectionConfig.TriggerMode triggerMode = config.getTriggerMode();
        
        if (triggerMode == AutoReflectionConfig.TriggerMode.MANUAL) {
            logger.info("Scheduler configured for manual mode, not starting periodic tasks");
            return;
        }

        scheduler = Executors.newScheduledThreadPool(1);
        
        switch (triggerMode) {
            case SCHEDULED:
                startScheduledMode();
                break;
            case THRESHOLD:
                startThresholdMode();
                break;
            default:
                logger.warn("Unknown trigger mode: {}", triggerMode);
                return;
        }

        logger.info("Scheduler started in {} mode", triggerMode);
    }

    /**
     * 定时模式 - 每24小时执行一次
     */
    private void startScheduledMode() {
        scheduler.scheduleAtFixedRate(() -> {
            logger.info("Scheduled analysis triggered");
            try {
                service.runAnalysisCycle();
            } catch (Exception e) {
                logger.error("Scheduled analysis failed", e);
            }
        }, 1, 24, TimeUnit.HOURS);
        
        logger.info("Scheduled mode: running analysis every 24 hours");
    }

    /**
     * 阈值触发模式 - 每5分钟检查一次
     */
    private void startThresholdMode() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int pendingCount = service.getCollector().getTotalQueryCount();
                // 简单实现：每5分钟运行一次分析
                if (pendingCount > 0) {
                    logger.info("Running threshold-based analysis");
                    service.runAnalysisCycle();
                }
            } catch (Exception e) {
                logger.error("Threshold check failed", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
        
        logger.info("Threshold mode: checking every 5 minutes");
    }

    /**
     * 停止调度器
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            logger.info("Stopping scheduler");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("Scheduler stopped");
        }
    }

    /**
     * 手动触发一次调度
     */
    public void triggerNow() {
        logger.info("Manual trigger received");
        service.runAnalysisCycle();
    }
}
