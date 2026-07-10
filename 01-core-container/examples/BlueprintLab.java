///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.util.List;

/**
 * 設計圖（BeanDefinition）的三種權力：讀、造、改。
 * 執行：jbang BlueprintLab.java
 */
public class BlueprintLab {

    static void log(String step, String msg) { System.out.printf("%-24s| %s%n", step, msg); }

    public static void main(String[] args) {
        var ctx = new AnnotationConfigApplicationContext(App.class);
        System.out.println("--- refresh() 返回：容器就緒（注意：HeavyReport 建構子還沒出現）---");
        ctx.getBean(HeavyReport.class);
        ctx.close();
    }

    @Configuration
    @Import(TeamRegistrar.class)
    static class App {
        @Bean static BlueprintEditor blueprintEditor() { return new BlueprintEditor(); }
        @Bean OrderService orderService() { return new OrderService(); }
        @Bean HeavyReport heavyReport() { return new HeavyReport(); }
    }

    // 權力二「造」：程式算出來的設計圖——@MapperScan、@EnableJpaRepositories 的機制
    static class TeamRegistrar implements ImportBeanDefinitionRegistrar {
        @Override public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry reg) {
            for (String name : List.of("alpha", "beta")) {
                BeanDefinition bd = BeanDefinitionBuilder.genericBeanDefinition(Worker.class)
                        .addConstructorArgValue(name)
                        .getBeanDefinition();
                reg.registerBeanDefinition("worker-" + name, bd);
            }
            log("[5] 註冊階段(Registrar)", "登記了 worker-alpha、worker-beta 兩張新設計圖");
        }
    }

    // 權力一「讀」與權力三「改」：BeanFactoryPostProcessor
    static class BlueprintEditor implements BeanFactoryPostProcessor {
        @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
            BeanDefinition bd = bf.getBeanDefinition("orderService");
            log("[5] 修改階段(BFPP)", "讀 orderService 的設計圖：beanClassName=" + bd.getBeanClassName()
                    + "、factoryBean=" + bd.getFactoryBeanName()
                    + "、factoryMethod=" + bd.getFactoryMethodName()
                    + "、scope=\"" + bd.getScope() + "\"");
            bf.getBeanDefinition("heavyReport").setLazyInit(true);
            log("[5] 修改階段(BFPP)", "把 heavyReport 的設計圖改成 lazy——它將錯過第 11 步的量產");
        }
    }

    static class OrderService { OrderService() { log("[11] 量產", "OrderService 建構子"); } }
    static class Worker { Worker(String name) { log("[11] 量產", "Worker(" + name + ") 建構子——沒有對應的原始碼宣告，圖是算出來的"); } }
    static class HeavyReport { HeavyReport() { log("[getBean]", "HeavyReport 建構子——lazy 化之後，用時才蓋"); } }
}
