///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework:spring-context:6.2.8
//DEPS org.springframework:spring-jdbc:6.2.8
//DEPS com.h2database:h2:2.3.232
//DEPS jakarta.annotation:jakarta.annotation-api:2.1.1

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 交易邊界上的事件五連測（H2 記憶體資料庫）：
 * 場景A：成功提交——@EventListener 交易內立刻收到（看得到未提交資料）、
 *        AFTER_COMMIT 事後才收到；AFTER_COMMIT 裡直接寫 DB＝搭在已完結交易的連線上
 *        （實測：靠 autocommit 恢復時的 JDBC 隱式 commit「意外落地」——契約內的寫法是 REQUIRES_NEW）
 * 場景B：交易回滾——AFTER_COMMIT 不觸發、AFTER_ROLLBACK 觸發
 * 場景D：AFTER_COMMIT listener 丟例外——被吞掉只留 SEVERE，呼叫端無感、交易已提交
 * 場景C：交易外發佈——@TransactionalEventListener 預設直接丟棄
 * 執行：jbang TxEventTiming.java
 */
public class TxEventTiming {

    static void log(String msg) { System.out.println("  " + msg); }

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            jdbc.execute("create table orders(id varchar(20))");
            jdbc.execute("create table audit_direct(id varchar(20))");
            jdbc.execute("create table audit_saved(id varchar(20))");
            OrderService svc = ctx.getBean(OrderService.class);

            System.out.println("=== 場景A：成功提交 ===");
            svc.place("A001", false);
            log("place() 返回");
            log("audit_direct 筆數 = " + count(jdbc, "audit_direct") + "  ← AFTER_COMMIT 裡的直接寫入（見筆記：意外提交）");
            log("audit_saved 筆數 = " + count(jdbc, "audit_saved") + "  ← REQUIRES_NEW 的寫入");

            System.out.println();
            System.out.println("=== 場景B：交易回滾 ===");
            try {
                svc.place("B002", true);
            } catch (IllegalStateException e) {
                log("呼叫端接到例外：" + e.getMessage());
            }
            log("orders 表筆數 = " + count(jdbc, "orders") + "（B002 已回滾）");

            System.out.println();
            System.out.println("=== 場景D：AFTER_COMMIT listener 丟例外 ===");
            try {
                svc.place("D004", false);
                log("place() 正常返回——listener 的例外沒有炸回呼叫端");
            } catch (RuntimeException e) {
                log("呼叫端接到例外：" + e.getMessage() + "（交易其實已提交！）");
            }
            log("orders 表筆數 = " + count(jdbc, "orders") + "（D004 是否已提交？）");

            System.out.println();
            System.out.println("=== 場景C：交易外發佈 ===");
            svc.notifyOutsideTx("C003");
            log("（觀察：AFTER_COMMIT / AFTER_ROLLBACK 都沒出聲——沒有交易可掛，事件被默默丟棄）");
        }
    }

    static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    record OrderPlaced(String id) { }

    static class OrderService {
        final JdbcTemplate jdbc;
        final ApplicationEventPublisher publisher;
        OrderService(JdbcTemplate jdbc, ApplicationEventPublisher publisher) {
            this.jdbc = jdbc; this.publisher = publisher;
        }

        @Transactional
        void place(String id, boolean fail) {
            jdbc.update("insert into orders values (?)", id);
            log("① 交易內：insert " + id + " 完成（尚未 commit）");
            publisher.publishEvent(new OrderPlaced(id));
            log("④ publishEvent 返回，交易繼續");
            if (fail) throw new IllegalStateException("下單失敗，交易回滾");
            log("⑤ place() 方法體結束 → proxy 準備 commit");
        }

        void notifyOutsideTx(String id) {           // 沒有 @Transactional
            publisher.publishEvent(new OrderPlaced(id));
            log("交易外 publishEvent 返回");
        }
    }

    static class Listeners {
        final JdbcTemplate jdbc;
        final DataSource ds;
        final TransactionTemplate requiresNew;
        Listeners(JdbcTemplate jdbc, DataSource ds, TransactionTemplate requiresNew) {
            this.jdbc = jdbc; this.ds = ds; this.requiresNew = requiresNew;
        }

        @EventListener
        void immediate(OrderPlaced e) {
            boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
            log("② @EventListener 立刻收到（交易中？" + inTx + "），此刻查 orders = "
                    + jdbc.queryForObject("select count(*) from orders", Integer.class)
                    + (inTx ? "（未提交的也看得到——同一條交易）" : ""));
        }

        @TransactionalEventListener   // 預設 phase = AFTER_COMMIT
        void afterCommit(OrderPlaced e) {
            log("⑥ AFTER_COMMIT 收到 " + e.id() + "（commit 之後才輪到我）");
            if (e.id().equals("D004")) throw new IllegalStateException("AFTER_COMMIT listener 炸了");
            try {
                var con = DataSourceUtils.getConnection(ds);
                log("   此刻連線 autocommit = " + con.getAutoCommit() + "——還綁在已完結交易的連線上");
                DataSourceUtils.releaseConnection(con, ds);
            } catch (Exception ex) { throw new RuntimeException(ex); }
            jdbc.update("insert into audit_direct values (?)", e.id());          // ⚠️ 搭在已完結的交易上（結局見筆記）
            requiresNew.executeWithoutResult(s ->
                    jdbc.update("insert into audit_saved values (?)", e.id())); // ✅ 自己開新交易
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
        void afterRollback(OrderPlaced e) {
            log("AFTER_ROLLBACK 收到 " + e.id() + "（補償／告警的入口）");
        }
    }

    @EnableTransactionManagement
    @Configuration
    static class Cfg {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager txManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate requiresNew(PlatformTransactionManager tm) {
            var t = new TransactionTemplate(tm);
            t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return t;
        }
        @Bean OrderService orderService(JdbcTemplate jdbc, ApplicationEventPublisher p) {
            return new OrderService(jdbc, p);
        }
        @Bean Listeners listeners(JdbcTemplate jdbc, DataSource ds, TransactionTemplate requiresNew) {
            return new Listeners(jdbc, ds, requiresNew);
        }
    }
}
