package com.dremio.service.autoreflection.executor;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import com.dremio.service.autoreflection.model.ReflectionCandidate;
import com.dremio.service.reflection.ReflectionAdministrationService;
import com.dremio.service.reflection.ReflectionService;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionType;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 反射执行器 - 调用Dremio API创建反射
 */
public class ReflectionExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionExecutor.class);

    private final AutoReflectionConfig config;
    private final ReflectionAdministrationService reflectionAdminService;
    
    // 创建结果记录
    private final List<CreationResult> creationResults = new ArrayList<>();

    public ReflectionExecutor(AutoReflectionConfig config, ReflectionAdministrationService reflectionAdminService) {
        this.config = config;
        this.reflectionAdminService = reflectionAdminService;
    }

    /**
     * 执行反射创建
     */
    public List<CreationResult> execute(List<ReflectionCandidate> candidates) {
        creationResults.clear();
        
        int maxCreations = 5; // 每次最多创建5个反射
        int created = 0;

        logger.info("Starting execution of {} candidates", candidates.size());

        for (ReflectionCandidate candidate : candidates) {
            if (created >= maxCreations) {
                logger.info("Reached max creations limit: {}", maxCreations);
                break;
            }

            if (!shouldCreate(candidate)) {
                continue;
            }

            try {
                CreationResult result = createReflection(candidate);
                creationResults.add(result);
                
                if (result.isSuccess()) {
                    created++;
                    logger.info("Successfully created reflection: {}", result.getReflectionId());
                } else {
                    logger.warn("Failed to create reflection: {}", result.getErrorMessage());
                }
            } catch (Exception e) {
                logger.error("Exception creating reflection: {}", candidate.getCandidateId(), e);
                creationResults.add(CreationResult.failure(candidate.getCandidateId(), e.getMessage()));
            }
        }

        logger.info("Execution complete. Created: {}, Failed: {}", 
                creationResults.stream().filter(CreationResult::isSuccess).count(),
                creationResults.stream().filter(r -> !r.isSuccess()).count()));

        return creationResults;
    }

    /**
     * 判断是否应该创建
     */
    private boolean shouldCreate(ReflectionCandidate candidate) {
        // 必须是已批准状态
        if (candidate.getStatus() != ReflectionCandidate.CandidateStatus.APPROVED) {
            return false;
        }
        
        // 收益必须超过阈值
        return candidate.getCompositeScore() > config.getBenefitThreshold();
    }

    /**
     * 创建反射
     */
    private CreationResult createReflection(ReflectionCandidate candidate) {
        logger.info("Creating reflection for dataset: {}", candidate.getDatasetPath());

        try {
            // 构建反射目标
            ReflectionGoal.Builder goalBuilder = ReflectionGoal.newBuilder();
            
            // 设置数据集路径
            if (candidate.getDatasetPath() != null) {
                List<String> pathParts = Arrays.asList(candidate.getDatasetPath().split("\\."));
                goalBuilder.setDatasetPath(CatalogEntityKey.fromPathParts(pathParts).toNamespaceKey());
            }
            
            // 设置反射类型
            ReflectionType reflectionType = mapToReflectionType(candidate.getAggregationType());
            goalBuilder.setType(reflectionType);
            
            // 设置名称
            String name = generateReflectionName(candidate);
            goalBuilder.setName(name);
            
            // 设置详细信息
            com.dremio.service.reflection.proto.ReflectionDetails details = 
                    buildReflectionDetails(candidate);
            goalBuilder.setDetails(details);

            // 创建反射
            ReflectionId reflectionId = reflectionAdminService.create(goalBuilder.build());
            
            logger.info("Created reflection with ID: {} for dataset: {}", 
                    reflectionId.getId(), candidate.getDatasetPath());
            
            return CreationResult.success(reflectionId.getId(), name);

        } catch (Exception e) {
            logger.error("Failed to create reflection for dataset: {}", 
                    candidate.getDatasetPath(), e);
            return CreationResult.failure(candidate.getCandidateId(), e.getMessage());
        }
    }

    /**
     * 构建反射详细信息
     */
    private com.dremio.service.reflection.proto.ReflectionDetails buildReflectionDetails(
            ReflectionCandidate candidate) {
        
        com.dremio.service.reflection.proto.ReflectionDetails.Builder detailsBuilder = 
                com.dremio.service.reflection.proto.ReflectionDetails.newBuilder();
        
        // 设置展示字段 (display fields)
        if (candidate.getDisplayFields() != null) {
            for (String field : candidate.getDisplayFields()) {
                detailsBuilder.addDisplayFields(
                        com.dremio.service.reflection.proto.ReflectionField.newBuilder()
                                .setName(field)
                                .build());
            }
        }
        
        // 设置分区字段
        if (candidate.getPartitionFields() != null) {
            for (String field : candidate.getPartitionFields()) {
                detailsBuilder.addPartitionFields(
                        com.dremio.service.reflection.proto.ReflectionField.newBuilder()
                                .setName(field)
                                .build());
            }
        }
        
        // 设置排序字段
        if (candidate.getOrderByFields() != null) {
            for (String field : candidate.getOrderByFields()) {
                detailsBuilder.addSortFields(
                        com.dremio.service.reflection.proto.ReflectionField.newBuilder()
                                .setName(field)
                                .build());
            }
        }
        
        // 设置维度字段 (用于聚合反射)
        if (candidate.getGroupByFields() != null) {
            for (String field : candidate.getGroupByFields()) {
                detailsBuilder.addDimensionFields(
                        com.dremio.service.reflection.proto.ReflectionDimensionField.newBuilder()
                                .setName(field)
                                .build());
            }
        }
        
        return detailsBuilder.build();
    }

    /**
     * 映射聚合类型到反射类型
     */
    private ReflectionType mapToReflectionType(ReflectionCandidate.AggregationType type) {
        switch (type) {
            case GROUP_BY:
            case AGGREGATE:
                return ReflectionType.AGGREGATION;
            case JOIN:
            case MATERIALIZED_VIEW:
                return ReflectionType.RAW;
            case NONE:
            default:
                return ReflectionType.RAW;
        }
    }

    /**
     * 生成反射名称
     */
    private String generateReflectionName(ReflectionCandidate candidate) {
        StringBuilder sb = new StringBuilder("auto_");
        
        // 表名
        if (candidate.getDatasetPath() != null) {
            sb.append(candidate.getDatasetPath().replace(".", "_"));
        }
        
        // 类型
        switch (candidate.getAggregationType()) {
            case GROUP_BY:
                sb.append("_grpby");
                break;
            case JOIN:
                sb.append("_join");
                break;
            case MATERIALIZED_VIEW:
                sb.append("_mv");
                break;
            default:
                sb.append("_raw");
        }
        
        // 字段hash
        if (candidate.getGroupByFields() != null && !candidate.getGroupByFields().isEmpty()) {
            sb.append("_").append(candidate.getGroupByFields().hashCode() & 0xffff);
        }
        
        sb.append("_").append(System.currentTimeMillis() % 10000);
        
        return sb.toString();
    }

    /**
     * 获取创建结果
     */
    public List<CreationResult> getCreationResults() {
        return Collections.unmodifiableList(creationResults);
    }

    /**
     * 创建结果封装
     */
    public static class CreationResult {
        private final String reflectionId;
        private final String reflectionName;
        private final boolean success;
        private final String errorMessage;
        private final long timestamp;

        private CreationResult(String reflectionId, String reflectionName, 
                              boolean success, String errorMessage) {
            this.reflectionId = reflectionId;
            this.reflectionName = reflectionName;
            this.success = success;
            this.errorMessage = errorMessage;
            this.timestamp = System.currentTimeMillis();
        }

        public static CreationResult success(String reflectionId, String name) {
            return new CreationResult(reflectionId, name, true, null);
        }

        public static CreationResult failure(String name, String error) {
            return new CreationResult(null, name, false, error);
        }

        public String getReflectionId() { return reflectionId; }
        public String getReflectionName() { return reflectionName; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return "CreationResult{" +
                    "reflectionId='" + reflectionId + '\'' +
                    ", reflectionName='" + reflectionName + '\'' +
                    ", success=" + success +
                    ", errorMessage='" + errorMessage + '\'' +
                    '}';
        }
    }
}
