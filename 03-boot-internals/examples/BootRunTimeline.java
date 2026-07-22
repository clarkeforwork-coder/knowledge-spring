///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter:3.4.5

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import jakarta.annotation.PostConstruct;

/**
 * SpringApplication.run() 全程直播：
 * ① Boot 自己的七個啟動事件（程式化 listener 才聽得到早期事件）
 * ② Environment 在「容器誕生之前」就緒——命令列屬性在 EnvironmentPrepared 時已可讀
 * ③ 容器事件（@PostConstruct / SmartLifecycle / ContextRefreshedEvent）與
 *    Boot 事件、Runner 的先後順序
 * ④ 經典坑：用 bean 的 @EventListener 聽早期 Boot 事件——永遠聽不到
 * 執行：jbang BootRunTimeline.java
 */
public class BootRunTimeline {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        var app = new SpringApplication(App.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        // 程式化註冊（不是 bean！）——所以連容器誕生前的事件都聽得到
        app.addListeners((ApplicationListener<SpringApplicationEvent>) e -> {
            String extra = "";
            if (e instanceof ApplicationEnvironmentPreparedEvent env) {
                extra = "——此刻已能讀 app.name=「" + env.getEnvironment().getProperty("app.name")
                        + "」（容器還不存在！）";
            }
            log("[Boot事件] " + e.getClass().getSimpleName() + extra);
        });
        var ctx = app.run("--app.name=從命令列來的值");
        log("run() 返回");
        ctx.close();
    }

    @Configuration
    @EnableAutoConfiguration            // demo 用（JBang 在 default package，避免 @ComponentScan 掃整個 classpath）
    static class App {
        @Bean OrderService orderService() { return new OrderService(); }
        @Bean Doorman doorman() { return new Doorman(); }
        @Bean TrapListener trapListener() { return new TrapListener(); }
        @Bean ApplicationRunner appRunner() {
            return a -> log("ApplicationRunner 執行（拿得到參數：app.name=" + a.getOptionValues("app.name") + "）");
        }
        @Bean CommandLineRunner cliRunner() {
            return a -> log("CommandLineRunner 執行");
        }
    }

    static class OrderService {
        @PostConstruct void init() { log("OrderService @PostConstruct（第 11 步量產中）"); }
        @EventListener void onReady(ContextRefreshedEvent e) { log("收到 ContextRefreshedEvent（第 12 步）"); }
    }

    static class Doorman implements SmartLifecycle {
        boolean running;
        @Override public void start() { running = true; log("Doorman.start()（SmartLifecycle，第 12 步）"); }
        @Override public void stop()  { running = false; }
        @Override public boolean isRunning() { return running; }
    }

    // ❌ 經典坑：bean 身分的 listener，想聽「容器誕生前」的事件
    static class TrapListener {
        @EventListener void onEnvPrepared(ApplicationEnvironmentPreparedEvent e) {
            log("TrapListener 聽到 EnvironmentPrepared！？（這行不可能出現）");
        }
    }
}
