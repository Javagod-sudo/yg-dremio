package com.dremio.service.autoreflection.provider;

import com.dremio.options.OptionManager;
import com.dremio.service.autoreflection.AutoReflectionService;
import com.dremio.service.reflection.ReflectionAdministrationService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自动反射服务提供者
 * 
 * 这是一个 Guice Provider，用于创建和提供 AutoReflectionService 实例。
 * Dremio 使用 Guice 进行依赖注入。
 */
public class AutoReflectionServiceProvider {

    private static final Logger logger = LoggerFactory.getLogger(AutoReflectionServiceProvider.class);

    private final OptionManager optionManager;
    private final ReflectionAdministrationService reflectionAdminService;
    
    private AutoReflectionService service;

    @Inject
    public AutoReflectionServiceProvider(
            OptionManager optionManager,
            ReflectionAdministrationService reflectionAdminService) {
        this.optionManager = optionManager;
        this.reflectionAdminService = reflectionAdminService;
    }

    /**
     * 获取或创建 AutoReflectionService 实例
     */
    public AutoReflectionService get() {
        if (service == null) {
            logger.info("Creating new AutoReflectionService instance");
            service = new AutoReflectionService(
                    new com.dremio.service.autoreflection.config.AutoReflectionConfig(optionManager),
                    reflectionAdminService);
        }
        return service;
    }

    /**
     * 启动服务
     */
    public void start() {
        get().start();
    }

    /**
     * 停止服务
     */
    public void close() {
        if (service != null) {
            service.stop();
        }
    }
}
