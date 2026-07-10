///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Environment 三連測（用 docker -e APP_TIMEOUT=10 執行以提供環境變數）：
 * 場景A：PropertySource 疊層 first-wins（-D > 環境變數 > 設定檔），與 addFirst 插隊
 * 場景B：缺 key 時——沒有 PSPC 安靜注入字面值 vs 有 PSPC 啟動即炸
 * 場景C：@Profile 開關 bean，且 spring.profiles.active 本身就是一個 property
 * 執行：docker run -e APP_TIMEOUT=10 … jbangdev/jbang-action EnvStack.java
 */
public class EnvStack {

    static void log(String c, String msg) { System.out.printf("%-42s| %s%n", c, msg); }

    // 模擬 @PropertySource 讀進來的設定檔（單檔範例，以 MapPropertySource 代替實體 .properties）
    static MapPropertySource fakeFile() {
        return new MapPropertySource("app.properties(模擬設定檔)", Map.of("app.timeout", "30"));
    }

    static AnnotationConfigApplicationContext build(Class<?> cfg, Consumer<AnnotationConfigApplicationContext> pre) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addLast(fakeFile());
        pre.accept(ctx);
        ctx.register(cfg);
        ctx.refresh();
        return ctx;
    }

    public static void main(String[] args) {
        System.out.println("=== 場景A：一疊 PropertySource，先問先贏 ===");
        System.setProperty("app.timeout", "5");
        try (var ctx = build(TimeoutConfig.class, c -> { })) {
            var names = ctx.getEnvironment().getPropertySources().stream()
                    .map(ps -> ps.getName()).toList();
            log("這一疊（由上往下問）", names.toString());
            log("三處同時設定 app.timeout", "-D=5、環境變數 APP_TIMEOUT=10、設定檔=30");
            log("@Value(\"${app.timeout}\")", ctx.getBean(TimeoutHolder.class).timeout + "  ← systemProperties 先答");
        }
        System.clearProperty("app.timeout");
        try (var ctx = build(TimeoutConfig.class, c -> { })) {
            log("拿掉 -D 再啟動一次", ctx.getBean(TimeoutHolder.class).timeout
                    + " ← 環境變數出線：APP_TIMEOUT 被當成 app.timeout 回答");
        }
        try (var ctx = build(TimeoutConfig.class, c -> c.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("緊急覆寫", Map.of("app.timeout", "99"))))) {
            log("addFirst 插一層「緊急覆寫」", ctx.getBean(TimeoutHolder.class).timeout + "  ← 插隊者先答");
        }

        System.out.println();
        System.out.println("=== 場景B：key 不存在的兩種下場 ===");
        try (var ctx = build(NoResolverConfig.class, c -> { })) {
            MissingHolder h = ctx.getBean(MissingHolder.class);
            log("沒有 PSPC：@Value(\"${app.missing}\")", h.missing + "  ← 字面值被安靜注入！");
            log("沒有 PSPC：@Value(\"${app.missing:fallback}\")", h.withDefault);
        }
        try (var ctx = build(StrictConfig.class, c -> { })) {
            log("有 PSPC", "（不會走到這行）");
        } catch (BeansException e) {
            log("有 PSPC：啟動即炸", e.getMostSpecificCause().getMessage());
        }

        System.out.println();
        System.out.println("=== 場景C：Profile 開關 bean ===");
        try (var ctx = build(ProfiledConfig.class, c -> c.getEnvironment().setActiveProfiles("dev"))) {
            log("setActiveProfiles(\"dev\")", "拿到的 Gateway：" + ctx.getBean(Gateway.class).name());
        }
        System.setProperty("spring.profiles.active", "prod");
        try (var ctx = build(ProfiledConfig.class, c -> { })) {
            log("-Dspring.profiles.active=prod", "拿到的 Gateway：" + ctx.getBean(Gateway.class).name()
                    + " ← 啟用 profile 本身也只是一個 property");
        }
        System.clearProperty("spring.profiles.active");
    }

    static class TimeoutHolder { @Value("${app.timeout}") String timeout; }
    @Configuration static class TimeoutConfig {
        @Bean TimeoutHolder timeoutHolder() { return new TimeoutHolder(); }
    }

    static class MissingHolder {
        @Value("${app.missing}") String missing;
        @Value("${app.missing:fallback}") String withDefault;
    }
    @Configuration static class NoResolverConfig {
        @Bean MissingHolder missingHolder() { return new MissingHolder(); }
    }
    @Configuration static class StrictConfig {
        @Bean static PropertySourcesPlaceholderConfigurer pspc() { return new PropertySourcesPlaceholderConfigurer(); }
        @Bean MissingHolder missingHolder() { return new MissingHolder(); }
    }

    interface Gateway { String name(); }
    @Configuration static class ProfiledConfig {
        @Bean @Profile("dev")  Gateway devGateway()  { return () -> "沙盒閘道（dev）"; }
        @Bean @Profile("prod") Gateway prodGateway() { return () -> "正式閘道（prod）"; }
    }
}
