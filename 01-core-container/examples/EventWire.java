///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 事件機制三連測：
 * 案例一：事件預設是「同步」的——同一條 thread、@Order 排隊、例外炸回發佈者
 * 案例二：condition 條件監聽（SpEL）
 * 案例三：@Async 之後才是非同步——主流程立刻返回
 * 執行：jbang EventWire.java
 */
public class EventWire {

    static void log(String msg) {
        System.out.printf("  [%s] %s%n", Thread.currentThread().getName(), msg);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 案例一：事件預設是同步的 ===");
        try (var ctx = new AnnotationConfigApplicationContext(SyncConfig.class)) {
            Checkout checkout = ctx.getBean(Checkout.class);
            checkout.place("A001", 500);

            System.out.println();
            System.out.println("=== 案例一之二：listener 的例外會炸回發佈者 ===");
            try {
                checkout.place("B002", 7000);
            } catch (RuntimeException e) {
                log("place() 的呼叫端接到例外：" + e.getMessage() + "——後面的 listener 沒跑");
            }

            System.out.println();
            System.out.println("=== 案例二：condition 條件監聽（金額 > 1000 才觸發）===");
            checkout.place("C003", 5000);
        }

        System.out.println();
        System.out.println("=== 案例三：@Async 之後才是非同步 ===");
        try (var ctx = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            Checkout checkout = ctx.getBean(Checkout.class);
            long t0 = System.currentTimeMillis();
            checkout.place("D004", 800);
            log("place() 返回，耗時 " + (System.currentTimeMillis() - t0) + " ms（稽核還在別條 thread 上跑）");
            Thread.sleep(500);   // 等非同步 listener 把話說完
        }
    }

    record OrderPlaced(String id, int amount) { }

    static class Checkout {
        @Autowired ApplicationEventPublisher publisher;
        void place(String id, int amount) {
            log("下單 " + id + "（金額 " + amount + "），publishEvent…");
            publisher.publishEvent(new OrderPlaced(id, amount));
            log("publishEvent 返回，下單流程繼續");
        }
    }

    // ---------- 同步世界 ----------
    static class MailListener {
        @Order(1) @EventListener
        void on(OrderPlaced e) { log("① 寄出確認信：" + e.id()); }
    }
    static class PointListener {
        @Order(2) @EventListener
        void on(OrderPlaced e) { log("② 加會員點數：" + e.id()); }
    }
    static class PoisonListener {
        @Order(3) @EventListener
        void on(OrderPlaced e) {
            if (e.id().startsWith("B")) throw new IllegalStateException("點數系統故障（" + e.id() + "）");
        }
    }
    static class BigOrderListener {
        // 注意：record 事件會被包進 PayloadApplicationEvent，#root.event 是「包裝」——
        // 要拿事件本體得用 #root.args[0]（或編譯帶 -parameters 後用參數名 #e）
        @Order(4) @EventListener(condition = "#root.args[0].amount() > 1000")
        void on(OrderPlaced e) { log("④ 大額訂單通報主管：" + e.id() + "（金額 " + e.amount() + "）"); }
    }

    @Configuration static class SyncConfig {
        @Bean Checkout checkout() { return new Checkout(); }
        @Bean MailListener mail() { return new MailListener(); }
        @Bean PointListener point() { return new PointListener(); }
        @Bean PoisonListener poison() { return new PoisonListener(); }
        @Bean BigOrderListener big() { return new BigOrderListener(); }
    }

    // ---------- 非同步世界 ----------
    static class SlowAuditListener {
        @Async @EventListener
        void on(OrderPlaced e) throws InterruptedException {
            Thread.sleep(200);   // 模擬慢速稽核
            log("（慢速）稽核完成：" + e.id());
        }
    }

    @EnableAsync
    @Configuration static class AsyncConfig {
        @Bean Checkout checkout() { return new Checkout(); }
        @Bean SlowAuditListener audit() { return new SlowAuditListener(); }
    }
}
