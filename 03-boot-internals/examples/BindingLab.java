///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter:3.4.5
//DEPS org.springframework.boot:spring-boot-starter-validation:3.4.5

import jakarta.validation.constraints.Min;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @ConfigurationProperties 綁定四連測（用 docker -e MY_APP_NAME=... 提供環境變數）：
 * 一、relaxed binding——kebab、camelCase、UPPER_SNAKE 環境變數三種寫法都綁得上
 * 二、record 建構子綁定＋@DefaultValue＋Duration 轉換＋巢狀＋List
 * 三、打錯 key＝沉默忽略（退回預設值）；型別錯＝啟動即炸（FailureAnalyzer 報告）
 * 四、@Validated＝值非法時 fail fast
 * 執行：docker run -e MY_APP_NAME=來自環境變數 … jbangdev/jbang-action BindingLab.java
 */
public class BindingLab {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        System.out.println("=== 一＋二：relaxed binding 全家福 ===");
        try (ConfigurableApplicationContext ctx = app().run(
                "--my-app.retry-cont=99",          // ❌ 打錯字（少個 u）——觀察它的下場
                "--my-app.timeout=5s",             // 字串 → Duration
                "--myApp.owner=camelCase寫的key",   // camelCase 也綁得上
                "--my-app.channels[0]=email",
                "--my-app.channels[1]=sms",
                "--my-app.api.url=https://core.example.com",
                "--my-app.api.token=secret")) {
            log(ctx.getBean(MyAppProps.class).toString());
            log("→ name 來自環境變數 MY_APP_NAME；owner 來自 camelCase 參數；");
            log("→ retryCount=3：打錯的 retry-cont 被「沉默忽略」，退回 @DefaultValue");
        }

        System.out.println();
        System.out.println("=== 三：型別錯——啟動即炸 ===");
        try (var ctx = app().run("--my-app.timeout=永遠")) {
            log("（不會走到這行）");
        } catch (Exception e) {
            log("啟動失敗：" + rootMessage(e));
        }

        System.out.println();
        System.out.println("=== 四：@Validated——值非法 fail fast ===");
        try (var ctx = app().run("--strict.pool-size=0")) {
            log("（不會走到這行）");
        } catch (Exception e) {
            log("啟動失敗：" + rootMessage(e));
        }
    }

    static SpringApplication app() {
        var app = new SpringApplication(App.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        return app;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    @ConfigurationProperties(prefix = "my-app")
    record MyAppProps(
            String name,                            // 由環境變數 MY_APP_NAME 提供
            String owner,                           // 由 --myApp.owner 提供
            @DefaultValue("3") int retryCount,      // 沒綁到就用預設
            Duration timeout,                       // "5s" → PT5S
            List<String> channels,                  // 索引語法綁 List
            Api api) {                              // 巢狀 record
        record Api(String url, String token) { }
    }

    @Validated
    @ConfigurationProperties(prefix = "strict")
    record StrictProps(@Min(1) @DefaultValue("5") int poolSize) { }

    @Configuration
    @EnableAutoConfiguration
    @EnableConfigurationProperties({ MyAppProps.class, StrictProps.class })
    static class App { }
}
