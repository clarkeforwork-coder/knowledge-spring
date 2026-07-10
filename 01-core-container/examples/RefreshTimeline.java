///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * refresh() 全程直播：每個擴充點在啟動的哪一站被叫到。
 * 執行：jbang RefreshTimeline.java
 */
public class RefreshTimeline {

    static void log(String step, String msg) { System.out.printf("%-38s| %s%n", step, msg); }

    public static void main(String[] args) {
        System.out.println("=== new AnnotationConfigApplicationContext(App.class)：refresh() 開始 ===");
        var ctx = new AnnotationConfigApplicationContext(App.class);
        System.out.println("=== refresh() 返回：容器就緒 ===");
        ctx.close();
    }

    @Configuration
    static class App {
        // static：讓容器不必先建出 App 實例就能拿到 BFPP/BPP（否則 App 被迫「早產」，見筆記）
        @Bean static DesignReviewer designReviewer() { return new DesignReviewer(); }
        @Bean static Inspector inspector() { return new Inspector(); }
        @Bean OrderService orderService() { return new OrderService(); }
        @Bean Doorman doorman() { return new Doorman(); }
    }

    // 步驟5：BeanFactoryPostProcessor 面對的是「設計圖」——此刻業務 bean 一個都不存在
    static class DesignReviewer implements BeanFactoryPostProcessor {
        DesignReviewer() { log("[5] invokeBeanFactoryPostProcessors", "DesignReviewer 建構子（BFPP 自己先出生）"); }
        @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
            log("[5] invokeBeanFactoryPostProcessors",
                "設計圖入庫 " + bf.getBeanDefinitionCount() + " 張；orderService 實例存在？"
                + bf.containsSingleton("orderService"));
        }
    }

    // 步驟6：BeanPostProcessor 提早建立——工人先到職，才加工得到之後的每個 bean
    static class Inspector implements BeanPostProcessor {
        Inspector() { log("[6] registerBeanPostProcessors", "工人 Inspector 到職（建構子）"); }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (name.equals("orderService"))
                log("[11] finishBeanFactoryInitialization", "Inspector 驗收 orderService（AOP proxy 誕生的那一站）");
            return bean;
        }
    }

    static class OrderService {
        OrderService() { log("[11] finishBeanFactoryInitialization", "OrderService 建構子"); }
        @PostConstruct void init() { log("[11] finishBeanFactoryInitialization", "OrderService @PostConstruct"); }
        @EventListener void onReady(ContextRefreshedEvent e) {
            log("[12] finishRefresh", "收到 ContextRefreshedEvent——全廠開幕，所有 singleton 已就緒");
        }
    }

    // 步驟12：SmartLifecycle 在 ContextRefreshedEvent「之前」被 start
    static class Doorman implements SmartLifecycle {
        boolean running;
        @Override public void start() { running = true; log("[12] finishRefresh", "Doorman.start()（SmartLifecycle）"); }
        @Override public void stop()  { running = false; log("[close]", "Doorman.stop()"); }
        @Override public boolean isRunning() { return running; }
    }
}
