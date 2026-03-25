package com.dremio.service.autoreflection.hook;

import com.dremio.exec.planner.observer.AbstractAttemptObserver;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.work.protector.UserRequest;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.service.autoreflection.AutoReflectionService;
import com.dremio.service.autoreflection.model.QueryRecord;
import com.dremio.service.autoreflection.util.SqlAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dremio QueryObserver Hook - 集成到Dremio的查询观察者
 * 
 * 这个类实现了 Dremio 的 Query Observer 模式，
 * 用于拦截查询并将其传递给 AutoReflectionService 进行分析。
 */
public class AutoReflectionQueryObserver extends AbstractAttemptObserver {

    private static final Logger logger = LoggerFactory.getLogger(AutoReflectionQueryObserver.class);

    private final AutoReflectionService service;
    private final ThreadLocal<QueryRecord> currentQuery = new ThreadLocal<>();
    private final ThreadLocal<Long> queryStartTime = new ThreadLocal<>();

    public AutoReflectionQueryObserver(AutoReflectionService service) {
        this.service = service;
    }

    @Override
    public void queryStarted(UserRequest query, String user) {
        try {
            String sql = extractSql(query);
            String queryId = generateQueryId(query);
            
            QueryRecord record = SqlAnalyzer.extractQueryInfo(queryId, sql, user);
            record.setStartTime(System.currentTimeMillis());
            
            currentQuery.set(record);
            queryStartTime.set(System.currentTimeMillis());
            
            logger.debug("Query started: {}", queryId);
        } catch (Exception e) {
            logger.warn("Failed to record query start", e);
        }
    }

    @Override
    public void execStarted(QueryProfile profile) {
        QueryRecord record = currentQuery.get();
        if (record == null) {
            return;
        }

        try {
            if (profile != null && profile.getStats() != null) {
                record.setRowsScanned(profile.getStats().getMaxPeakMemory());
            }
            if (profile != null) {
                record.setUser(profile.getUser());
            }
            
            logger.debug("Query exec started: {}", record.getQueryId());
        } catch (Exception e) {
            logger.warn("Failed to update query profile", e);
        }
    }

    @Override
    public void attemptCompletion(UserResult result) {
        QueryRecord record = currentQuery.get();
        if (record == null) {
            return;
        }

        try {
            Long startTime = queryStartTime.get();
            if (startTime != null) {
                record.setDuration(System.currentTimeMillis() - startTime);
            }

            if (result != null && result.getProfile() != null) {
                QueryProfile profile = result.getProfile();
                
                if (profile.getStats() != null) {
                    record.setRowsScanned(profile.getStats().getTotalPeakMemory());
                    record.setRowsReturned(profile.getStats().getOutputRecords());
                }
                
                // 检查是否被反射加速
                if (profile.getAccelerationProfile() != null) {
                    record.setAccelerated(true);
                }
            }

            // 记录完成
            service.recordQueryCompletion(record);
            
            logger.debug("Query completed: {}, duration: {}ms, accelerated: {}", 
                    record.getQueryId(), record.getDuration(), record.isAccelerated());
            
        } catch (Exception e) {
            logger.error("Failed to record query completion", e);
        } finally {
            currentQuery.remove();
            queryStartTime.remove();
        }
    }

    /**
     * 从UserRequest中提取SQL
     */
    private String extractSql(UserRequest request) {
        if (request == null) {
            return "";
        }
        
        try {
            if (request.getSession() != null && request.getSession().getActiveQuery() != null) {
                return request.getSession().getActiveQuery();
            }
            
            if (request.getPayload() != null) {
                return request.getPayload().toString();
            }
        } catch (Exception e) {
            logger.debug("Failed to extract SQL from request", e);
        }
        
        return "";
    }

    /**
     * 生成查询ID
     */
    private String generateQueryId(UserRequest request) {
        return String.format("q_%d_%d", System.currentTimeMillis(), 
                Thread.currentThread().getId());
    }

    @Override
    public void queryClosed() {
        super.queryClosed();
    }

    @Override
    public void execCompletion(UserResult result) {
        attemptCompletion(result);
    }
}
