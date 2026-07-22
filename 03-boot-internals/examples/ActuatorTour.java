///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter-web:3.4.5
//DEPS org.springframework.boot:spring-boot-starter-actuator:3.4.5

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Actuator 導覽（web 範例，啟動後用 curl 驗證）：
 *
 * 預設封閉版：
 *   docker run -d --name act -p 18080:8080 -v "$PWD":/ws -w /ws \
 *     -v knowledge-spring-jbang:/root/.jbang -v knowledge-spring-m2:/root/.m2 \
 *     jbangdev/jbang-action ActuatorTour.java
 *   curl http://localhost:18080/actuator            → 只列 health
 *   curl http://localhost:18080/actuator/metrics    → 404
 *
 * 開放＋核心系統故障版（追加參數）：
 *   … ActuatorTour.java \
 *     --management.endpoints.web.exposure.include=health,metrics,env \
 *     --management.endpoint.health.show-details=always \
 *     --demo.core-down=true --my.secret=P@ssw0rd
 *   curl -i http://localhost:18080/actuator/health  → 503 ＋ coreSystem DOWN 細節
 *   curl http://localhost:18080/actuator/metrics/jvm.memory.used
 *   curl http://localhost:18080/actuator/env/my.secret → 值被遮罩
 */
public class ActuatorTour {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {

        // 自訂健康檢查：接上「公司核心系統」的探測（這裡用屬性模擬故障開關）
        @Bean
        HealthIndicator coreSystem(Environment env) {
            boolean down = env.getProperty("demo.core-down", Boolean.class, false);
            return () -> down
                    ? Health.down()
                            .withDetail("coreApi", "連線逾時")
                            .withDetail("lastSuccess", "2026-07-22T01:00:00Z")
                            .build()
                    : Health.up().withDetail("coreApi", "ok").build();
        }
    }
}
