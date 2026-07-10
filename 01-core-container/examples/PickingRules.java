///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import jakarta.annotation.Resource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

/**
 * @Autowired 的裁決規則實測：
 * 一、兩顆候選、零提示 → 炸給你看（真實錯誤訊息）
 * 二、沒有 @Primary 時 → 欄位名 fallback
 * 三、有 @Primary 時 → @Primary 贏過欄位名；@Qualifier 與 @Resource 又各自贏過 @Primary
 * 四、集合、Map、泛型、缺席者
 * 執行：jbang PickingRules.java
 */
public class PickingRules {

    static void log(String c, String msg) { System.out.printf("%-34s| %s%n", c, msg); }

    public static void main(String[] args) {
        System.out.println("=== 一、兩顆候選、零提示 ===");
        try {
            new AnnotationConfigApplicationContext(FailConfig.class);
        } catch (BeansException e) {
            log("NoUniqueBeanDefinitionException", e.getMostSpecificCause().getMessage());
        }

        System.out.println();
        System.out.println("=== 二、沒有 @Primary：欄位名出面 ===");
        try (var ctx = new AnnotationConfigApplicationContext(NoPrimaryConfig.class)) {
            log("@Autowired Notifier emailNotifier", ctx.getBean(HolderB.class).emailNotifier.name());
        }

        System.out.println();
        System.out.println("=== 三、smsNotifier 標上 @Primary 之後 ===");
        try (var ctx = new AnnotationConfigApplicationContext(PrimaryConfig.class)) {
            HolderC h = ctx.getBean(HolderC.class);
            HolderR r = ctx.getBean(HolderR.class);
            log("@Autowired Notifier emailNotifier", h.emailNotifier.name() + "   ← @Primary 贏過欄位名！");
            log("@Resource  Notifier emailNotifier", r.emailNotifier.name() + " ← @Resource 名字優先，結論反過來");
            log("@Autowired @Qualifier(\"emailNotifier\")", h.byQualifier.name() + " ← @Qualifier 贏過 @Primary");

            System.out.println();
            System.out.println("=== 四、集合、Map、泛型、缺席者 ===");
            log("List<Notifier>（依 @Order 排）", h.all.stream().map(Notifier::name).toList().toString());
            log("Map<String, Notifier>（bean 名為 key）", h.byName.keySet().toString());
            log("@Autowired Store<Apple>", h.appleStore.label);
            log("ObjectProvider<Missing>.getIfAvailable()", String.valueOf(h.missing.getIfAvailable()));
        }
    }

    interface Notifier { String name(); }
    @Order(1) static class SmsNotifier   implements Notifier { public String name() { return "SMS"; } }
    @Order(2) static class EmailNotifier implements Notifier { public String name() { return "EMAIL"; } }
    static class PushNotifier            implements Notifier { public String name() { return "PUSH"; } }

    static class Apple { }
    static class Orange { }
    static class Store<T> { final String label; Store(String label) { this.label = label; } }
    static class Missing { }

    // ---------- 一 ----------
    @Configuration static class FailConfig {
        @Bean Notifier emailNotifier() { return new EmailNotifier(); }
        @Bean Notifier smsNotifier()   { return new SmsNotifier(); }
        @Bean Victim victim() { return new Victim(); }
    }
    static class Victim { @Autowired Notifier notifier; }

    // ---------- 二 ----------
    @Configuration static class NoPrimaryConfig {
        @Bean Notifier emailNotifier() { return new EmailNotifier(); }
        @Bean Notifier smsNotifier()   { return new SmsNotifier(); }
        @Bean HolderB holderB() { return new HolderB(); }
    }
    static class HolderB { @Autowired Notifier emailNotifier; }

    // ---------- 三、四 ----------
    @Configuration static class PrimaryConfig {
        @Bean Notifier emailNotifier() { return new EmailNotifier(); }
        @Bean @Primary Notifier smsNotifier() { return new SmsNotifier(); }
        @Bean Notifier pushNotifier() { return new PushNotifier(); }
        @Bean Store<Apple>  appleStore()  { return new Store<>("蘋果店"); }
        @Bean Store<Orange> orangeStore() { return new Store<>("橘子店"); }
        @Bean HolderC holderC() { return new HolderC(); }
        @Bean HolderR holderR() { return new HolderR(); }
    }
    static class HolderC {
        @Autowired Notifier emailNotifier;                            // 欄位名 vs @Primary，誰贏？
        @Autowired @Qualifier("emailNotifier") Notifier byQualifier;  // @Qualifier vs @Primary，誰贏？
        @Autowired List<Notifier> all;
        @Autowired Map<String, Notifier> byName;
        @Autowired Store<Apple> appleStore;
        @Autowired ObjectProvider<Missing> missing;
    }
    static class HolderR {
        @Resource Notifier emailNotifier;   // 與 HolderC 第一個欄位宣告完全相同，只差註解
    }
}
