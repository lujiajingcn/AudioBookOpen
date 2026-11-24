package com.audiobook.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 同时合成的任务数。TTS 是重负载，默认 2；机器/显卡撑得住可以调大。
     */
    @Value("${app.tts.concurrency:2}")
    private int concurrency;

    /**
     * 排队等待的任务数上限，超出后拒绝入队（AbortPolicy + Controller 前置容量校验返回 429）
     */
    @Value("${app.tts.queue-capacity:200}")
    private int queueCapacity;

    @Bean("ttsTaskExecutor")
    public Executor ttsTaskExecutor() {
        log.info("========== 初始化 TTS 异步线程池 ==========");

        int poolSize = Math.max(1, concurrency);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 注意：ThreadPoolExecutor 只有在队列塞满后才会把线程数从 core 扩到 max。
        // 队列容量有 200，max 永远不可能被触发，所以这里让 core == max，
        // 语义就是「并发 N 个任务，其余排队」，避免出现误以为能跑 5 个的错觉。
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("TTS-Async-");
        // AbortPolicy：队列满时快速失败。绝不能用 CallerRunsPolicy——那会让 HTTP 线程
        // 同步执行整个合成任务（一本书可达数小时），请求挂死、超时、连接池耗尽。
        // 前置防线：Controller 在建任务前校验未完成任务数并返回 429；
        // 兜底防线：dispatchAfterCommit 捕获 TaskRejectedException 并把任务标记为 failed。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时等待已提交的任务跑完，避免重启把进行中的合成直接截断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("✅ TTS 异步线程池初始化完成");
        log.info("   并发任务数: {}", poolSize);
        log.info("   队列容量: {}", queueCapacity);

        return executor;
    }
}
