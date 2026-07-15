# @Async：執行緒池在哪、例外去了哪

## 前言

[第 01 章的事件篇](../01-core-container/application-events.md)結尾留了一句沒拆的 INFO：

```
INFO: No task executor bean found for async processing
```

給方法加上 `@Async` 的那一刻，你其實簽了三張空白支票：**誰**來跑這個方法？方法裡丟出的**例外**去了哪（log 沒有、呼叫端也沒有）？以及——它**真的**非同步了嗎？這三題的答案都不在 `@Async` 這四個字母裡，在它背後的 proxy 與 `TaskExecutor`。

這也是第 02 章的開章定調：[knowledge-java 用 @Transactional 講透了 proxy 與失效](../../knowledge-java/03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)——本章把同一套機制推廣到**所有**「一個註解換一項基礎設施」的功能。`@Async` 是第一個病例：它得的是同一種病，多帶兩個自己的併發症。

## 技術背景

### 先破除誤解：@Async 不是「開新執行緒」

`@Async` 的機制是「**proxy 攔截 ＋ 把方法呼叫包成任務丟進 `TaskExecutor`**」。proxy 從哪來你已經知道：[after-init 站的掉包](../01-core-container/bean-post-processor.md)（這次的工人叫 `AsyncAnnotationBeanPostProcessor`）。這個定位直接推出兩件事：

1. **失效清單與 `@Transactional` 完全共用**（self-invocation、非 public、忘了 `@EnableAsync`…，見下）
2. 非同步的品質取決於**那個 `TaskExecutor` 是誰**——這正是三張空白支票的第一張

### 第一張支票：誰在跑

`@Async` 找 executor 的順序（`AsyncExecutionAspectSupport`，6.2.x）：

```
① 容器裡唯一的 TaskExecutor bean
② 沒有唯一的？找名字叫 "taskExecutor" 的 Executor bean
③ 都沒有 → fallback：SimpleAsyncTaskExecutor
     ＝ 每個任務開一條全新 thread、用完即棄、數量無上限（實測見案例一）
```

那句 INFO 就是「正在走 ③」的自白。Spring Boot 專案通常無感——自動配置給了一顆 `applicationTaskExecutor`（`ThreadPoolTaskExecutor`）；**plain Spring、自組測試環境、老專案**才會踩進 fallback，然後在高流量那天變成 thread 炸彈。

配置的正解是實作 `AsyncConfigurer`——executor 和例外處理器**一起**給：

```java
@EnableAsync
@Configuration
class AsyncConfig implements AsyncConfigurer {
    @Override public Executor getAsyncExecutor() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);           // 池參數的權衡見 knowledge-java 07 章
        pool.setThreadNamePrefix("async-");
        pool.initialize();
        return pool;
    }
    @Override public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> { /* 告警，不要只 log */ };
    }
}
```

執行緒池參數怎麼估不是 Spring 的事，是 [knowledge-java 07 章](../../knowledge-java/07-concurrency/executorservice-and-thread-pools.md)的事——這裡只補 Spring 層的差異：包成 **bean** 的 `ThreadPoolTaskExecutor` 會掛進容器生命週期，關機時被優雅 shutdown。

### 第二張支票：例外去了哪——回傳型別決定命運

| 回傳型別 | 例外去向 | 呼叫端感知 |
|---|---|---|
| `void` | `AsyncUncaughtExceptionHandler`（預設只印一條 SEVERE log） | **永遠無感** |
| `CompletableFuture` / `Future` | 存進 future，`get()`／`join()` 時浮出 | 有——前提是你真的去 join |
| 其他（`String`…） | **6.x：呼叫當下直接炸**；5.x：安靜回 null（雙版本實測） | 立即（6.x） |

`void` 那格是本篇最大的地雷：例外**不會**傳回呼叫端（人早走了），也**不會**讓任何交易回滾，預設 handler 只默默寫一條 log。「批次通知悄悄全滅、沒有任何告警」的事故就住在這一格。

