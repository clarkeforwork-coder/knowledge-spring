# @ConfigurationProperties 與 relaxed binding

## 前言

[上一篇](springapplication-run.md)看到 `application.yml` 在 `prepareEnvironment` 就疊進了 Environment——但疊進去的是**字串**，程式要的是 `int`、`Duration`、`List`。從字串到型別安全物件的這段路，就是**綁定（binding）**，也是 `@Value` 和 `@ConfigurationProperties` 的分水嶺。

三個工作中的具體疑問給這篇定調：環境變數 `SERVER_PORT` 為什麼對得上 `server.port`？`retry-count`、`retryCount`、`RETRY_COUNT` 為什麼都綁得上同一個欄位？以及最重要的——**配置打錯字，為什麼有時啟動大炸、有時卻無聲無息**？（實測答案：錯在「值」會炸得漂漂亮亮，錯在「鍵」則安靜蒸發。）

## 技術背景

### 先破除誤解：@ConfigurationProperties 不是「批量 @Value」

| | `@Value("${...}")` | `@ConfigurationProperties` |
|---|---|---|
| relaxed binding | ❌ 只認精確 key | ✅ kebab／camel／環境變數全通 |
| 巢狀物件、List、Map | ❌ | ✅ 一整棵樹綁進 record |
| 驗證（fail fast） | ❌ | ✅ `@Validated`＋constraint |
| IDE 自動補全 | ❌ | ✅（配 metadata annotation processor） |
| SpEL | ✅ | ❌ |
| 適用 | 單一值、需要運算式 | **成組的配置**（絕大多數場景） |

### 基本盤：record 建構子綁定是 Boot 3 的主流姿勢

```java
@ConfigurationProperties(prefix = "my-app")
record MyAppProps(
        String name,
        @DefaultValue("3") int retryCount,   // 沒綁到 → 預設值
        Duration timeout,                    // "5s" → PT5S，轉換是一等公民
        List<String> channels,               // my-app.channels[0]=... 索引綁定
        Api api) {                           // 巢狀 record，一棵樹一次綁完
    record Api(String url, String token) { }
}
```

record＝**不可變**配置：綁定走建構子、啟動後沒人能改——[knowledge-java 講過的不可變紅利](../../knowledge-java/02-language-core/record-and-immutability.md)在配置場景全額兌現。註冊方式擇一：`@EnableConfigurationProperties(MyAppProps.class)`（顯式，本篇範例用）或 `@ConfigurationPropertiesScan`（掃描）。

### relaxed binding：一個欄位、多種寫法

綁定器（`Binder`）的規則：**canonical 形式是 kebab-case**（`my-app.retry-count`），但來源可以寬鬆——camelCase（`myApp.retryCount`）、底線、大寫都被正規化後匹配。環境變數的映射演算法：**轉大寫、`.` 換 `_`、去掉 `-`**：

```
my-app.name      ←  MY_APP_NAME        （實測綁上）
server.port      ←  SERVER_PORT
my-app.channels[0] ← MY_APP_CHANNELS_0_
```

[第 01 章](../01-core-container/environment-profiles.md)看過 Framework 層 `SystemEnvironmentPropertySource` 的陽春版轉換——Boot 的 Binder 是完整版（含索引、巢狀）。實務意義：**同一份程式碼，開發用 yml、容器平台用環境變數、臨時調試用命令列**，一行不改。

### 錯誤處理的三張臉（本篇主戲）

**臉一：打錯 key ＝ 沉默忽略。** 預設 `ignoreUnknownFields = true`——`retry-cont`（少個 u）不會有任何警告，欄位安靜退回預設值（實測）。這是本篇最重要的反差：**Boot 對「值錯」嚴厲、對「鍵錯」寬容**。防線在編譯期：掛上 `spring-boot-configuration-processor`，IDE 會對打錯的 key 畫黃線。

**臉二：型別錯 ＝ 啟動即炸，而且炸得很漂亮。** `--my-app.timeout=永遠` 換來的不是 stack trace 海，是 `FailureAnalyzer` 的結構化報告（實測原文）：

```
***************************
APPLICATION FAILED TO START
***************************

Description:
Failed to bind properties under 'my-app.timeout' to java.time.Duration:

    Property: my-app.timeout
    Value: "永遠"
    Origin: "my-app.timeout" from property source "commandLineArgs"
    Reason: failed to convert java.lang.String to java.time.Duration
```

注意 **Origin 那行**：它直接指出這個值來自**哪個 property source**（yml 時會精確到檔名＋行號）——第 01 章教你手工 dump 疊層查「值哪來」，Boot 的綁定報告內建了這個答案。

**臉三：值非法 ＝ `@Validated` fail fast。** 同一套 Bean Validation（[上一章的方法級驗證](../02-declarative-infrastructure/method-validation.md)的另一個掛載點）搬到配置上：

```java
@Validated
@ConfigurationProperties(prefix = "strict")
record StrictProps(@Min(1) @DefaultValue("5") int poolSize) { }
```

