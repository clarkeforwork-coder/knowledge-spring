///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * @Async 解剖四連測：
 * 案例一：誰在跑——沒配 executor（每任務開新 thread）vs 配了 ThreadPoolTaskExecutor（重用）
 * 案例二：void 方法的例外去哪了——caller 接不到，只有 AsyncUncaughtExceptionHandler 收屍
 * 案例三：回傳值的真相——@Async String 拿到 null；CompletableFuture 才能把例外帶回來
 * 案例四：self-invocation——同類內呼叫不過 proxy，@Async 無聲失效
 * 執行：jbang AsyncAnatomy.java
 */
public class AsyncAnatomy {

    static void log(String msg) {
        System.out.printf("  [%s] %s%n", Thread.currentThread().getName(), msg);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 案例一a：沒配 executor——每個任務都是全新的 thread ===");
        try (var ctx = new AnnotationConfigApplicationContext(DefaultConfig.class)) {
            Worker w = ctx.getBean(Worker.class);
            for (int i = 1; i <= 3; i++) { w.whoRuns(i); Thread.sleep(80); }
            Thread.sleep(200);

            System.out.println();
            System.out.println("=== 案例二a：void 例外——預設的收屍人 ===");
            try {
                w.voidBoom();
                log("voidBoom() 呼叫端：沒接到任何例外，繼續往下走");
            } catch (RuntimeException e) {
                log("（不會走到這行）");
            }
            Thread.sleep(300);
        }

        System.out.println();
        System.out.println("=== 案例一b＋二b：配了 ThreadPoolTaskExecutor 與自訂例外處理 ===");
        try (var ctx = new AnnotationConfigApplicationContext(PoolConfig.class)) {
            Worker w = ctx.getBean(Worker.class);
            for (int i = 1; i <= 3; i++) { w.whoRuns(i); Thread.sleep(80); }
            Thread.sleep(200);
            w.voidBoom();
            Thread.sleep(300);

            System.out.println();
            System.out.println("=== 案例三：回傳值的真相 ===");
            try {
                w.badReturn();
            } catch (IllegalArgumentException e) {
                log("@Async String badReturn()：呼叫當下炸出 " + e.getMessage());
            }
            log("CompletableFuture 正常路徑：" + w.compute().join());
            try {
                w.computeBoom().join();
            } catch (Exception e) {
                log("CompletableFuture 例外路徑：join() 丟出 " + e.getClass().getSimpleName()
                        + "（cause: " + e.getCause().getMessage() + "）");
            }

            System.out.println();
            System.out.println("=== 案例四：self-invocation——同類內呼叫不過 proxy ===");
            w.outer();
            Thread.sleep(200);
        }
    }

    static class Worker {
        @Async void whoRuns(int no) { log("任務 " + no + " 執行中"); }

        @Async void voidBoom() { throw new IllegalStateException("void 方法裡的例外"); }

        @Async String badReturn() { return "你永遠拿不到這個字串"; }   // 啟動不炸，「呼叫當下」才炸

        @Async CompletableFuture<String> compute() {
            return CompletableFuture.completedFuture("算好了（by " + Thread.currentThread().getName() + "）");
        }

        @Async CompletableFuture<String> computeBoom() {
            throw new IllegalStateException("計算失敗");
        }

        void outer() {
            log("outer() 開始，呼叫 this.inner()…");
            inner();   // ❌ 走的是 this，不是 proxy——@Async 無聲失效
        }
        @Async void inner() { log("inner() 執行中（如果有換 thread 才算非同步）"); }
    }

    @EnableAsync
    @Configuration
    static class DefaultConfig {
        @Bean Worker worker() { return new Worker(); }
    }

    @EnableAsync
    @Configuration
    static class PoolConfig implements AsyncConfigurer {
        @Bean Worker worker() { return new Worker(); }

        @Override public Executor getAsyncExecutor() {
            var pool = new ThreadPoolTaskExecutor();
            pool.setCorePoolSize(2);
            pool.setMaxPoolSize(2);
            pool.setThreadNamePrefix("async-");
            pool.setDaemon(true);   // 這裡的 pool 不是 bean，容器不會幫它 shutdown——demo 用 daemon 讓 JVM 能退出
            pool.initialize();
            return pool;
        }
        @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (ex, method, params) ->
                    log("自訂收屍人：" + method.getName() + "() 丟出「" + ex.getMessage() + "」");
        }
    }
}
