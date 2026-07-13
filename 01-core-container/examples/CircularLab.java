///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 循環依賴四連測：
 * 實驗一：欄位注入 A ↔ B（plain Framework 預設）——能解，且兩邊拿到同一顆
 * 實驗二：建構子注入 A ↔ B——無藥可解，貼真實錯誤
 * 實驗三：setAllowCircularReferences(false)——重現 Boot 2.6+ 的預設行為
 * 實驗四：@Async ＋ 循環依賴——proxy 與早期引用的一致性
 *   （6.2.8 成功；把 //DEPS 改成 spring-context:6.1.21 可重現經典 raw version 錯誤，
 *     再把 b2 的 @Bean 移到 a2 前面，6.1 也會成功——炸不炸取決於建立順序）
 * 執行：jbang CircularLab.java
 */
public class CircularLab {

    static void log(String c, String msg) { System.out.printf("%-14s| %s%n", c, msg); }

    public static void main(String[] args) {
        System.out.println("=== 實驗一：欄位注入 A ↔ B（allowCircularReferences 預設 true）===");
        try (var ctx = new AnnotationConfigApplicationContext(FieldConfig.class)) {
            A a = ctx.getBean(A.class);
            B b = ctx.getBean(B.class);
            log("啟動", "成功");
            log("身分驗證", "a.b 是容器裡那顆 B？" + (a.b == b) + "；b.a 是容器裡那顆 A？" + (b.a == a));
        }

        System.out.println();
        System.out.println("=== 實驗二：建構子注入 A ↔ B ===");
        try (var ctx = new AnnotationConfigApplicationContext(CtorConfig.class)) {
            log("啟動", "（不會走到這行）");
        } catch (BeansException e) {
            log("啟動失敗", e.getMostSpecificCause().getMessage());
        }

        System.out.println();
        System.out.println("=== 實驗三：關掉 allowCircularReferences（Boot 2.6+ 的預設）===");
        try {
            var ctx = new AnnotationConfigApplicationContext();
            ctx.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            ctx.register(FieldConfig.class);
            ctx.refresh();
            log("啟動", "（不會走到這行）");
        } catch (BeansException e) {
            log("啟動失敗", e.getMostSpecificCause().getMessage());
        }

        System.out.println();
        System.out.println("=== 實驗四：@Async ＋ 循環依賴——proxy 與早期引用的一致性 ===");
        try (var ctx = new AnnotationConfigApplicationContext(AsyncCircularConfig.class)) {
            A2 a2 = ctx.getBean(A2.class);
            B2 b2 = ctx.getBean(B2.class);
            log("啟動", "成功（舊版 Spring 在這裡會炸 raw version 錯誤）");
            log("a2 實際型別", a2.getClass().getName());
            log("b2.a 實際型別", b2.a.getClass().getName());
            log("身分驗證", "b2 拿到的 a 就是容器裡的 a2（proxy）？" + (b2.a == a2));
        } catch (BeansException e) {
            log("啟動失敗", e.getMostSpecificCause().getMessage());
        }
    }

    // ---------- 欄位注入版 ----------
    static class A { @Autowired B b; }
    static class B { @Autowired A a; }
    @Configuration static class FieldConfig {
        @Bean A a() { return new A(); }
        @Bean B b() { return new B(); }
    }

    // ---------- 建構子注入版 ----------
    static class CA { CA(CB b) { } }
    static class CB { CB(CA a) { } }
    @Configuration static class CtorConfig {
        @Bean CA ca(CB cb) { return new CA(cb); }
        @Bean CB cb(CA ca) { return new CB(ca); }
    }

    // ---------- @Async ＋ 循環 ----------
    static class A2 {
        @Autowired B2 b;
        @Async void audit() { }   // 讓 A2 在 after-init 被換成 async proxy
    }
    static class B2 { @Autowired A2 a; }
    @EnableAsync
    @Configuration static class AsyncCircularConfig {
        @Bean A2 a2() { return new A2(); }
        @Bean B2 b2() { return new B2(); }
    }
}
