# 事件機制：ApplicationEvent 與 @EventListener

## 前言

下單完成後要寄確認信、加會員點數、通知倉庫。最直覺的寫法是 `OrderService` 依序呼叫三個 service——然後每接一個新下游，就得回頭改一次下單主流程，直到它變成一張認識全公司的蜘蛛網。Spring 容器內建的事件機制就是為這個形狀準備的：**上游只宣布「發生了什麼」，誰關心誰處理**。

這套機制你其實見過：[啟動全景](container-startup-refresh.md)第 12 步的 `ContextRefreshedEvent` 就是容器發給自己的事件。本文講你**自己用**的部分——以及一個讓無數人踩坑的預設值：`@EventListener` 看起來像非同步的訊息系統，**但它預設是同步的**。寄信 listener 炸掉，你的下單會跟著失敗。

## 技術背景

### 先破除誤解：事件 ≠ 非同步

`publishEvent()` 的預設實作（`SimpleApplicationEventMulticaster`）就是**同一條 thread 上的一個 for 迴圈**，依序呼叫每個 listener。三個直接推論（全部實測見案例一）：

1. `publishEvent()` **返回之前，所有 listener 已經跑完**——它不是「丟出去就走」
2. **listener 的例外會炸回發佈者**，而且排在後面的 listener 被跳過——「解耦」只是程式碼上的，命運還綁在一起
3. 同一條 thread 意味著**同一個交易**——listener 失敗會 rollback 發佈者的交易。這既是 feature（資料一致）也是坑（寄信失敗退你的單），正確處理這條邊界的工具是 `@TransactionalEventListener`，見規劃中的〈@TransactionalEventListener：交易邊界上的事件〉（第 02 章）

### 基本盤：發佈與監聽

Spring 4.2 之後，事件可以是**任意物件**，`record` 是最理想的事件載體；發佈方注入 `ApplicationEventPublisher`（不要為了發事件注入整個 `ApplicationContext`——最小介面原則）：

```java
record OrderPlaced(String id, int amount) { }          // 事件＝不可變的事實陳述

@Service
class Checkout {
    private final ApplicationEventPublisher publisher;
    Checkout(ApplicationEventPublisher publisher) { this.publisher = publisher; }

    void place(String id, int amount) {
        // ...下單主流程...
        publisher.publishEvent(new OrderPlaced(id, amount));   // 宣布事實，不點名收件人
    }
}

@Component
class MailListener {
    @Order(1) @EventListener                            // 訂閱條件＝參數型別
    void on(OrderPlaced e) { /* 寄信 */ }
}
```

幾個機制點：

- **訂閱條件就是參數型別**——分發按型別匹配，上游下游互不 import
- `@EventListener` 的掃描者是 `EventListenerMethodProcessor`——[又一個「不是容器本能」](bean-post-processor.md)的功能
- 同一事件多個 listener 時，`@Order` 決定順序（它管排序不管挑選，[和集合注入同一條規則](dependency-resolution.md)）

### condition 篩選，與一個會炸的包裝細節

```java
@EventListener(condition = "#root.args[0].amount() > 1000")   // 只聽大額訂單
void on(OrderPlaced e) { ... }
```

condition 是 SpEL，但這裡藏著一個實測炸過的坑：**不繼承 `ApplicationEvent` 的普通物件事件，會被容器包進 `PayloadApplicationEvent`**——SpEL 裡的 `#root.event` 指向包裝、不是你的 record，直接寫 `#root.event.amount()` 會得到：

```
SpelEvaluationException: EL1004E: Method call: Method amount() cannot be found
on type org.springframework.context.PayloadApplicationEvent
```

解法：用 `#root.args[0]`（listener 方法的第一個參數），或編譯帶 `-parameters` 後用參數名 `#e`。

### 要非同步，明說：@Async

```java
@Configuration @EnableAsync class AsyncConfig { }

@Async @EventListener          // 這樣才會離開發佈者的 thread
void on(OrderPlaced e) { ... }
```

實測（案例三）：加上 `@Async` 後主流程 22 ms 就返回，慢速稽核在 `SimpleAsyncTaskExecutor-1` 上自己跑。但啟動 log 同時給了一句值得劃線的 INFO：

```
INFO: No task executor bean found for async processing:
no bean of type TaskExecutor and no bean named 'taskExecutor' either
```

沒配 executor 時，Framework 退回 `SimpleAsyncTaskExecutor`——**每個任務開一條新 thread、不設上限**。這句 INFO 是 `@Async` 整包地雷（executor 配置、例外去哪了、回傳值）的入口，本篇點到為止：見規劃中的〈@Async：執行緒池在哪、例外去了哪〉（第 02 章）。

### 事件 vs 直接呼叫：選擇的準繩

| 用事件 | 直接呼叫 |
|---|---|
| 下游「可有可無」、預期會**增生**（通知類、稽核類） | 下游是主流程的**必要步驟**（扣庫存） |
| 一對多廣播 | 需要**回傳值**參與後續邏輯 |
| 跨模組解耦，上游不該認識下游 | 順序與交易語意需要緊密可見 |

