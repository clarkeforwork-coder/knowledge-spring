///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Cacheable 地雷四連測：
 * 案例一：key 的預設規則——沒實作 equals 的參數永遠 cache miss；record 一發命中
 * 案例二：共用 cache name 的跨方法污染——炸出真實的 ClassCastException
 * 案例三：null 也會被快取——查無資料的第二次不再執行方法
 * 案例四：抽象層沒有 TTL——髒讀實證與 @CacheEvict 修復
 * 執行：jbang CacheTraps.java
 */
public class CacheTraps {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            PriceService svc = ctx.getBean(PriceService.class);

            System.out.println("=== 案例一：參數就是 key——equals 決定命中率 ===");
            svc.quoteBad(new BadQuery("apple"));
            svc.quoteBad(new BadQuery("apple"));
            log("BadQuery（沒有 equals/hashCode）連查兩次 → 方法本體執行了 " + Db.badRuns + " 次");
            svc.quoteGood(new GoodQuery("apple"));
            svc.quoteGood(new GoodQuery("apple"));
            log("GoodQuery（record，自帶 equals）連查兩次 → 方法本體執行了 " + Db.goodRuns + " 次");

            System.out.println();
            System.out.println("=== 案例二：共用 cache name 的跨方法污染 ===");
            log("name(1L) 先進快取：" + svc.name(1L));
            try {
                Integer stock = svc.stock(1L);
                log("（不會走到這行）stock = " + stock);
            } catch (ClassCastException e) {
                log("stock(1L) 炸了：" + e.getMessage());
            }

            System.out.println();
            System.out.println("=== 案例三：null 也會被快取 ===");
            log("discount(9L) 第一次：" + svc.discount(9L));
            log("discount(9L) 第二次：" + svc.discount(9L));
            log("方法本體執行了 " + Db.discountRuns + " 次——查無資料也被記住了");

            System.out.println();
            System.out.println("=== 案例四：抽象層沒有 TTL——髒讀與 evict ===");
            log("quote(\"apple\")：" + svc.quote("apple"));
            Db.price = 88;    // 價格改了（模擬別處更新了資料）
            log("改價後再 quote(\"apple\")：" + svc.quote("apple") + "  ← 還是舊價，永不過期");
            svc.evictQuote("apple");
            log("evict 後再 quote(\"apple\")：" + svc.quote("apple"));
        }
    }

    static class BadQuery {                       // ❌ 沒有 equals/hashCode：每個實例都是「不同的 key」
        final String sku;
        BadQuery(String sku) { this.sku = sku; }
    }
    record GoodQuery(String sku) { }              // ✅ record 自帶以欄位為準的 equals/hashCode

    // 狀態放在 proxy 之外：容器給你的 PriceService 是 CGLIB proxy，
    // 透過 proxy 讀寫「欄位」碰到的是 proxy 自己那份，不是本尊的（方法才會轉發）
    static class Db {
        static int badRuns, goodRuns, discountRuns;
        static double price = 100;
    }

    static class PriceService {
        @Cacheable("quotes") double quoteBad(BadQuery q)  { Db.badRuns++;  return Db.price; }
        @Cacheable("quotes") double quoteGood(GoodQuery q) { Db.goodRuns++; return Db.price; }

        // ❌ 兩個方法共用 "catalog"，單參數預設 key 都是 1L——彼此打架
        @Cacheable("catalog") String  name(Long id)  { return "蘋果"; }
        @Cacheable("catalog") Integer stock(Long id) { return 42; }

        @Cacheable("discounts") Object discount(Long id) { Db.discountRuns++; return null; }

        @Cacheable(value = "prices", key = "#sku") double quote(String sku) { return Db.price; }
        @CacheEvict(value = "prices", key = "#sku") void evictQuote(String sku) { }
    }

    @EnableCaching
    @Configuration
    static class Cfg {
        @Bean PriceService priceService() { return new PriceService(); }
        // plain Spring 要自己給 CacheManager；預設實作：本地 ConcurrentMap、無 TTL、無上限
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
    }
}
