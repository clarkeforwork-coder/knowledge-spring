///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS org.springframework:spring-expression:6.2.8
//DEPS org.hibernate.validator:hibernate-validator:8.0.1.Final
//DEPS org.glassfish.expressly:expressly:5.0.0
// 注意：expressly 5.0.0 的 POM 夾帶 spring-expression 5.3.22，
// 必須明式宣告 6.2.8 蓋掉，否則啟動炸 NoSuchMethodError（SpelParserConfiguration）
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * @Validated 方法級驗證五連測：
 * 案例一：有 @Validated——非法參數在方法門口被擋下（ConstraintViolationException）
 * 案例二：忘了 @Validated——同樣的 constraint 無聲不驗，髒資料長驅直入
 * 案例三：物件參數要 @Valid 才會往內驗（cascade）
 * 案例四：回傳值也能驗（@NotNull 擋住回 null 的方法）
 * 案例五：self-invocation——proxy 家族病第三次發作
 * 執行：jbang ValidationGate.java
 */
public class ValidationGate {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            TransferService svc = ctx.getBean(TransferService.class);
            UnguardedService unguarded = ctx.getBean(UnguardedService.class);

            System.out.println("=== 案例一：有 @Validated，非法參數進不了門 ===");
            svc.transfer("A123", 500);
            try {
                svc.transfer("", 0);
            } catch (ConstraintViolationException e) {
                log("擋下：" + e.getMessage());
            }

            System.out.println();
            System.out.println("=== 案例二：忘了 @Validated——同樣的 constraint 無聲不驗 ===");
            unguarded.transfer("", -5);

            System.out.println();
            System.out.println("=== 案例三：物件參數要 @Valid 才往內驗 ===");
            try {
                svc.pay(new Payee("", "abc"));
            } catch (ConstraintViolationException e) {
                log("擋下：" + e.getMessage());
            }
            svc.payNoCascade(new Payee("", "abc"));

            System.out.println();
            System.out.println("=== 案例四：回傳值驗證 ===");
            try {
                svc.findAccount("X999");
            } catch (ConstraintViolationException e) {
                log("擋下：" + e.getMessage());
            }

            System.out.println();
            System.out.println("=== 案例五：self-invocation——驗證無聲跳過 ===");
            svc.outer();
        }
    }

    record Payee(@NotBlank String name, @Pattern(regexp = "\\d{10,16}") String account) { }

    @Validated                       // ★ 開關在「類別」上——掛在方法上沒有用
    static class TransferService {
        double transfer(@NotBlank String to, @Min(1) long amount) {
            log("執行轉帳：to=" + to + "、amount=" + amount);
            return amount;
        }
        void pay(@Valid Payee payee) { log("付款給 " + payee.name()); }
        void payNoCascade(Payee payee) { log("付款給「" + payee.name() + "」——沒有 @Valid，物件內欄位不驗"); }
        @NotNull String findAccount(String id) { return null; }   // 資料層失手回了 null
        void outer() {
            log("outer() 直接呼叫 this.transfer(\"\", -99)…");
            transfer("", -99);       // ❌ 走 this 不走 proxy——驗證跳過
        }
    }

    static class UnguardedService {  // ❌ 忘了 @Validated：constraint 全是裝飾品
        double transfer(@NotBlank String to, @Min(1) long amount) {
            log("執行轉帳（沒人擋）：to=" + to + "、amount=" + amount);
            return amount;
        }
    }

    @Configuration
    static class Cfg {
        // 方法驗證的工人（一個 BPP）：plain Spring 要自己請，Boot 有 validator 依賴時自動配置
        @Bean static MethodValidationPostProcessor methodValidation() { return new MethodValidationPostProcessor(); }
        @Bean TransferService transferService() { return new TransferService(); }
        @Bean UnguardedService unguardedService() { return new UnguardedService(); }
    }
}