事件的解耦不是免費的——它把顯式呼叫變成**隱式控制流**，這筆帳記在優缺點段。

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action` 執行）。

▶ 可執行範例：[EventWire.java](examples/EventWire.java)

### 案例一：同步的三個證據

```
=== 事件預設是同步的 ===
  [main] 下單 A001（金額 500），publishEvent…
  [main] ① 寄出確認信：A001
  [main] ② 加會員點數：A001
  [main] publishEvent 返回，下單流程繼續

=== listener 的例外會炸回發佈者 ===
  [main] 下單 B002（金額 7000），publishEvent…
  [main] ① 寄出確認信：B002
  [main] ② 加會員點數：B002
  [main] place() 的呼叫端接到例外：點數系統故障（B002）——後面的 listener 沒跑
```

證據逐條對：全部發生在 **`[main]`**；listener 跑完 `publishEvent` 才返回；B002 金額 7000 **本該觸發**第④號大額 listener，卻因為 `@Order(3)` 的 listener 先炸而缺席——例外不只炸回發佈者，還讓後面的訂閱者集體失聯。事故劇本照著唸：「加點數失敗，導致**下單失敗**、確認信卻已寄出」——以為發了事件就解耦的人，會在這裡學到同步的意思。

### 案例二：condition 生效

```
  [main] 下單 C003（金額 5000），publishEvent…
  [main] ① 寄出確認信：C003
  [main] ② 加會員點數：C003
  [main] ④ 大額訂單通報主管：C003（金額 5000）
```

A001（500）時④缺席、C003（5000）時④出現——`#root.args[0].amount() > 1000` 在分發前就把小額事件濾掉了。

### 案例三：@Async 之後才是非同步

```
  [main] 下單 D004（金額 800），publishEvent…
  [main] publishEvent 返回，下單流程繼續
  [main] place() 返回，耗時 22 ms（稽核還在別條 thread 上跑）
  [SimpleAsyncTaskExecutor-1] （慢速）稽核完成：D004
```

thread 名字換了、主流程不再等待——但別忘了上面那句「No task executor bean found」的 INFO：這條 thread 是無上限地開出來的。

## 技術優缺點

### 事件機制買到什麼

- **開閉原則的教科書案例**：新增下游＝新增一個 listener，下單主流程零改動、甚至不用重新 review
- **一對多天然**：廣播就是它的原生語意，不用自己維護 observer 名單
- **篩選、排序、非同步都是宣告式**：condition、`@Order`、`@Async` 各管一件事，組合自由

### 代價與地雷

- **隱式控制流**：「這個事件誰在聽？」IDE 給不出完整答案（find usages 只能找到型別引用）——事件用得越多，系統行為離原始碼越遠，這和[動態註冊 bean 的代價](bean-definition-and-bfpp.md)是同一種稅
- **同步預設的兩面**：例外連坐、共享交易——不知道這件事的人會把「可有可無的通知」寫成「拖垮主流程的炸彈」
- **預設 async executor 不設限**：忘了配 executor 的 `@Async` 在高流量下就是 thread 洩漏
- **事件鏈失控**：listener 裡再發事件，幾層之後沒人能畫出完整流程圖——事件適合「扇出」，不適合當流程引擎

## 小結

- `@EventListener` **預設同步**：同一條 thread、`publishEvent` 返回前全部跑完、例外炸回發佈者且後面的 listener 被跳過（實測三個證據俱在）
- 同步＝同交易——「listener 失敗 rollback 發佈者」的邊界問題，解法在 `@TransactionalEventListener`（第 02 章）
- 事件可以是任意物件（record 最佳）；訂閱條件＝參數型別；condition 用 SpEL 篩選——注意 **`PayloadApplicationEvent` 包裝坑**（`#root.args[0]` 才是本體）
- 非同步要明說（`@EnableAsync`＋`@Async`）；預設 executor **每任務開一條 thread 不設限**——那句 INFO 是警訊不是雜訊
- 事件適合「可有可無、會增生」的下游；主流程的必要步驟**直接呼叫**——解耦的代價是隱式控制流

到這裡，第 01 章的 🔰 主軌走完了：容器怎麼蓋（refresh）、圖怎麼畫（BeanDefinition）、工人怎麼加工（BPP）、依賴怎麼裁決、值哪來（Environment）、消息怎麼傳（事件）。但整章刻意迴避了一題：兩顆 singleton **互相** `@Autowired`——先有雞還是先有蛋？容器為什麼（有時）沒炸？答案藏在三層快取裡：見規劃中的〈循環依賴與三級快取〉。

## 常見面試題

1. `@EventListener` 預設是同步還是非同步？這帶來哪些後果？（提示：同 thread、例外炸回發佈者、同交易）
2. 想讓 listener 只處理特定事件怎麼做？（提示：參數型別＋condition SpEL；record 事件的 `PayloadApplicationEvent` 包裝）
3. 怎麼避免事件處理拖慢主流程？有什麼要注意的？（提示：`@Async`；預設 executor 不設限、例外不再炸回）

## 延伸閱讀

- [Spring Framework 官方文件：Standard and Custom Events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-events) — 事件機制官方全貌（含 condition 可用變數表）
- [SimpleApplicationEventMulticaster 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/context/event/SimpleApplicationEventMulticaster.java) — 「同步 for 迴圈」的第一手證據
- [@TransactionalEventListener javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html) — 交易邊界事件的官方答案（本 repo 第 02 章展開）