### 第三張支票：它真的非同步了嗎——失效清單

機制是 proxy，所以病也是 proxy 的病，[knowledge-java 的深入解剖](../../knowledge-java/03-spring-to-spring-boot/deep-transactional-self-invocation.md)整篇適用：

| 失效情境 | 原因 |
|---|---|
| 同類別內 `this.inner()` | 走 `this` 不走 proxy（實測見案例四） |
| 非 public／final 方法 | CGLIB 覆寫不了 |
| 忘了 `@EnableAsync` | 沒人僱用製 proxy 的工人 |
| 自己 `new` 的物件 | 不在容器裡，沒被掉包 |

這張表值得記牢——它會在本章反覆出現：`@Cacheable`、`@Retryable`、`@Validated` 全是同一張。

### 附帶損失：ThreadLocal 全家不跟隨

換了 thread，所有掛在 `ThreadLocal` 上的東西歸零：**交易**（async 方法裡的 `@Transactional` 是全新交易，不跟外面同生共死）、**SecurityContext**（誰登入的？不知道——見規劃中的第 06 章深入文）、**MDC**（log 追蹤 ID 斷鏈）。`@Async` 劃下的不只是執行緒邊界，是一整條上下文斷層線。

## 實際案例

驗證環境：spring-context 6.2.8（對照組 5.3.39）、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[AsyncAnatomy.java](examples/AsyncAnatomy.java)

### 案例一：兩種 executor 的指紋

```
=== 沒配 executor ===
INFO: No task executor bean found for async processing: …
  [SimpleAsyncTaskExecutor-1] 任務 1 執行中
  [SimpleAsyncTaskExecutor-2] 任務 2 執行中
  [SimpleAsyncTaskExecutor-3] 任務 3 執行中

=== 配了 ThreadPoolTaskExecutor（core 2）===
  [async-1] 任務 1 執行中
  [async-2] 任務 2 執行中
  [async-1] 任務 3 執行中      ← 重用了
```

三次呼叫**間隔 80ms、前一個早就跑完**，fallback 仍然開了三條全新 thread——「不重用」不是負載高才發生，是它的本性。thread 名就是排查指紋：看到 `SimpleAsyncTaskExecutor-`，等於看到那張沒簽名的支票。

### 案例二：void 例外的黑洞

```
  [main] voidBoom() 呼叫端：沒接到任何例外，繼續往下走
SEVERE: Unexpected exception occurred invoking async method: void AsyncAnatomy$Worker.voidBoom()
java.lang.IllegalStateException: void 方法裡的例外
```

呼叫端的 try-catch 是擺設（人在例外發生前就離場了）；預設收屍人 `SimpleAsyncUncaughtExceptionHandler` 只印 SEVERE。自訂 handler 之後（案例二b）至少能接上告警——但**能拿回例外的只有 `CompletableFuture` 路線**。

### 案例三：回傳值的真相（雙版本對照）

```
6.2.8：@Async String badReturn()：呼叫當下炸出
       Invalid return type for async method (only Future and void supported): class java.lang.String
5.3.39：@Async String 回傳：null

CompletableFuture 正常路徑：算好了（by async-1）
CompletableFuture 例外路徑：join() 丟出 CompletionException（cause: 計算失敗）
```

`@Async` 方法的合法回傳只有 `void` 和 `Future` 家族。寫了 `String` 會怎樣？**6.x 在「呼叫當下」炸**（注意：啟動時不炸——這是 runtime 檢查，測試沒蓋到的路徑會活到正式環境才爆）；**5.x 更陰，安靜回 null**——網路上「@Async 方法回傳 null」的老文章說的就是它。順帶一提，6.x 的錯誤 stack 裡能看到 `Worker$$SpringCGLIB$$0.badReturn(<generated>)`——proxy 本人出鏡。

### 案例四：self-invocation，無聲失效

```
  [main] outer() 開始，呼叫 this.inner()…
  [main] inner() 執行中（如果有換 thread 才算非同步）
```

