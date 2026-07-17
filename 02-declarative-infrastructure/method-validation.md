# @Validated：方法級驗證

## 前言

[knowledge-java 的驗證篇](../../knowledge-java/03-spring-to-spring-boot/exception-handling-and-validation.md)把 MVC 世界的驗證講完了：`@Valid @RequestBody` 進來、`MethodArgumentNotValidException` 出去、advice 統一變 400。但那道門只立在 Controller——**排程任務、事件 listener、MQ handler、別的 service** 呼叫你的 Service 時，誰來擋髒資料？

方法級驗證（method validation）就是把 constraint 帶出 Web 層、裝到**任何 bean 的方法門口**。本文從 knowledge-java 那篇的結尾接下去，講這第二套驗證機制——以及它最陰的特性：配置差一個註解，**驗證不是報錯，是無聲不驗**（實測：`@NotBlank` 掛在參數上，`to=""、amount=-5` 長驅直入）。

## 技術背景

### 先破除誤解：@Valid 和 @Validated 不是「差不多」——是兩套機制

| | MVC binding 驗證 | 方法級驗證（本篇） |
|---|---|---|
| 寫法 | `@Valid @RequestBody Foo foo` | 類別標 `@Validated`＋參數掛 constraint |
| 誰執行 | argument resolver（binding 階段） | **proxy**（`MethodValidationInterceptor`） |
| 失敗丟出 | `MethodArgumentNotValidException` | `ConstraintViolationException` |
| Web 預設下場 | 400（advice 好接） | **500**（沒人接的 RuntimeException） |
| 作用範圍 | 只有 Controller 方法 | 任何 bean 的任何 public 方法 |

同一個 `@NotBlank`，走哪套機制、炸哪種例外、回什麼狀態碼，完全是兩個世界——「統一例外處理要接兩種型別」的原因就在這裡。

### 機制：又是一個 BPP、又是一個 proxy

到這章第四篇，你應該能默寫這個句式了：方法級驗證的工人是 `MethodValidationPostProcessor`（一個 BPP，[宣告成 `static @Bean`](../01-core-container/container-startup-refresh.md)），它掃描標了 `@Validated` 的**類別**、裝上 proxy；每次方法呼叫先過 `MethodValidationInterceptor`——參數驗過才放行進本體，回傳值出來再驗一次。

環境需求：Boot 帶了 `spring-boot-starter-validation` 就自動配置；plain Spring 要自己註冊 BPP＋放 Bean Validation 實作（hibernate-validator）＋EL 實作。這裡記一個親踩的依賴地雷：**`expressly` 5.0.0 的 POM 夾帶 `spring-expression` 5.3.22**，不明式蓋回 6.x 就會在啟動時炸：

```
NoSuchMethodError: 'void org.springframework.expression.spel.SpelParserConfiguration.<init>(...)'
```

看到 Spring 內部類別的 `NoSuchMethodError`，第一反應永遠是「classpath 上混了兩代 Spring」——`mvn dependency:tree`（或 `jbang info classpath`）抓內鬼。

### 最大的雷：開關在「類別」上

`@Validated` 必須標在**類別**——因為「要不要裝 proxy」是 BPP 掃類別時的決定。於是出現這個致命組合：參數上的 constraint 寫得整整齊齊、類別忘了標 `@Validated`——

**全部是裝飾品。** 不報錯、不警告、每次呼叫都直接放行（實測見案例二）。比起炸掉，「看起來有驗」是更危險的狀態：程式碼審查時人人以為有防線。

### 兩個註解的分工：@Validated 開門、@Valid 帶路

物件參數想往**內部欄位**驗（cascade），要在參數上標 `@Valid`：

```java
@Validated
class TransferService {
    void pay(@Valid Payee payee) { ... }      // ✅ 往 Payee 內部的 @NotBlank/@Pattern 驗下去
    void payNoCascade(Payee payee) { ... }    // ❌ 物件本身非 null 就過——內部欄位不看
}
```

分工記法：**`@Validated`（Spring 的）＝類別級開關（附贈 groups 分組能力）；`@Valid`（標準的）＝cascade 標記**。實測（案例三）：同一顆全空的 `Payee`，有 `@Valid` 擋下兩條違規、沒 `@Valid` 直接進了方法。

### 回傳值也能驗

```java
@NotNull String findAccount(String id) { ... }   // 資料層失手回 null → 出門被攔
```

實測（案例四）擋下的訊息很直白：`findAccount.<return value>: must not be null`。這是對「下游失手」的最後防線——比 NPE 在三層外爆炸好排查得多。

### 失效清單，第三次發作

proxy 家族病照抄：**self-invocation 驗證無聲跳過**（實測案例五：`this.transfer("", -99)` 順利執行）、非 public 不攔、忘了註冊 BPP 全體停擺。[那張表](async-under-the-hood.md)第三次應驗——這正是它值得背下來的原因。

