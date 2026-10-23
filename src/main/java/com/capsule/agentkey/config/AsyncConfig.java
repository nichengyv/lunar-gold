package com.capsule.agentkey.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("doc-process-");
        //队列满时由调用线程（上传请求的 Tomcat 线程）代为执行，形成自然背压；
        //相比默认 AbortPolicy 直接抛 RejectedExecutionException 打回 500，
        //CallerRunsPolicy 至少保证文档处理任务不丢失，代价是上传请求偶尔变慢，可接受
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        //停机时等待队列任务执行完再关闭，最长 60s；避免发布时正在 Embedding 的文档被腰斩为 ERROR
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * SSE 流式聊天专用线程池。
     *
     * 背景：原先 AgentChatController.streamChat 在 Tomcat 请求线程上同步调用 DeepSeek，
     * 每次流式聊天霸占一个 Tomcat 线程直到流结束（30s+），默认 200 线程并发稍高就耗尽。
     * 改为 SseEmitter 后，Controller 立即返回，实际 LLM 调用在本线程池执行。
     *
     * 线程数估算：单次聊天占用 1 线程，持续 5-60s。20 线程支持约 20 个并发流，
     * 超过后排队 200 个，再满则背压到 Tomcat 线程（此时应扩容或限流）。
     */
    @Bean("agentStreamExecutor")
    public Executor agentStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}