`inner()` 標了 `@Async`，卻在 `[main]` 上執行——`this.inner()` 繞過了 proxy。沒有錯誤、沒有警告，只是**同步地跑完了**。修法排序照 [knowledge-java 那篇](../../knowledge-java/03-spring-to-spring-boot/deep-transactional-self-invocation.md)：拆類別 ＞ 注入自己的 proxy ＞ `AopContext`。

## 技術優缺點

### @Async 買到什麼

- **樣板蒸發**：「建 executor、submit、管 future」的例行公事變成一個註解——非同步化一個通知方法從十行變零行
- **與容器同生共死**：bean 形式的 executor 隨容器優雅關閉，比手工 `ExecutorService` 少一類洩漏
- **宣告式的一致性**：和 `@Transactional`、`@Cacheable` 同一套心智模型——學一次，整個家族通用

### 代價與地雷

- **預設值是陷阱**：fallback executor 每任務一條新 thread、無上限——`@Async` 是少數「不配置比配置危險」的功能
- **void 是例外黑洞**：不炸、不滾、不告警——需要結果或需要知道失敗的，一律走 `CompletableFuture`
- **上下文斷層**：交易、SecurityContext、MDC 全部不跟隨——跨過 `@Async` 邊界的每一項 ThreadLocal 資產都要重新盤點
- **proxy 失效全家福適用**，而且失效是**無聲的**（案例四）——同步跑完的 `@Async` 比炸掉的更難發現

## 小結

- `@Async` ＝ **proxy 攔截＋丟進 `TaskExecutor`**——不是「開執行緒」；`@Transactional` 的失效清單整張適用（self-invocation 實測無聲失效）
- 沒配 executor 就是 `SimpleAsyncTaskExecutor`：**每任務全新 thread、不重用、無上限**（實測 -1/-2/-3）——那句 INFO 是警訊；正解是 `AsyncConfigurer` 或 `ThreadPoolTaskExecutor` bean
- **void 方法的例外進 `AsyncUncaughtExceptionHandler`**（預設只 log SEVERE），呼叫端永遠無感——要拿到例外只有 `CompletableFuture`＋`join`／`get` 一條路
- 合法回傳只有 `void` 與 `Future` 家族——**6.x 呼叫當下炸、5.x 安靜回 null**（雙版本實測），讀舊文章要對版本
- `@Async` 是一條 **ThreadLocal 斷層線**：交易、SecurityContext、MDC 都不過界

同一套 proxy，換一個攔截邏輯就是另一項基礎設施：把「方法結果存起來、下次直接還你」宣告化，就是 `@Cacheable`——它的 key 是怎麼生的、什麼時候「同參數卻 cache miss」？見規劃中的〈@Cacheable：快取抽象與它的 key 地雷〉。

## 常見面試題

1. `@Async` 方法丟出例外，呼叫端接得到嗎？（提示：回傳型別決定——void 進 handler、`Future` 家族進 `get()`）
2. 沒有配置執行緒池時 `@Async` 用什麼跑？有什麼風險？（提示：`SimpleAsyncTaskExecutor`——每任務新 thread、無上限、不重用）
3. `@Async` 什麼情況下會無聲失效？和 `@Transactional` 的失效有什麼關係？（提示：同一種 proxy 病——self-invocation、非 public、忘了 `@EnableAsync`）

## 延伸閱讀

- [Spring Framework 官方文件：Annotation Support for Scheduling and Asynchronous Execution](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support) — `@Async` 與 `@EnableAsync` 的官方語意
- [AsyncExecutionAspectSupport 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-aop/src/main/java/org/springframework/aop/interceptor/AsyncExecutionAspectSupport.java) — executor 查找順序與回傳型別檢查的第一手依據
- [Spring Boot 官方文件：Task Execution and Scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html) — Boot 的 `applicationTaskExecutor` 自動配置與 `spring.task.execution.*` 屬性
