# @TransactionalEventListener：交易邊界上的事件

## 前言

這篇要收攏前面埋下的兩條線。[事件篇](../01-core-container/application-events.md)證明過：`@EventListener` 是同步的，交易內發佈的事件會**在交易內**被消費——listener 炸了、發佈者的交易跟著回滾。[@Cacheable 篇](cache-abstraction.md)結尾則問：交易還沒 commit 就清快取，別的請求馬上用舊資料回填——清了等於沒清。

兩個問題其實是同一題：**「下單成功後寄信」的「成功」，指的是 commit 成功，不是方法跑完**。方法跑完到 commit 之間存在一段窗口，事件在窗口裡就被消費，等於把「候選事實」當成了「既成事實」。`@TransactionalEventListener` 就是把「等交易有結果再說」宣告化——但它自己也埋了三顆雷，本篇全部實測（其中一顆的實測結果和江湖傳言不同，追出了真相）。

## 技術背景

### 先破除誤解：交易內發出的事件只是「候選事實」

`OrderPlaced` 事件發佈的那一刻，訂單**還沒提交**——它之後可能 commit（事實成立）、也可能 rollback（事實作廢）。用普通 `@EventListener` 立即消費，等於對著一筆可能消失的資料寄信、清快取、通知下游。實測（場景A②）還能看到更細的證據：listener 在交易內查得到**未提交的 row**——它和發佈者共享同一條交易。

### 四個 phase：把「等結果」宣告化

```java
@TransactionalEventListener              // 預設 phase = AFTER_COMMIT
void onPlaced(OrderPlaced e) { ... }     // 交易成功提交後才執行
```

| Phase | 時機 | 典型用途 |
|---|---|---|
| `BEFORE_COMMIT` | commit 前一刻 | 最後校驗、flush 類收尾 |
| `AFTER_COMMIT`（預設） | 成功提交後 | **寄信、清快取、通知外部系統** |
| `AFTER_ROLLBACK` | 回滾後 | 補償、告警 |
| `AFTER_COMPLETION` | 提交或回滾後都跑 | 清理類 |

機制一句話：`publishEvent()` 時若有進行中的交易，事件不會立刻分發，而是**掛成一個 `TransactionSynchronization`**，等交易走到對應 phase 才呼叫 listener。注意兩件事沒變：**還是同一條 thread、還是同步**——它推遲的是時機，不是執行模型（要真非同步，疊 `@Async`，[上上篇](async-under-the-hood.md)的全部注意事項適用）。

### 地雷一：沒有交易時，事件被默默丟棄

`@TransactionalEventListener` 的預設行為：發佈當下**沒有進行中的交易 → 這個 listener 直接不執行**，不炸、不警告（實測場景C）。兩個經典中招現場：

- **單元測試**沒開交易——「listener 在正式環境有跑，測試裡怎麼都不觸發」
- 呼叫鏈某層**忘了 `@Transactional`**——事件無聲蒸發

要「沒交易就立刻執行」的語意，明說：`@TransactionalEventListener(fallbackExecution = true)`。

### 地雷二：AFTER_COMMIT 裡寫資料庫——你騎在一具屍體上

AFTER_COMMIT 執行時，交易已完結、**但連線還綁在 thread 上**（實測：listener 內查連線 `autocommit = false`）。此時直接用 `JdbcTemplate` 寫入，等於把 SQL 搭在一具已完結交易的屍體上——結局是**實作細節的意外，不是契約**。

我們的實測劇情值得完整記錄：預期「寫入會無聲消失」（江湖著名說法），結果 `audit_direct = 1`——**落地了**。追下去發現落地的原因：交易清理階段 `DataSourceTransactionManager` 會把連線的 autocommit 恢復為 true，而 JDBC 規範明定**交易中途切換 autocommit 會隱式提交當前交易**——那筆寫入是被「打掃時順手」提交的。這不是保障：

