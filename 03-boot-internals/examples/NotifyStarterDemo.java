///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter:3.4.5
//FILES META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports=notify-starter.imports

// 本範例破例帶一個資源檔（notify-starter.imports）：starter 的「引依賴即生效」
// 就是靠 META-INF/spring/...AutoConfiguration.imports 被發現的——沒有它就不是 starter demo。

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自製 starter 的最小完整版：
 * 情境A：使用者什麼都不寫——內建 Notifier 自動生效，且可用 notify.* 屬性調整
 * 情境B：使用者自定義 Notifier——starter 讓位（@ConditionalOnMissingBean），附條件報告為證
 * 情境C：notify.enabled=false——整包關閉（@ConditionalOnProperty），附條件報告為證
 * 執行：jbang NotifyStarterDemo.java
 */
public class NotifyStarterDemo {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        System.out.println("=== 情境A：引依賴即生效（使用者零程式碼）===");
        try (var ctx = run(UserAppA.class, "--notify.prefix=[緊急]")) {
            ctx.getBean(Notifier.class).send("系統將於今晚維護");
        }

        System.out.println();
        System.out.println("=== 情境B：使用者自定義 → starter 讓位 ===");
        try (var ctx = run(UserAppB.class)) {
            ctx.getBean(Notifier.class).send("系統將於今晚維護");
            printConditions(ctx);
        }

        System.out.println();
        System.out.println("=== 情境C：notify.enabled=false → 整包關閉 ===");
        try (var ctx = run(UserAppA.class, "--notify.enabled=false")) {
            try {
                ctx.getBean(Notifier.class);
            } catch (NoSuchBeanDefinitionException e) {
                log("getBean(Notifier.class) 炸了：" + e.getMessage());
            }
            printConditions(ctx);
        }
    }

    static ConfigurableApplicationContext run(Class<?> cfg, String... args) {
        var app = new SpringApplication(cfg);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        return app.run(args);
    }

    static void printConditions(ConfigurableApplicationContext ctx) {
        log("條件評估報告（節選 Notify 相關）：");
        var report = ConditionEvaluationReport.get((ConfigurableListableBeanFactory) ctx.getBeanFactory());
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (source.contains("Notify")) {
                String name = source.substring(source.lastIndexOf('.') + 1);
                outcomes.forEach(co -> log("  " + name + " → " + co.getOutcome()));
            }
        });
    }

    // ================== 使用者側（兩種 App）==================

    @Configuration
    @EnableAutoConfiguration          // 自動配置由 imports 檔「發現」，App 完全不認識 NotifyAutoConfiguration
    static class UserAppA { }

    @Configuration
    @EnableAutoConfiguration
    static class UserAppB {
        @Bean Notifier customNotifier() {   // 使用者自己的實作
            return msg -> log("[自家的通知器] " + msg);
        }
    }
}

// ================== 以下是「starter 側」：實務上住在獨立的 jar ==================

interface Notifier { void send(String msg); }

@ConfigurationProperties(prefix = "notify")
record NotifyProps(@DefaultValue("[公告]") String prefix) { }

@AutoConfiguration
@EnableConfigurationProperties(NotifyProps.class)
@ConditionalOnProperty(prefix = "notify", name = "enabled", havingValue = "true", matchIfMissing = true)
class NotifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean         // 使用者有自己的 Notifier 就讓位
    Notifier consoleNotifier(NotifyProps props) {
        return msg -> System.out.println("  [內建通知器] " + props.prefix() + " " + msg);
    }
}
