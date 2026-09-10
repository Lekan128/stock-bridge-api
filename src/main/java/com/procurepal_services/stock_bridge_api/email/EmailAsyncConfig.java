package com.procurepal_services.stock_bridge_api.email;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool {@link EmailSender#sendAsync} runs on.
 *
 * <h2>Why a dedicated executor and not Spring's default</h2>
 * {@code @Async} with no qualifier resolves to whatever single {@code Executor}
 * bean the context happens to expose, which makes email's throughput hostage to a
 * pool it does not own - and makes a future second async feature silently share
 * (and be blocked by) SES latency. Naming it keeps the blast radius of a slow or
 * throttled SES to this pool.
 *
 * <h2>Sizing</h2>
 * Small on purpose. SES is fast and this is a low-volume transactional load - a few
 * emails per order, not a campaign - so the pool exists to get work off the request
 * thread, not to parallelise. A larger pool would mostly buy a faster route to SES's
 * per-second send quota, which is the one AWS-side limit an application can trip by
 * being enthusiastic.
 *
 * <h2>What happens when the queue fills</h2>
 * Emails are dropped, with a log line, and that is the deliberate choice. The two
 * alternatives are worse: {@code AbortPolicy} throws
 * {@code RejectedExecutionException} back into an {@code afterCommit} callback, and
 * {@code CallerRunsPolicy} runs the SES call on the request thread - which is
 * exactly the latency this pool exists to avoid, applied at the precise moment the
 * system is already struggling. A queue this size only fills if SES is unreachable,
 * in which case the queued messages were not going to be delivered anyway.
 */
@Configuration
@EnableAsync
@Slf4j
public class EmailAsyncConfig {

    @Bean("emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("Email queue is full ({} queued) - dropping an outgoing email. This means SES is "
                        + "unreachable or far slower than usual.", pool.getQueue().size()));
        // Let the JVM stop even with mail in flight, but give an in-progress send a
        // moment to finish rather than killing the thread mid-request to SES. Ten
        // seconds is well under the shutdown grace period any of this project's
        // deploy targets allow, so it cannot turn a rolling restart into a hang.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