版本註記：Spring Framework 6.1 起，**Controller 的方法級驗證內建進了 Web 層**（不再靠這裡的 proxy，失敗丟 `HandlerMethodValidationException`）——Web 端的細節留給第 04 章。

## 實際案例

驗證環境：spring-context 6.2.8、hibernate-validator 8.0.1、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[ValidationGate.java](examples/ValidationGate.java)

### 案例一＆二：一字之差，天壤之別

```
=== 有 @Validated，非法參數進不了門 ===
執行轉帳：to=A123、amount=500
擋下：transfer.amount: must be greater than or equal to 1, transfer.to: must not be blank

=== 忘了 @Validated——同樣的 constraint 無聲不驗 ===
執行轉帳（沒人擋）：to=、amount=-5
```

兩個類別的方法簽名**一字不差**，差別只在類別上有沒有 `@Validated`。負數金額的轉帳就這樣執行了——這一格輸出值得貼在團隊 code review 指南裡。

### 案例三：cascade 要 @Valid 帶路

```
擋下：pay.payee.account: must match "\d{10,16}", pay.payee.name: must not be blank
付款給「」——沒有 @Valid，物件內欄位不驗
```

### 案例四：回傳值的最後防線

```
擋下：findAccount.<return value>: must not be null
```

### 案例五：self-invocation，第三次

```
outer() 直接呼叫 this.transfer("", -99)…
執行轉帳：to=、amount=-99
```

同一個方法、同一組 constraint，從 `this` 進來就是沒人攔。`@Async` 靜靜變同步、`@Cacheable` 靜靜不快取、`@Validated` 靜靜不驗——同一種病的第三種症狀。

## 技術優缺點

### 方法級驗證買到什麼

- **防線離開 Controller**：排程、事件、內部呼叫，所有入口同一道門——「只有 HTTP 進來的才被驗」這個隱形假設被拆掉
- **constraint 即文件**：`@Min(1) long amount` 把前置條件寫在簽名上，比 Javadoc 的 "amount must be positive" 多了強制力
- **回傳值驗證**：對下游（資料層、外部 API 包裝）的契約檢查，NPE 提前到源頭現形

### 代價與地雷

- **失效是無聲的**：忘標 `@Validated`、self-invocation、忘註冊 BPP——三種失效**全部靜默放行**，比炸掉危險
- **兩套機制的認知稅**：`@Valid`／`@Validated`、兩種例外、400／500——混用的專案要在統一例外處理裡接兩條線（[knowledge-java 那篇](../../knowledge-java/03-spring-to-spring-boot/exception-handling-and-validation.md)的 advice 得加一個 handler）
- **每次呼叫都過驗證攔截**——高頻內部方法要掂量；驗證邏輯重的（跨欄位、查 DB 的自訂 constraint）更要小心放哪
- **依賴組合有坑**：EL 實作必備、版本衝突親踩（expressly 夾帶舊 spring-expression）

## 小結

- 驗證有**兩套機制**：MVC binding（`@Valid @RequestBody`→400）與方法級（類別 `@Validated`＋proxy→`ConstraintViolationException`，預設 500）——例外型別和狀態碼都不同
- **開關在類別上**：參數 constraint 寫滿、類別忘標 `@Validated`＝全部裝飾品（實測 `amount=-5` 長驅直入）——失效無聲，比炸更危險
- 分工：`@Validated` 開門（＋groups）、`@Valid` 帶路（cascade 進物件內部，實測有無差異）
- **回傳值也能驗**（`@NotNull` 攔下回 null 的方法）——對下游失手的最後防線
- proxy 失效清單第三次應驗：self-invocation 靜默跳過——@Async／@Cacheable／@Validated 同病三症

驗證擋的是「不該進來的」；但有些呼叫是「進來了、也合法，只是**暫時**失敗」——網路抖一下、DB 死鎖、第三方 API 限流。把「再試一次」宣告化，就是下一位家族成員：見規劃中的〈Spring Retry：@Retryable 與退避策略〉。

## 常見面試題

1. `@Valid` 和 `@Validated` 有什麼差別？（提示：cascade 標記 vs 類別級開關＋groups；背後是兩套機制、兩種例外）
2. 參數上明明有 `@NotBlank` 卻沒有驗證，可能的原因有哪些？（提示：類別沒標 `@Validated`／self-invocation／`MethodValidationPostProcessor` 不在場——三種都是無聲失效）
3. 方法級驗證失敗會丟什麼例外？在 Web 層預設會變成什麼狀態碼？（提示：`ConstraintViolationException`→500；要自己在 advice 接成 400）

## 延伸閱讀

- [Spring Framework 官方文件：Java Bean Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html) — 方法級驗證與 `MethodValidationPostProcessor` 的官方說明
- [Spring Framework 6.1：Built-in method validation for controllers](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html) — Web 層內建方法驗證的新世界（第 04 章展開）
- [Hibernate Validator 官方文件](https://docs.jboss.org/hibernate/stable/validator/reference/en-US/html_single/) — constraint 全集與自訂 constraint
