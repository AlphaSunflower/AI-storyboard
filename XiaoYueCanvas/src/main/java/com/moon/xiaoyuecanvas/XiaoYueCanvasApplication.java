package com.moon.xiaoyuecanvas;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.AiConfigProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Morrow 设计助理作业执行服务（XiaoYueCanvas job-service）。
 *
 * <p>复用 agent-core（com.moon.moonagent）的：数据层（实体/mapper）、AI 生成服务（Image/Video/Refine/VideoPlan）、
 * AiConfigProperties（网关/上传/超时配置）。token/能力分发与 Morrow 作业契约在本服务实现。
 *
 * <p>Spring 扫描范围：默认扫描本包 {@code com.moon.xiaoyuecanvas}；agent-core 的 {@code com.moon.moonagent.ai.*}
 * 服务通过 Maven 依赖 + 其注解（{@code @Service}）在类路径上被 Spring 扫描到——需确保主类能扫描到 agent-core 的包。
 */
@SpringBootApplication(scanBasePackages = {"com.moon.xiaoyuecanvas", "com.moon.moonagent", "com.storyboard.common"})
@MapperScan({"com.moon.moonagent.mapper", "com.moon.xiaoyuecanvas.mapper"})
@EnableConfigurationProperties({AiConfigProperties.class, AiConfigProperties.Gateway.class, AgentAiConfigProperties.class})
public class XiaoYueCanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaoYueCanvasApplication.class, args);
    }
}
