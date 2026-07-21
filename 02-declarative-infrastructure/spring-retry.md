# Spring Retry：@Retryable 與退避策略

## 前言

呼叫核心系統偶發 timeout、第三方 API 偶爾回 503、資料庫偶爾死鎖——這類失敗的共同點是：**再試一次多半就好了**。於是專案裡長出各種手工重試：`for` 迴圈包 try-catch、`Thread.sleep` 硬等、計數器變數散落一地。Spring Retry 把這坨樣板收成一個註解：`@Retryable`。

它是本章 proxy 家族的第五位成員（注意：它住在**獨立專案** `org.springframework.retry`，不在 Framework 本體；Framework 7.0 已把 resilience 能力收編進核心，本 repo 基線 6.x 仍用 spring-retry）。[上一篇](method-validation.md)的驗證擋「不該進來的」；重試處理「進來了、也合法，只是**暫時**失敗的」——而「暫時」兩個字，正是這個註解最容易被用錯的地方：實測會看到，**預設配置會把「餘額不足」重試三次**。

## 技術背景

### 先破除誤解：「失敗就重試」——重試只對暫時性失敗有意義

| 失敗類型 | 例子 | 重試？ |
|---|---|---|
| **暫時性（transient）** | 網路抖動、連線逾時、DB 死鎖、限流 429、下游 503 | ✅ 重試的存在意義 |
| **永久性（permanent）** | 餘額不足、驗證失敗、404、權限不足 | ❌ 試一萬次也是這個結果——重試只是拖慢失敗、拉高下游負載 |

還有一條紅線壓在所有重試之上：**重試的前提是冪等**。「扣款 timeout」可能是「扣成功了但回應沒回來」——盲目重試就是重複扣款。框架不會幫你判斷這件事，冪等設計（去重鍵、條件更新）是你上重試之前的功課。

### 基本盤：@EnableRetry ＋ @Retryable

```java
@EnableRetry                        // 僱工人（又是 proxy——家族病第四次應驗，見案例四）
@Configuration class Cfg { ... }

@Retryable(
    retryFor = TransientApiException.class,          // ★ 永遠顯式指定——預設是「所有例外都重試」
    maxAttempts = 4,                                 // 預設 3
    backoff = @Backoff(delay = 100, multiplier = 2)) // 指數退避
String fetch() { ... }
```

依賴是 `spring-retry`＋`aspectjweaver`。這裡記第二顆親踩的依賴地雷（和[上一篇的 expressly](method-validation.md) 同款病）：**spring-retry 2.0.10 的傳遞依賴是 spring-* 6.0.23**——不把 Spring 全家明式釘回你的版本，啟動就是 `NoSuchMethodError`／`NoClassDefFoundError` 兩連炸。教訓通用：**引入任何「Spring 週邊專案」時，先查它拖了哪一代 Spring 本體**。

### Backoff：重試要有禮貌

失敗立刻重試＝對一個已經在掙扎的下游連續補刀。`@Backoff` 的三件套：`delay`（首次等待）、`multiplier`（指數放大）、`maxDelay`（封頂）。實測節奏（`delay=100, multiplier=2`）：

```
第 1 次 → （失敗，等 ~100ms）→ 第 2 次（實測距上次 107ms）→（等 ~200ms）→ 第 3 次（205ms）→ 成功
```

大量實例同時重試同一個下游時，再加 `random = true`（抖動）打散節奏，避免 thundering herd。

### @Recover：降級出口——以及它改變例外行為的副作用

重試耗盡後想優雅降級（回快取值、回預設值、進補償流程），掛 `@Recover`。匹配規則：**第一個參數是例外型別、其餘參數與原方法對齊、回傳型別一致**：

```java
@Retryable(maxAttempts = 3)
String alwaysDown() { ... }

@Recover
String downFallback(IllegalStateException e) { return "降級回應"; }   // 型別三對齊
```

但這裡藏著本篇實測挖出的最深一顆雷——**`@Recover` 的存在會改變整個類別的例外行為**，三種情境（全部實測）：

| 類別內的 @Recover 狀態 | 重試耗盡後呼叫端拿到 |
|---|---|
| 完全沒有 | **原始例外**原樣拋回（實測 `Boom: 餘額不足`） |
| 有、且簽名匹配 | 降級回傳值（例外被消化） |
| 有、但**不匹配** | `ExhaustedRetryException: Cannot locate recovery method`——原始例外**降級成 cause** |

第三格的殺傷力在於：你只是給 A 方法加了個 `@Recover`，**B 方法的呼叫端 `catch (InsufficientBalanceException)` 就再也接不到了**（例外被換型）。上游按原始型別寫的錯誤處理整排失效——而且連 `noRetryFor` 排除掉的「立即失敗」也走這條換型路徑（實測見案例三下半）。

## 實際案例

驗證環境：spring-context 6.2.8＋spring-retry 2.0.10（Spring 全家明式釘版）、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[RetryDrill.java](examples/RetryDrill.java)

### 案例一：抖兩次就好——重試到成功＋退避節奏

```
fetch 第 1 次嘗試（距上次 -）
fetch 第 2 次嘗試（距上次 107 ms）
fetch 第 3 次嘗試（距上次 205 ms）
呼叫端拿到：資料到手（第 3 次成功）
```

