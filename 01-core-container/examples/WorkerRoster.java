///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;

/**
 * BeanPostProcessor 三連測：
 * 案例一：拆掉工人——沒有註解處理器的裸容器，@Autowired/@PostConstruct 全滅
 * 案例二：自己當工人——BPP 在 after-init 把 bean 換成 JDK proxy（迷你 AOP）
 * 案例三：早產警告——BPP 依賴業務 bean，重現 "not eligible" INFO
 * 執行：jbang WorkerRoster.java
 */
public class WorkerRoster {

    public static void main(String[] args) {
        System.out.println("=== 案例一：裸容器（只登記設計圖，不請任何註解工人）===");
        var raw = new GenericApplicationContext();
        raw.registerBeanDefinition("dep", new RootBeanDefinition(Dep.class));
        raw.registerBeanDefinition("svc", new RootBeanDefinition(Svc.class));
        raw.refresh();
        raw.getBean(Svc.class).report();
        raw.close();

        System.out.println();
        System.out.println("=== 案例一（對照）＋案例二：AnnotationConfigApplicationContext ===");
        var ctx = new AnnotationConfigApplicationContext(App.class);
        ctx.getBean(Svc.class).report();
        Greeter g = ctx.getBean(Greeter.class);
        System.out.println("容器給的 Greeter 實際型別：" + g.getClass().getName());
        g.hello();
        ctx.close();

        System.out.println();
        System.out.println("=== 案例三：BPP 依賴業務 bean，Dep 被迫早產 ===");
        new AnnotationConfigApplicationContext(AppEager.class).close();
    }

    // ---------- 案例一：@Autowired / @PostConstruct 的「受害者」----------
    static class Dep { }
    static class Svc {
        @Autowired Dep dep;
        boolean initialized;
        @PostConstruct void init() { initialized = true; }
        void report() {
            System.out.println("Svc 狀態：dep = " + dep + "、@PostConstruct 跑了嗎？" + initialized);
        }
    }

    // ---------- 案例二：自訂 BPP＝迷你 AOP ----------
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    @interface Loud { }

    interface Greeter { void hello(); }

    @Loud
    static class LoudGreeter implements Greeter {
        @Override public void hello() { System.out.println("  hello!"); }
    }

    // after-init 站：看到 @Loud 就把成品換成 JDK proxy——@Transactional 的同款手法
    static class MiniAop implements BeanPostProcessor {
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!bean.getClass().isAnnotationPresent(Loud.class)) return bean;
            return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                    bean.getClass().getInterfaces(),
                    (proxy, method, methodArgs) -> {
                        System.out.println("  [proxy] " + method.getName() + "() 進站");
                        Object r = method.invoke(bean, methodArgs);
                        System.out.println("  [proxy] " + method.getName() + "() 出站");
                        return r;
                    });
        }
    }

    @Configuration
    static class App {
        @Bean static MiniAop miniAop() { return new MiniAop(); }
        @Bean Dep dep() { return new Dep(); }
        @Bean Svc svc() { return new Svc(); }
        @Bean Greeter greeter() { return new LoudGreeter(); }
    }

    // ---------- 案例三：BPP 的依賴會早產 ----------
    static class EagerBpp implements BeanPostProcessor {
        EagerBpp(Dep dep) { }   // ❌ BPP 依賴業務 bean：Dep 被迫在工人到職完成前出生
    }

    @Configuration
    static class AppEager {
        @Bean Dep dep() { return new Dep(); }
        @Bean static EagerBpp eagerBpp(Dep dep) { return new EagerBpp(dep); }
    }
}
