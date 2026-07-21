///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS org.springframework:spring-aop:6.2.8
//DEPS org.springframework:spring-beans:6.2.8
//DEPS org.springframework:spring-core:6.2.8
//DEPS org.springframework:spring-expression:6.2.8
//DEPS org.springframework.retry:spring-retry:2.0.10
//DEPS org.aspectj:aspectjweaver:1.9.22
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1
// 注意：spring-retry 2.0.10 傳遞依賴 spring-* 6.0.23，
// 必須把 spring 全家明式釘回 6.2.8，否則啟動炸 NoSuchMethodError/NoClassDefFoundError

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;

/**
 * @Retryable 四連測：
 * 案例一：抖兩次就好——重試到成功，量測 backoff 間隔（100ms → 200ms）
 * 案例二：一直掛——重試耗盡，@Recover 降級接手
 * 案例三：預設「什麼例外都重試」的陷阱——餘額不足被重試三次；noRetryFor 修正
 * 案例四：self-invocation——proxy 家族病第四次發作（重試消失）
 * 執行：jbang RetryDrill.java
 */
public class RetryDrill {

    static void log(String msg) { System.out.println("  " + msg); }

    static class Db {
        static int fetchCalls, downCalls, chargeCalls, chargeSafeCalls;
        static long lastAttempt;
    }

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            RemoteClient client = ctx.getBean(RemoteClient.class);

            System.out.println("=== 案例一：抖兩次就好——重試到成功＋backoff 節奏 ===");
            log("呼叫端拿到：" + client.fetch());

            System.out.println();
            System.out.println("=== 案例二：一直掛——重試耗盡，@Recover 降級 ===");
            log("呼叫端拿到：" + client.alwaysDown());
            log("方法本體總共執行了 " + Db.downCalls + " 次");

            System.out.println();
            System.out.println("=== 案例三：預設什麼都重試——業務失敗也被重試 ===");
            try {
                client.charge(999);
            } catch (RuntimeException e) {
                log("呼叫端接到：" + e.getClass().getSimpleName()
                        + "（cause: " + e.getCause().getMessage() + "）——扣款方法已執行 " + Db.chargeCalls + " 次！");
            }
            try {
                client.chargeSafe(999);
            } catch (RuntimeException e) {
                log("noRetryFor 版接到：" + e.getClass().getSimpleName()
                        + "（" + e.getMessage() + "）——方法只執行 " + Db.chargeSafeCalls + " 次");
            }

            System.out.println();
            System.out.println("=== 案例四：self-invocation——重試無聲消失 ===");
            Db.fetchCalls = 0; Db.lastAttempt = 0;
            try {
                client.callFetchInternally();
            } catch (IllegalStateException e) {
                log("第一次失敗就直接炸出：" + e.getMessage() + "（fetch 只執行了 " + Db.fetchCalls + " 次，沒有重試）");
            }
        }
    }

    static class InsufficientBalanceException extends RuntimeException {
        InsufficientBalanceException(String msg) { super(msg); }
    }

    static class RemoteClient {

        @Retryable(maxAttempts = 4, backoff = @Backoff(delay = 100, multiplier = 2.0))
        String fetch() {
            long now = System.currentTimeMillis();
            Db.fetchCalls++;
            log("fetch 第 " + Db.fetchCalls + " 次嘗試（距上次 "
                    + (Db.lastAttempt == 0 ? "-" : (now - Db.lastAttempt) + " ms") + "）");
            Db.lastAttempt = now;
            if (Db.fetchCalls < 3) throw new IllegalStateException("網路抖動");
            return "資料到手（第 " + Db.fetchCalls + " 次成功）";
        }

        @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 50))
        String alwaysDown() {
            Db.downCalls++;
            log("alwaysDown 第 " + Db.downCalls + " 次嘗試");
            throw new IllegalStateException("下游服務掛了");
        }
        @Recover
        String downFallback(IllegalStateException e) {   // 簽名規則：例外在前、參數與回傳型別對齊
            return "降級回應（@Recover 接手：" + e.getMessage() + "）";
        }

        @Retryable(backoff = @Backoff(delay = 50))       // ❌ 預設：所有例外都重試
        void charge(long amount) {
            Db.chargeCalls++;
            log("charge 執行第 " + Db.chargeCalls + " 次");
            throw new InsufficientBalanceException("餘額不足");
        }

        @Retryable(noRetryFor = InsufficientBalanceException.class, backoff = @Backoff(delay = 50))
        void chargeSafe(long amount) {                   // ✅ 業務失敗不重試
            Db.chargeSafeCalls++;
            log("chargeSafe 執行第 " + Db.chargeSafeCalls + " 次");
            throw new InsufficientBalanceException("餘額不足");
        }

        void callFetchInternally() {
            log("內部方法直接呼叫 this.fetch()…");
            fetch();                                     // ❌ 走 this 不走 proxy——@Retryable 失效
        }
    }

    @EnableRetry
    @Configuration
    static class Cfg {
        @Bean RemoteClient remoteClient() { return new RemoteClient(); }
    }
}