呼叫端毫無感知——它只看到一次成功的方法呼叫。107→205 的間隔就是 `delay=100, multiplier=2` 的指數退避在走。

### 案例二：一直掛——@Recover 降級

```
alwaysDown 第 1 / 2 / 3 次嘗試
呼叫端拿到：降級回應（@Recover 接手：下游服務掛了）
```

三次耗盡、例外被 `@Recover` 消化、呼叫端拿到降級值——這是重試＋降級的理想形狀。

### 案例三：預設什麼都重試——業務失敗的三連擊

```
charge 執行第 1 / 2 / 3 次
呼叫端接到：ExhaustedRetryException（cause: 餘額不足）——扣款方法已執行 3 次！

chargeSafe 執行第 1 次
noRetryFor 版接到：ExhaustedRetryException（Cannot locate recovery method）——方法只執行 1 次
```

上半：`@Retryable` 不加條件＝「餘額不足」也重試三次——結果不會變，只是把失敗拖慢三倍、對帳 log 多兩筆疑案。下半：`noRetryFor` 讓方法只執行一次（正解），**但注意例外還是被換型了**——因為類別裡有別的 `@Recover`（上面表格第三格）。兩顆雷疊在同一個輸出裡。

### 案例四：self-invocation——重試無聲消失

```
內部方法直接呼叫 this.fetch()…
fetch 第 1 次嘗試（距上次 -）
第一次失敗就直接炸出：網路抖動（fetch 只執行了 1 次，沒有重試）
```

家族病第四次應驗，症狀照舊：不炸配置錯誤、只是**安靜地不重試**。

## 技術優缺點

### @Retryable 買到什麼

- **樣板蒸發**：for＋sleep＋計數器＋「第幾次了」的 log，全部收進一行註解——重試策略還變成看得見、可 review 的宣告
- **退避是一等公民**：指數退避＋抖動這種「手寫必偷懶」的細節，宣告式給到位
- **降級有正規出口**：`@Recover` 把「最後怎麼辦」從 catch 區塊的即興發揮變成簽名明確的方法

### 代價與地雷

- **預設 retry 一切**：不寫 `retryFor` 就是「所有例外都值得再來一次」——業務失敗三連擊實測為證；紀律：**永遠顯式指定暫時性例外**
- **例外會變形**：類別裡出現任何 `@Recover` 後，不匹配的失敗一律換型成 `ExhaustedRetryException`——上游的 catch 型別可能整排失效（實測）
- **冪等是你的責任**：框架看不出「timeout 的扣款其實成功了」——非冪等操作上重試等於自製重複交易
- **同步阻塞**：重試期間 thread 被佔著，退避越長越痛——高併發場景考慮改走佇列＋非同步補償
- **proxy 家族病**第四次＋**依賴版本地雷**（spring-retry 拖 6.0.23 親踩）

## 小結

- 重試只對**暫時性失敗**有意義，且前提是**冪等**——這兩個判斷框架都不會幫你做
- `@Retryable` 預設「所有例外、3 次」——**永遠顯式 `retryFor`**（實測：餘額不足被重試三次）
- `@Backoff` 指數退避實測節奏 107→205ms；大規模場景加 `random` 抖動
- `@Recover` 三對齊（例外、參數、回傳型別）；**它的存在會把類別內不匹配的失敗換型成 `ExhaustedRetryException`**（三情境實測）——上游 catch 要防
- self-invocation 第四次無聲失效；spring-retry 的傳遞依賴是舊版 Spring，**引週邊專案先釘版本**

到這裡，本章五位家族成員（@Async、@Cacheable、@TransactionalEventListener、@Validated、@Retryable）全部解剖完畢，每一篇都說了同一句話：「又是 proxy」。章末的深入文就來清算這句話：proxy 到底**何時**、**被誰**、**怎麼**裝上的？JDK 與 CGLIB 怎麼選？`@Configuration` 類別的 `$$SpringCGLIB$$` 又是哪一種？見規劃中的〈proxy 是何時、被誰裝上的：AbstractAutoProxyCreator 追讀〉。

## 常見面試題

1. 什麼樣的失敗適合重試？上重試之前要先確認什麼？（提示：暫時性 vs 永久性；冪等前提）
2. `@Retryable` 的預設行為有什麼陷阱？（提示：所有例外都重試、maxAttempts=3——業務例外三連擊）
3. `@Recover` 的匹配規則是什麼？加了它可能對其他方法造成什麼影響？（提示：例外／參數／回傳三對齊；不匹配的失敗換型成 `ExhaustedRetryException`）

## 延伸閱讀

- [Spring Retry 官方 README（GitHub）](https://github.com/spring-projects/spring-retry) — 註解全參數與 `RetryTemplate` 程式化用法
- [RetryTemplate javadoc](https://docs.spring.io/spring-retry/docs/api/current/org/springframework/retry/support/RetryTemplate.html) — 不想用 proxy 時的程式化入口（policy＋backoff 自由組合）
- [AnnotationAwareRetryOperationsInterceptor 原始碼](https://github.com/spring-projects/spring-retry/blob/main/src/main/java/org/springframework/retry/annotation/AnnotationAwareRetryOperationsInterceptor.java) — `@Retryable` 攔截器本人；`RecoverAnnotationRecoveryHandler` 是例外換型的出處
