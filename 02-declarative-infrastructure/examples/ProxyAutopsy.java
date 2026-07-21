///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS org.aspectj:aspectjweaver:1.9.22
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * proxy 解剖四連測：
 * 實驗一：JDK vs CGLIB 的選擇——有介面預設走 JDK，且「用實作類別領 bean」當場炸型別
 * 實驗二：一顆 bean 兩種切面（@Aspect ＋ @Async）——仍然只有「一顆」proxy，advisor 疊鏈
 * 實驗三：CGLIB 的沉默弱點——final 方法無聲不攔截
 * 實驗四：@Configuration 的 CGLIB 是另一回事——@Bean 互呼叫拿同一顆 vs proxyBeanMethods=false
 * 執行：jbang ProxyAutopsy.java
 */
public class ProxyAutopsy {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        System.out.println("=== 實驗一：有介面 → 預設 JDK proxy ===");
        try (var ctx = new AnnotationConfigApplicationContext(JdkConfig.class)) {
            PayApi api = ctx.getBean(PayApi.class);
            log("getBean(PayApi.class) 的實際型別：" + api.getClass().getName());
            api.pay();
            try {
                ctx.getBean("payService", PayService.class);
            } catch (BeansException e) {
                log("getBean(PayService.class) 炸了：" + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== 實驗一b：proxyTargetClass = true → CGLIB ===");
        try (var ctx = new AnnotationConfigApplicationContext(CglibConfig.class)) {
            PayService svc = ctx.getBean(PayService.class);   // 這次用實作類別領，成功
            log("getBean(PayService.class) 的實際型別：" + svc.getClass().getName());
        }

        System.out.println();
        System.out.println("=== 實驗二：@Aspect ＋ @Async 同一顆 bean——一顆 proxy、advisor 疊鏈 ===");
        try (var ctx = new AnnotationConfigApplicationContext(StackConfig.class)) {
            ReportService svc = ctx.getBean(ReportService.class);
            log("實際型別：" + svc.getClass().getName());
            log("是 Advised 嗎？" + (svc instanceof Advised));
            Advised advised = (Advised) svc;
            log("advisor 清單：");
            Arrays.stream(advised.getAdvisors())
                    .forEach(a -> log("  - " + a.getAdvice().getClass().getSimpleName()));
        }

        System.out.println();
        System.out.println("=== 實驗三：CGLIB 的沉默弱點——final 方法 ===");
        try (var ctx = new AnnotationConfigApplicationContext(CglibConfig.class)) {
            PayService svc = ctx.getBean(PayService.class);
            log("呼叫 nonFinal 方法：");
            svc.pay();
            log("呼叫 final 方法（同樣標了 @Traced）：");
            svc.payFinal();
        }

        System.out.println();
        System.out.println("=== 實驗四：@Configuration 的 CGLIB——@Bean 互呼叫 ===");
        try (var ctx = new AnnotationConfigApplicationContext(FullConfig.class)) {
            Car car = ctx.getBean(Car.class);
            Engine managed = ctx.getBean(Engine.class);
            log("full 模式 config 類別型別：" + ctx.getBean(FullConfig.class).getClass().getSimpleName());
            log("bar() 裡呼叫兩次 engine()：同一顆？" + (car.a == car.b)
                    + "；就是容器那顆？" + (car.a == managed));
        }
        try (var ctx = new AnnotationConfigApplicationContext(LiteConfig.class)) {
            Car car = ctx.getBean(Car.class);
            Engine managed = ctx.getBean(Engine.class);
            log("lite 模式 config 類別型別：" + ctx.getBean(LiteConfig.class).getClass().getSimpleName());
            log("bar() 裡呼叫兩次 engine()：同一顆？" + (car.a == car.b)
                    + "；就是容器那顆？" + (car.a == managed));
        }
    }

    // ---------- 切面素材 ----------
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    @interface Traced { }

    @Aspect
    static class Tracer {
        @Before("@annotation(ProxyAutopsy.Traced)")
        void before(org.aspectj.lang.JoinPoint jp) {
            log("  [切面] 攔截到 " + jp.getSignature().getName() + "()");
        }
    }

    interface PayApi { void pay(); }

    static class PayService implements PayApi {
        @Traced @Override public void pay() { log("  pay() 本體執行"); }
        @Traced public final void payFinal() { log("  payFinal() 本體執行（final）"); }
    }

    static class ReportService {
        @Traced @Async public void export() { }
    }

    // ---------- 配置 ----------
    @EnableAspectJAutoProxy                             // 預設 proxyTargetClass = false
    @Configuration static class JdkConfig {
        @Bean Tracer tracer() { return new Tracer(); }
        @Bean PayService payService() { return new PayService(); }
    }

    @EnableAspectJAutoProxy(proxyTargetClass = true)    // Boot 的預設姿勢
    @Configuration static class CglibConfig {
        @Bean Tracer tracer() { return new Tracer(); }
        @Bean PayService payService() { return new PayService(); }
    }

    @EnableAsync
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Configuration static class StackConfig {
        @Bean Tracer tracer() { return new Tracer(); }
        @Bean ReportService reportService() { return new ReportService(); }
    }

    // ---------- @Configuration 的 CGLIB ----------
    static class Engine { }
    static class Car { final Engine a, b; Car(Engine a, Engine b) { this.a = a; this.b = b; } }

    @Configuration                                      // full 模式：類別被 CGLIB 增強
    static class FullConfig {
        @Bean Engine engine() { return new Engine(); }
        @Bean Car car() { return new Car(engine(), engine()); }   // 互呼叫被攔截 → 拿容器那顆
    }

    @Configuration(proxyBeanMethods = false)            // lite 模式：不增強
    static class LiteConfig {
        @Bean Engine engine() { return new Engine(); }
        @Bean Car car() { return new Car(engine(), engine()); }   // 就是普通方法呼叫 → 每次 new
    }
}
