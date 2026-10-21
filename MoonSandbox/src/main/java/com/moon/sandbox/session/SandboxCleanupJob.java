package com.moon.sandbox.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxCleanupJob.class);

    private final SandboxSessionService sessions;

    public SandboxCleanupJob(SandboxSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${moon.sandbox.cleanup-delay:10m}",
            initialDelayString = "${moon.sandbox.cleanup-initial-delay:1m}")
    public void cleanupExpired() {
        int removed = sessions.cleanupExpired();
        if (removed > 0) {
            log.info("Cleaned {} expired sandbox sessions", removed);
        }
        // 活跃会话也要清 tmp/ 与 work/.runner：否则每次 run_python_script 都留下一个
        // UUID 脚本、每次 ffmpeg/PDF 处理都留下临时文件，24h 内会一直累积。
        int transientFiles = sessions.cleanupTransient();
        if (transientFiles > 0) {
            log.info("Cleaned {} transient sandbox files", transientFiles);
        }
    }
}
