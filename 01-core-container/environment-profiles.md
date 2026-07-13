# Environment、Profile 與 PropertySource

## 前言

`@Value("${app.timeout}")` 在你本機是 30，上了測試機變 5——誰改的？追問下去通常會發現：properties 檔寫 30、部署腳本 `-Dapp.timeout=5`、容器平台又塞了環境變數 `APP_TIMEOUT=10`。**三處同時設定，誰贏？為什麼？**

多數人背 Boot 文件那張優先序表應付這題。但那張表不是規則，是**產品**——機制原型在 Spring Framework 的 `Environment` 抽象裡，而且小到可以一句話講完：**一疊 PropertySource，由上往下問，先答先贏**。[上一篇](dependency-resolution.md)回答了「注入誰」；本文回答「值從哪來」，順便拆掉一顆安靜的炸彈：缺 key 時，`@Value` 可能把 `${app.missing}` **這串字面值**直接塞進你的欄位。

## 技術背景

### 先破除誤解：@Value 讀的不是「設定檔」

`@Value` 問的是 **`Environment`**。這個物件管兩件事：

- **properties**：一疊 `PropertySource`——每個 source 就是一個會回答 `getProperty(key)` 的 key-value 來源（系統屬性、環境變數、properties 檔、你手寫的 Map…）
- **profiles**：目前啟用的環境標籤（決定哪些 bean 存在，稍後講）

查值的演算法只有一行：**從疊頂往下問，第一個有答案的 source 贏**。設定檔只是這疊裡的一層——通常還是墊底的那層。

### 出廠的疊，與插隊的原語

`StandardEnvironment` 出廠只有兩層（實測見場景A，這疊可以直接印出來）：

```
systemProperties     ← JVM 的 -D 參數
systemEnvironment    ← OS 環境變數
（@PropertySource 掛的檔案 → addLast，墊底）
```

所以 Framework 層的天然優先序是：**-D ＞ 環境變數 ＞ 設定檔**。而 `MutablePropertySources` 提供 `addFirst`／`addLast`／`addBefore` 三個插隊原語——Boot 那張十幾層的優先序表（command line args、random、application.yml 家族…），就是用這三個方法疊出來的。

一個實務彩蛋：`systemEnvironment` 這層是 `SystemEnvironmentPropertySource`，查 `app.timeout` 失敗時會自動改試 **`APP_TIMEOUT`**（`.`→`_`、轉大寫）——「環境變數蓋設定檔」在 Framework 層就成立，不是 Boot 的專利（Boot 的 relaxed binding 是它的加強版）。

### ${} 是誰解析的——兩種模式，天壤之別

`@Value` 裡的 `${}` 不是容器本能（[這個句式你已經熟了](bean-post-processor.md)），解析器有兩種可能：

| | 有 `PropertySourcesPlaceholderConfigurer`（PSPC） | 沒有（容器的 fallback resolver） |
|---|---|---|
| 誰 | 一個 **BFPP**（[設計圖階段](bean-definition-and-bfpp.md)的住戶） | 容器在啟動尾聲塞的簡易 resolver |
| 缺 key | **啟動即炸**：`Could not resolve placeholder` | **把 `${app.missing}` 字面值注入**，不炸不警告 |
| 誰幫你註冊 | Boot 自動配置 | plain Spring／簡陋測試環境的預設 |

Boot 專案感受不到差異（PSPC 已就位）；但在 plain Spring、自組的測試 context、或某些老專案裡，**打錯 key 的懲罰不是報錯，而是一串 `${...}` 流進業務邏輯**——比炸掉危險得多。自己註冊 PSPC 時記得宣告成 `static @Bean`（它是 BFPP，[早產規則](container-startup-refresh.md)適用）。

順帶：`${key:default}` 的預設值語法兩種模式都支援，是防禦缺 key 的第一道紀律。

### Profile：bean 存在與否的開關

property 決定「值是多少」，profile 決定「**bean 在不在**」——兩者都掛在 `Environment` 上：

```java
@Bean @Profile("dev")  Gateway devGateway()  { ... }   // dev 啟用時才進設計圖
@Bean @Profile("prod") Gateway prodGateway() { ... }   // 支援 "!prod"、"dev | staging" 表達式
```

啟用方式的優雅之處在於：**`spring.profiles.active` 本身就是一個 property**——所以它自動繼承整套疊層規則，`-D`、環境變數 `SPRING_PROFILES_ACTIVE`、程式呼叫 `setActiveProfiles()` 都行，優先序照疊。沒啟用任何 profile 時，名為 `default` 的 profile 生效。

判定時機你也已經知道了：`@Profile` 是 `@Conditional` 的特化——[第 5 步翻譯設計圖時](bean-definition-and-bfpp.md)就定案，不是 runtime 開關。

### 與 Boot 的邊界