- 沒有交易語意——不會回滾、和任何業務操作都不原子
- 換一個 DataSource／連線池／ORM，結局就換一種（JPA 場景以「無聲蒸發」聞名：官方 javadoc 明文警告 interaction with transactional resources、要求用新交易——JPA 版本文未實測，第 05 章再驗）

契約內的寫法只有一種：**自己開新交易**（實測 `audit_saved = 1`）：

```java
@TransactionalEventListener
void afterCommit(OrderPlaced e) {
    requiresNewTxTemplate.executeWithoutResult(s ->   // ✅ REQUIRES_NEW
            jdbc.update("insert into audit values (?)", e.id()));
}
// 或者：listener 方法標 @Transactional(propagation = Propagation.REQUIRES_NEW)
```

### 地雷三：AFTER_COMMIT 的例外會被吞掉

listener 在 AFTER_COMMIT 丟例外會怎樣？實測（場景D）：**呼叫端完全無感**——`place()` 正常返回、交易已提交，例外被 `TransactionSynchronizationUtils` 接住、只留一條 SEVERE log：

```
SEVERE: TransactionSynchronization.afterCompletion threw exception
```

這個設計是對的（交易都提交了，不能再假裝失敗），但推論很重要：**AFTER_COMMIT listener 的失敗沒有任何人負責重試**——log 一條就結案。「commit 成功但通知丟了」的窗口是真實存在的；要保證送達，得把事件先寫進 DB 同一筆交易（outbox pattern），那是訊息可靠性的領域，超出本 repo 範圍，點到為止。

### 回收 @Cacheable 那條線

「交易成功才清快取」的正解形狀，就是把 `@CacheEvict` 的動作搬進 AFTER_COMMIT listener——或更直接：讓更新方法發佈領域事件，evict 掛在 `@TransactionalEventListener` 上。時機正確性交給框架，而不是靠「evict 寫在方法最後一行」的排版錯覺。

## 實際案例

驗證環境：spring-context／spring-jdbc 6.2.8、H2 2.3.232、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[TxEventTiming.java](examples/TxEventTiming.java)

### 場景A：成功提交——兩種 listener 的時序，與「屍體上的寫入」

```
① 交易內：insert A001 完成（尚未 commit）
② @EventListener 立刻收到（交易中？true），此刻查 orders = 1（未提交的也看得到）
④ publishEvent 返回，交易繼續
⑤ place() 方法體結束 → proxy 準備 commit
⑥ AFTER_COMMIT 收到 A001（commit 之後才輪到我）
   此刻連線 autocommit = false——還綁在已完結交易的連線上
place() 返回
audit_direct 筆數 = 1  ← 直接寫入「意外落地」（靠 autocommit 恢復的隱式 commit）
audit_saved  筆數 = 1  ← REQUIRES_NEW 的寫入（契約內）
```

`②` 在 commit 前、`⑥` 在 commit 後——同一個事件，兩種 listener 的世界觀完全不同。`autocommit = false` 那行是「屍體」的驗屍報告。

### 場景B：回滾——事件跟著作廢

```
② @EventListener 立刻收到（交易中？true），此刻查 orders = 2
AFTER_ROLLBACK 收到 B002（補償／告警的入口）
呼叫端接到例外：下單失敗，交易回滾
orders 表筆數 = 1（B002 已回滾）
```

普通 listener 已經對著 B002 行動過了（它看到 count=2 的幻象）；`AFTER_COMMIT` 正確地保持沉默；`AFTER_ROLLBACK` 拿到補償的入場券。

### 場景C：交易外發佈——無聲蒸發

```
② @EventListener 立刻收到（交易中？false），此刻查 orders = 1
交易外 publishEvent 返回
（AFTER_COMMIT / AFTER_ROLLBACK 都沒出聲——沒有交易可掛，事件被默默丟棄）
```

普通 listener 照常工作，`@TransactionalEventListener` 全體缺席——測試裡「怎麼都不觸發」的謎底通常就是這個。

### 場景D：listener 丟例外——呼叫端無感

