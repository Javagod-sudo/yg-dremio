package com.dremio.service.autoreflection.recommender;

import com.dremio.service.autoreflection.config.AutoReflectionConfig;
import com.dremio.service.autoreflection.model.ReflectionCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 反射推荐器 - 评估和筛选候选反射
 */
public class ReflectionRecommender {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionRecommender.class);

    private final AutoReflectionConfig config;
    
    // 已有的反射配置哈希集合
    private Set<String> existingReflectionHashes = new HashSet<>();

    public ReflectionRecommender(AutoReflectionConfig config) {
        this.config = config;
    }

    /**
     * 评估候选反射列表
     */
    public List<ReflectionCandidate> evaluateCandidates(List<ReflectionCandidate> candidates) {
        logger.info("Evaluating {} candidates", candidates.size());
        
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 1. 过滤已有反射
        List<ReflectionCandidate> newCandidates = filterExisting(candidates);
        logger.info("After filtering existing: {} candidates", newCandidates.size());
        
        // 2. 评估每个候选
        for (ReflectionCandidate candidate : newCandidates) {
            evaluateCandidate(candidate);
        }
        
        // 3. 按综合得分排序
        newCandidates.sort((c1, c2) -> 
                Double.compare(c2.getCompositeScore(), c1.getCompositeScore()));
        
        // 4. 应用存储预算限制
        applyStorageBudget(newCandidates);
        
        // 5. 自动批准高置信度候选
        autoApprove(newCandidates);

        logger.info("Generated {} evaluated candidates", newCandidates.size());
        return newCandidates;
    }

    /**
     * 评估单个候选
     */
    private void evaluateCandidate(ReflectionCandidate candidate) {
        // 计算优先级得分已经在 calculateBenefitScores 中完成了
        // 这里可以做额外的评估逻辑
        
        // 生成反射名称hash
        if (candidate.getCandidateHash() == null) {
            candidate.setCandidateHash(UUID.randomUUID().toString());
        }
    }

    /**
     * 过滤已存在的反射
     */
    private List<ReflectionCandidate> filterExisting(List<ReflectionCandidate> candidates) {
        return candidates.stream()
                .filter(c -> !existingReflectionHashes.contains(c.getCandidateHash()))
                .collect(Collectors.toList());
    }

    /**
     * 应用存储预算限制
     */
    private void applyStorageBudget(List<ReflectionCandidate> candidates) {
        double totalStorage = 0;
        double budget = config.getStorageBudgetGb() * 1024;  // 转换为MB
        
        ListIterator<ReflectionCandidate> iter = candidates.listIterator();
        while (iter.hasNext()) {
            ReflectionCandidate candidate = iter.next();
            double candidateStorage = candidate.getEstimatedStorageCost();
            
            // 如果超过预算，标记为拒绝
            if (totalStorage + candidateStorage > budget) {
                candidate.setStatus(ReflectionCandidate.CandidateStatus.REJECTED);
                candidate.setReason(ReflectionCandidate.RecommendationReason.SLOW_QUERY);
                logger.debug("Candidate {} rejected due to storage budget", candidate.getCandidateId());
            } else {
                totalStorage += candidateStorage;
            }
        }
        
        logger.info("Storage budget: {:.2f}MB used out of {:.2f}MB", totalStorage, budget);
    }

    /**
     * 自动批准高置信度候选
     */
    private void autoApprove(List<ReflectionCandidate> candidates) {
        if (!config.isAutoApproveEnabled()) {
            logger.info("Auto-approve is disabled");
            return;
        }

        double threshold = config.getHighConfidenceThreshold();
        int approvedCount = 0;
        
        for (ReflectionCandidate candidate : candidates) {
            if (candidate.getStatus() != ReflectionCandidate.CandidateStatus.PENDING) {
                continue;
            }
            
            // 收益超过阈值且置信度足够高
            if (candidate.getCompositeScore() > config.getBenefitThreshold() &&
                    candidate.getConfidenceScore() >= threshold) {
                candidate.setStatus(ReflectionCandidate.CandidateStatus.APPROVED);
                approvedCount++;
                logger.info("Auto-approved candidate: {} (score: {}, confidence: {})", 
                        candidate.getCandidateId(), 
                        candidate.getCompositeScore(),
                        candidate.getConfidenceScore());
            }
        }
        
        logger.info("Auto-approved {} candidates", approvedCount);
    }

    /**
     * 更新已有的反射哈希集合
     */
    public void updateExistingReflections(Set<String> hashes) {
        this.existingReflectionHashes = hashes;
        logger.info("Updated existing reflection hashes, count: {}", hashes.size());
    }

    /**
     * 检查是否应该创建反射
     */
    public boolean shouldCreateReflection(ReflectionCandidate candidate) {
        double benefitThreshold = config.getBenefitThreshold();
        return candidate.getCompositeScore() > benefitThreshold &&
               candidate.getStatus() == ReflectionCandidate.CandidateStatus.APPROVED;
    }

    /**
     * 获取推荐创建的候选列表
     */
    public List<ReflectionCandidate> getCandidatesToCreate(List<ReflectionCandidate> candidates) {
        return candidates.stream()
                .filter(this::shouldCreateReflection)
                .limit(5)  // 每次最多创建5个
                .collect(Collectors.toList());
    }
}
