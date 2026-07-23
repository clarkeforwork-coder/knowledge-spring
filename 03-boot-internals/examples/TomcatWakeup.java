///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter-web:3.4.5

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;

/**
 * 內嵌 Tomcat 的兩段式甦醒實驗：
 *
 * 實驗一（建於第 9 步、開門於第 12 步）：
 *   SlowBean 的 @PostConstruct 睡 8 秒（第 11 步）——期間從外面 curl 連不上（connection refused），
 *   對照 app log 的兩行時間戳：「Tomcat initialized」(第 9 步) 與「Tomcat started」(第 12 步) 相隔 8 秒。
 *   docker run -d --name tom -p 18083:8080 -v "$PWD":/ws -w /ws \
 *     -v knowledge-spring-jbang:/root/.jbang -v knowledge-spring-m2:/root/.m2 \
 *     jbangdev/jbang-action TomcatWakeup.java
 *   （host 端以迴圈 curl 觀察 refused → 200 的轉折）
 *
 * 實驗二（誰在跑 request）：
 *   curl http://localhost:18083/whoami → http-nio-8080-exec-N（Tomcat 的執行緒池）
 *
 * 實驗三（server.port=0 隨機埠）：
 *   … jbangdev/jbang-action TomcatWakeup.java --server.port=0
 *   → WebServerInitializedEvent 報出實際綁定的埠後自行關閉
 */
public class TomcatWakeup {

    static void log(String msg) { System.out.println("  [" + LocalTime.now().withNano(0) + "] " + msg); }

    public static void main(String[] args) {
        var ctx = SpringApplication.run(App.class, args);
        if ("0".equals(ctx.getEnvironment().getProperty("server.port"))) {
            int port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
            log("server.port=0 → 實際綁定的 port=" + port);
            ctx.close();
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {
        @Bean SlowBean slowBean() { return new SlowBean(); }
        @Bean Api api() { return new Api(); }
        @Bean PortReporter portReporter() { return new PortReporter(); }
    }

    static class SlowBean {
        @PostConstruct void init() throws InterruptedException {
            log("SlowBean @PostConstruct 開始（第 11 步）——睡 8 秒，此刻 Tomcat 已建立但『還沒開門』");
            Thread.sleep(8000);
            log("SlowBean @PostConstruct 結束");
        }
    }

    static class PortReporter {
        @EventListener void onReady(WebServerInitializedEvent e) {
            log("WebServerInitializedEvent：port " + e.getWebServer().getPort() + " 開門了（第 12 步）");
        }
    }

    @RestController
    static class Api {
        @GetMapping("/whoami")
        String whoami() { return "處理我的執行緒：" + Thread.currentThread().getName() + "\n"; }
    }
}