`--strict.pool-size=0` → 啟動失敗，報告同樣有 Property／Value／Origin／Reason 四件套（實測）。fail fast 的哲學一句話：**配置錯誤最好的引爆時機是啟動那一秒，最壞的是三天後的 NPE**。

## 實際案例

驗證環境：spring-boot-starter＋starter-validation 3.4.5、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`，以 `-e MY_APP_NAME=來自環境變數` 提供環境變數）。

▶ 可執行範例：[BindingLab.java](examples/BindingLab.java)

### 一次啟動，綁定全家福

```
MyAppProps[name=來自環境變數, owner=camelCase寫的key, retryCount=3, timeout=PT5S,
           channels=[email, sms], api=Api[url=https://core.example.com, token=secret]]
```

逐欄驗收：`name` 來自環境變數 `MY_APP_NAME`（relaxed 映射）；`owner` 來自 `--myApp.owner`（camelCase 也通）；`timeout` 從 `"5s"` 轉成 `Duration`；`channels` 用索引語法綁 List；`api` 一棵巢狀樹整包綁進。而 **`retryCount=3`**——我們明明傳了 `--my-app.retry-cont=99`（打錯字），它被沉默忽略、退回 `@DefaultValue`。**一個輸出同時示範了 relaxed binding 的慷慨與臉一的冷漠。**

### 兩張 FailureAnalyzer 報告

型別錯（`timeout=永遠`）與驗證失敗（`pool-size=0`）各炸出一份結構化報告（原文見上文），共同點是那四行：Property／Value／**Origin**／Reason——排查配置問題時，這份報告比任何 stack trace 都值得先讀。

## 技術優缺點

### @ConfigurationProperties 買到什麼

- **配置即 API**：一個 record 就是這組配置的型別安全契約——有型別、有預設值、有驗證、有文件（metadata），而不是散落各處的魔法字串
- **部署形態自由**：relaxed binding 讓 yml／環境變數／命令列互為替身——十二要素應用的「配置外部化」拿來就用
- **錯誤體驗一流**：fail fast＋Origin 追蹤，「值哪來、錯在哪」啟動當下就有答案
- **不可變**：record 綁定讓配置在啟動後凍結——沒有「執行到一半被改掉」這種靈異事件

### 代價與地雷

- **鍵錯沉默**：值錯炸得漂亮、**鍵錯無聲蒸發**——這個反差是最大的坑；`configuration-processor`（IDE 層）＋針對配置的啟動測試是兩道補丁
- **relaxed 的另一面**：同專案裡 kebab、camel 混寫都能跑——寬容機器、混亂人類；團隊慣例定死 kebab-case
- **註冊方式有三種**（`@EnableConfigurationProperties`／`@ConfigurationPropertiesScan`／直接當 bean）——混用時「這個 props 是誰註冊的」要翻半天
- `@Value` 沒有死：單一值＋需要 SpEL 的場景仍是它的地盤——工具各歸其位

## 小結

- 綁定是「Environment 字串 → 型別安全物件」的那段路；**record 建構子綁定＋`@DefaultValue`** 是 Boot 3 主流姿勢（不可變）
- **relaxed binding**：canonical 是 kebab-case，camelCase／`UPPER_SNAKE` 環境變數（轉大寫、`.`→`_`、去 `-`）全綁得上（實測三種來源同時命中）
- 型別轉換是一等公民：`"5s"`→`Duration`、索引 List、巢狀 record 一次綁完
- 錯誤三張臉：**鍵錯沉默忽略（實測蒸發）、型別錯啟動炸、值非法 `@Validated` fail fast**——後兩者附 Property/Value/**Origin**/Reason 結構化報告
- 對「鍵錯沉默」的防線：`spring-boot-configuration-processor` 的 IDE 提示＋配置啟動測試

自動配置（knowledge-java 講過的條件裝配）＋本篇的型別安全配置，正是**自製 starter** 的兩塊積木——把團隊共用的基礎設施包成「引依賴即生效」的模組：見規劃中的〈自製一個 starter〉。

## 常見面試題

1. `@Value` 和 `@ConfigurationProperties` 怎麼選？（提示：對照表——relaxed／巢狀／驗證／metadata vs SpEL）
2. 環境變數 `SERVER_PORT` 為什麼能綁上 `server.port`？（提示：relaxed binding 映射演算法——大寫、`.`→`_`、去 `-`）
3. 配置打錯「鍵」和打錯「值」分別會發生什麼事？（提示：`ignoreUnknownFields` 沉默 vs FailureAnalyzer 報告＋Origin；防線是 metadata processor）

## 延伸閱讀

- [Spring Boot 官方文件：Type-safe Configuration Properties](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties) — 綁定、relaxed binding 規則表與建構子綁定
- [Spring Boot 官方文件：Configuration Metadata](https://docs.spring.io/spring-boot/specification/configuration-metadata/index.html) — `configuration-processor` 與 IDE 提示的機制
- [Binder 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/context/properties/bind/Binder.java) — 綁定器本人