```
⑥ AFTER_COMMIT 收到 D004（commit 之後才輪到我）
SEVERE: TransactionSynchronization.afterCompletion threw exception
place() 正常返回——listener 的例外沒有炸回呼叫端
orders 表筆數 = 2（D004 已提交）
```

對比事件篇的同步 listener（例外炸回發佈者、交易回滾）——跨過 commit 這條線之後，例外的世界規則整個翻面：**之前你炸我陪葬，之後你炸我裝沒事**。

## 技術優缺點

### @TransactionalEventListener 買到什麼

- **「成功後才做」宣告化**：寄信、清快取、通知下游的時機正確性交給框架——比「把程式碼排在方法最後一行」可靠一個量級
- **回滾感知**：`AFTER_ROLLBACK` 給了補償邏輯一個正規入口，不用在 catch 區塊裡手工編排
- **與既有事件模型無縫**：發佈端一行不改（同一個 `publishEvent`），時機語意由訂閱端自己宣告——解耦做得很徹底

### 代價與地雷

- **無交易＝無聲丟棄**（預設）——測試與「忘了 @Transactional」兩大中招現場；`fallbackExecution = true` 是開關
- **AFTER_COMMIT 是資源的黃昏地帶**：連線還綁著、交易已死——直接寫 DB 的結局是實作細節（我們實測到「意外落地」，JPA 場景以蒸發聞名）；**寫入一律 REQUIRES_NEW**
- **例外被吞、無人重試**：commit 後的失敗只留 SEVERE——需要送達保證的場景要上 outbox，這個註解不是訊息佇列
- **仍是同步**：listener 執行期間連線尚未歸還（在 afterCompletion 清理前）——AFTER_COMMIT 裡做慢操作，高併發下是連線池殺手；慢工作疊 `@Async` 出去

## 小結

- 交易內發佈的事件是**候選事實**：普通 `@EventListener` 在交易內立即消費（實測看得到未提交資料）、回滾時已消費的動作收不回來
- `@TransactionalEventListener` 把事件掛上交易同步器，**AFTER_COMMIT（預設）／AFTER_ROLLBACK** 等四個 phase 對應四種時機——同 thread、仍同步
- **沒有交易時預設直接丟棄**（實測缺席）——測試環境的經典謎題；`fallbackExecution = true` 改行為
- **AFTER_COMMIT 裡寫 DB 要 REQUIRES_NEW**：直接寫是搭在已完結交易的連線上（實測 `autocommit=false`），落地與否是實作意外不是契約
- **AFTER_COMMIT 的例外被吞掉**（實測 SEVERE、呼叫端無感）——失敗無人重試，送達保證要另尋 outbox

到這裡，「proxy 宣告式家族」的三個成員（@Async、@Cacheable、@TransactionalEventListener）都解剖完了。下一個成員把驗證邏輯搬出 Controller：`@Validated` 讓**任何** bean 的方法參數都能掛 constraint——但它和 MVC 的 `@Valid` 是兩套機制，混淆的代價是「驗證悄悄沒跑」：見規劃中的〈@Validated：方法級驗證〉。

## 常見面試題

1. 「下單成功後寄信」用 `@EventListener` 寫有什麼問題？（提示：交易內消費候選事實——未提交可見、回滾收不回）
2. `@TransactionalEventListener` 在沒有交易時會怎樣？（提示：預設無聲丟棄；`fallbackExecution`；測試環境經典坑）
3. 為什麼 AFTER_COMMIT listener 裡寫資料庫要開新交易？（提示：交易已完結、連線還綁著——直接寫的結局是實作細節，不受交易保障）

## 延伸閱讀

- [Spring Framework 官方文件：Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) — 四個 phase 與 fallbackExecution 的官方語意
- [@TransactionalEventListener javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html) — 對「interact with transactional resources」的明文警告
- [java.sql.Connection#setAutoCommit javadoc](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html#setAutoCommit(boolean)) — 「切換 autocommit 隱式提交當前交易」的規範出處（本篇「意外落地」的謎底）