本文是 Framework 層的機制；Boot 在同一疊上加了 `application.yml` 家族（含 profile-specific 檔）、更寬鬆的 relaxed binding、以及型別安全的 `@ConfigurationProperties`——見規劃中的〈@ConfigurationProperties 與 relaxed binding〉（第 03 章）。

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`，以 `-e APP_TIMEOUT=10` 提供環境變數）。

▶ 可執行範例：[EnvStack.java](examples/EnvStack.java)

### 場景A：三處同時設定，誰贏

```
這一疊（由上往下問）          | [systemProperties, systemEnvironment, app.properties(模擬設定檔)]
三處同時設定 app.timeout     | -D=5、環境變數 APP_TIMEOUT=10、設定檔=30
@Value("${app.timeout}")   | 5  ← systemProperties 先答
拿掉 -D 再啟動一次           | 10 ← 環境變數出線：APP_TIMEOUT 被當成 app.timeout 回答
addFirst 插一層「緊急覆寫」   | 99 ← 插隊者先答
```

三個實測重點：疊**印得出來**（排查「值哪來」的第一步就是 dump 這疊）；環境變數的 **key 自動轉換**（`APP_TIMEOUT` 回答了 `app.timeout` 的查詢）；`addFirst` 的插隊權——線上緊急覆寫一個參數而不改包不改檔，機制上就是這一行。

### 場景B：key 不存在的兩種下場

```
沒有 PSPC：@Value("${app.missing}")          | ${app.missing}  ← 字面值被安靜注入！
沒有 PSPC：@Value("${app.missing:fallback}") | fallback
有 PSPC：啟動即炸 | Could not resolve placeholder 'app.missing' in value "${app.missing}"
```

同一行 `@Value`，環境裡差一個 PSPC，結局從「啟動失敗、訊息點名哪個 key」變成「一串 `${...}` 靜靜流進欄位」。如果這個欄位是條件判斷的一部分，bug 會躲到很深的地方才現形——**啟動即炸是這裡的好結局**。

### 場景C：Profile 開關 bean

```
setActiveProfiles("dev")      | 拿到的 Gateway：沙盒閘道（dev）
-Dspring.profiles.active=prod | 拿到的 Gateway：正式閘道（prod） ← 啟用 profile 本身也只是一個 property
```

同一份配置、不碰任何程式碼，一個 `-D` 就切換了容器裡住的是哪顆 bean——而那個 `-D` 之所以有效，正是場景A那疊的功勞。

## 技術優缺點

### 這套抽象買到什麼

- **程式碼只認 key**：`@Value("${app.timeout}")` 不知道也不必知道值來自檔案、環境變數還是運維的臨時 `-D`——部署形態（實體機、容器、雲）換了，程式碼一行不動
- **優先序可推理、可插隊**：一疊 first-wins 是能在腦中執行的模型；`addFirst` 給了緊急覆寫的正規管道
- **環境差異宣告式**：`@Profile` 把「測試用沙盒閘道、正式用真閘道」從 if-else 變成標籤——切換點集中在一個 property 上

### 代價與地雷

- **疊是隱形的**：值被哪一層答掉，log 不會告訴你——排查工具就是把疊 dump 出來逐層查（Boot 的疊更深，這個技能更重要）
- **字面值注入是安靜炸彈**：非 Boot 環境缺了 PSPC，打錯 key 的懲罰被延遲到業務邏輯深處——`${key:default}` 與「確保 strict 解析」是兩道保險
- **`@Value` 在 bean 建立時定格**：之後改 property 不會回填（singleton 只建一次）——需要動態配置是另一個問題域的事，別對 `@Value` 有這個期待
- **profile 表達式會組合爆炸**：`@Profile("!prod")` 這類負邏輯，在 profile 變多後極難推理——標籤保持少而正面

## 小結

- `@Value` 問的是 `Environment`：**一疊 PropertySource、由上往下、先答先贏**——Boot 的優先序表是這個機制的產品，不是規則本身
- Framework 出廠疊序：**-D ＞ 環境變數 ＞ 設定檔**；環境變數層自帶 `APP_TIMEOUT`→`app.timeout` 的 key 轉換（實測為證）
- `addFirst`／`addLast` 是優先序的操作原語——緊急覆寫、測試造數都是同一招
- 缺 key 的兩種下場：有 PSPC **啟動即炸**（好結局）；沒有 PSPC **字面值安靜注入**（實測 `${app.missing}` 進了欄位）——非 Boot 環境務必確認 strict 解析
- `@Profile` 是 `@Conditional` 的特化（第 5 步定案）；**`spring.profiles.active` 本身就是 property**，自動繼承整套疊層規則

Environment 管的是**靜態的值**——啟動時定格、之後不動。容器裡還有一套**動的**通訊機制：`ContextRefreshedEvent` 在[啟動全景](container-startup-refresh.md)裡露過臉，但事件能做的遠不止宣布開幕——解耦業務模組、交易邊界上的回呼都靠它：見[事件機制：ApplicationEvent 與 @EventListener](application-events.md)。

## 常見面試題

1. 同一個 key 在 properties 檔、環境變數、`-D` 參數都有設，`@Value` 拿到哪個？為什麼？（提示：一疊 PropertySource first-wins；出廠疊序）
2. `@Value("${x}")` 的 x 不存在，會發生什麼事？（提示：兩種模式——strict 啟動炸 vs 字面值安靜注入；`${x:default}` 防禦）
3. `spring.profiles.active` 有哪些設定方式？彼此優先序怎麼定？（提示：它本身是 property，走同一疊）

## 延伸閱讀

- [Spring Framework 官方文件：Environment Abstraction](https://docs.spring.io/spring-framework/reference/core/beans/environment.html) — profile 與 property source 的官方全貌
- [Spring Boot 官方文件：Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) — Boot 疊出來的完整優先序表（對照本文的機制原型）
- [PropertySourcesPlaceholderConfigurer javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/support/PropertySourcesPlaceholderConfigurer.html) — strict 解析與 static 宣告建議
