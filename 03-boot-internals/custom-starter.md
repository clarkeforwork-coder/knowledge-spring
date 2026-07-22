# 自製一個 starter

## 前言

團隊的第三個專案又要接內部通知系統了。你打開上一個專案，把同一坨配置碼——client、序列化設定、retry 包裝、一組 `@Value`——複製過去，改兩個參數。第 N 次做這件事的時候，你該把它包成 **starter**：讓下個專案「引一個依賴，功能就生效」。

[knowledge-java 的自動配置篇](../../knowledge-java/03-spring-to-spring-boot/spring-boot-autoconfiguration.md)講的是**讀方**視角——Boot 官方 starter 怎麼運作；本文是**寫方**視角：用兩塊你已經集齊的積木（條件裝配＋[上一篇的型別安全配置](configuration-properties.md)），組出自己的「引依賴即生效」。範例是一個完整可跑的迷你 starter，三種使用情境全部實測——包括 starter 最重要的品德：**讓位**。

## 技術背景

### 先破除誤解：starter ≠ 依賴打包

只把一堆依賴包成 pom 是半個 starter。完整的 starter 是三件東西：**依賴集合＋自動配置類（帶條件與讓位禮儀）＋型別安全的配置屬性**。少了後兩者，使用者引了依賴還是得自己寫配置碼——那只是省了幾行 `<dependency>`。

### starter 的解剖：檔案佈局與魔法所在

```
my-notify-spring-boot-starter/          ← 命名慣例：第三方用 {name}-spring-boot-starter
 ├─ pom.xml                                （官方保留 spring-boot-starter-{name}）
 └─ src/main/
     ├─ java/…/NotifyAutoConfiguration.java   自動配置類
     ├─ java/…/NotifyProps.java               @ConfigurationProperties
     └─ resources/META-INF/spring/
         └─ org.springframework.boot.autoconfigure.AutoConfiguration.imports  ★ 魔法所在
```

`.imports` 檔一行一個自動配置類全名。發現機制一條線串回舊識：`@EnableAutoConfiguration` → `AutoConfigurationImportSelector`（[第 01 章講過的 `ImportSelector` 三形態之一](../01-core-container/bean-definition-and-bfpp.md)）→ `ImportCandidates.load()` 掃**所有 jar** 的這個固定路徑。你的 jar 被放上 classpath 的那一刻，配置就被看見了——「引依賴即生效」的全部秘密就是這個檔案（歷史註記：Boot 2.7 前用 `spring.factories`，讀舊 starter 原始碼會看到它）。

### @AutoConfiguration 不是換皮的 @Configuration

三個差異，每個都有實務意義：

1. **隱含 `proxyBeanMethods = false`**（lite 模式）——[上一章深入文](../02-declarative-infrastructure/deep-proxy-creation.md)的知識直接落地：自動配置類不做 CGLIB 增強，啟動更快，`@Bean` 互呼叫改用參數注入
2. **參與自動配置排序**：`before`／`after` 屬性宣告與其他自動配置的先後
3. **最後才處理（deferred）**——這是讓位禮儀能成立的機制基礎：自動配置在**使用者的配置全部註冊完之後**才評估條件，所以 `@ConditionalOnMissingBean` 檢查時，使用者的 bean 一定已經在場。沒有這個順序保證，「讓位」就是碰運氣

### 讓位禮儀：starter 的靈魂三件套

```java
@AutoConfiguration
@EnableConfigurationProperties(NotifyProps.class)
@ConditionalOnProperty(prefix = "notify", name = "enabled",
                       havingValue = "true", matchIfMissing = true)  // ② 預設開、一鍵關
class NotifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean          // ① 使用者定義了自己的 → 我退場
    Notifier consoleNotifier(NotifyProps props) { ... }
}
```

| 條件 | 禮儀 | 實測 |
|---|---|---|
| `@ConditionalOnMissingBean` | 使用者自定義同型別 bean → starter 讓位 | 情境B |
| `@ConditionalOnProperty` ＋ `matchIfMissing = true` | 零配置就生效，一個屬性能整包關閉 | 情境C |
| `@ConditionalOnClass` | classpath 有那個庫才配（starter 對 optional 依賴的姿勢） | [knowledge-java 篇](../../knowledge-java/03-spring-to-spring-boot/spring-boot-autoconfiguration.md)手工重現過 |

加上 `@ConfigurationProperties` 劃出的 `notify.*` 命名空間，使用者的三檔控制力就齊了：**不動（用預設）→ 調屬性 → 換實作**。

### 排錯武器：條件評估報告

自動配置的一切決定都被記錄在 `ConditionEvaluationReport`——`--debug` 啟動可以整份印出，或程式化讀取（本篇實測用後者）。每個條件**為何成立／為何不成立**都是一行人話，這是「魔法可審計」的關鍵。

## 實際案例

驗證環境：spring-boot-starter 3.4.5、JDK 17（JBang，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[NotifyStarterDemo.java](examples/NotifyStarterDemo.java)＋[notify-starter.imports](examples/notify-starter.imports)
（本範例破例帶一個資源檔：`//FILES` 把 imports 檔打進 jar 的 `META-INF/spring/`——自動配置是**真的被發現**的，不是 `@Import` 進來的。使用者側的 App 完全不認識 `NotifyAutoConfiguration`。）

### 情境A：引依賴即生效

```
[內建通知器] [緊急] 系統將於今晚維護
```

`UserAppA` 一行功能碼都沒有——`Notifier` 由 imports 檔發現的自動配置提供，前綴還能用 `--notify.prefix=[緊急]` 調整。**零程式碼、可配置**，starter 體驗的基準線。

### 情境B：使用者自定義 → starter 讓位

```
[自家的通知器] 系統將於今晚維護
條件評估報告（節選）：
  NotifyAutoConfiguration → @ConditionalOnProperty (notify.enabled=true) matched
  NotifyAutoConfiguration#consoleNotifier → @ConditionalOnMissingBean (types: Notifier;
      SearchStrategy: all) found beans of type 'Notifier' customNotifier
```

使用者定義了 `customNotifier`，內建的安靜退場。報告把理由寫成人話：**「found beans of type 'Notifier' customNotifier」**——誰擠掉了誰、為什麼，一行對帳。

### 情境C：一個屬性整包關閉

```
getBean(Notifier.class) 炸了：No qualifying bean of type 'Notifier' available
條件評估報告（節選）：
  NotifyAutoConfiguration → @ConditionalOnProperty (notify.enabled=true)
      found different value in property 'enabled'
```

`--notify.enabled=false` 讓類別級條件不成立，整包配置（含屬性綁定）消失。緊急停用一個 starter 不用改程式碼——這就是 `matchIfMissing = true` 搭配關閉開關的設計價值。

## 技術優缺點

### 自製 starter 買到什麼

- **複製貼上變成版本化模組**：配置碼有了唯一出處、有版本號、能發 changelog——「每個專案的通知配置都長得不太一樣」這種熵增被止住
- **三檔控制力並存**：預設好用（零配置）、屬性可調、實作可換——讓位禮儀讓「框架的便利」與「使用者的主權」不打架
- **魔法可審計**：條件報告讓每個 bean 的來龍去脈有一行解釋——比公司內部 wiki 可靠

### 代價與地雷

- **隱形的配置來源**：bean 從一個「沒人 import 過的類別」裡長出來——新人排查時的第一個迷宮（解藥就是條件報告，把它寫進團隊 onboarding）
- **讓位禮儀寫漏＝和使用者搶 bean**：忘了 `@ConditionalOnMissingBean` 的 starter 會讓使用者的自定義變成 `NoUniqueBeanDefinitionException`——[第 01 章的裁決鏈](../01-core-container/dependency-resolution.md)救不了設計失禮
- **你成了那個「週邊專案」**：[上一章兩顆傳遞依賴地雷](../02-declarative-infrastructure/spring-retry.md)的教訓反轉適用——starter 拖什麼版本的依賴，全公司跟著吃；依賴範圍要克制、版本要跟公司的 Boot 基線
- **過度 starter 化**：三個類別的工具庫不需要自動配置——starter 的成本（維護、版本、發布）要用「被多少專案複製過」來justify

## 小結

- 完整 starter＝**依賴＋自動配置＋配置屬性**三件套；「引依賴即生效」的魔法＝`META-INF/spring/…AutoConfiguration.imports` 被 `ImportCandidates` 掃描（實測：App 不認識配置類，功能照常生效）
- `@AutoConfiguration` 的三個特質：lite 模式、可排序、**deferred**——最後一項是 `@ConditionalOnMissingBean` 可靠讓位的機制保證
- 讓位禮儀三件套：`@ConditionalOnMissingBean`（換實作）＋`@ConditionalOnProperty(matchIfMissing=true)`（一鍵關）＋`@ConditionalOnClass`（optional 依賴）——實測前兩者，報告一行講清理由
- 使用者的三檔控制力：**不動 → 調屬性 → 換實作**（三情境實測）
- 排錯先看 **`ConditionEvaluationReport`**（`--debug` 或程式化讀取）——自動配置世界的對帳單

starter 讓功能「引了就有」——Boot 自家把這招用得最徹底的，是那包「引了就有一整套營運端點」的 Actuator：health、metrics、環境快照，以及它們的安全邊界。見規劃中的〈Actuator：health、metrics 與 endpoint 安全〉。

## 常見面試題

1. 一個 starter 由哪些部分組成？自動配置類是怎麼被「發現」的？（提示：三件套；`.imports` 檔＋`ImportCandidates`；2.7 前是 `spring.factories`）
2. `@AutoConfiguration` 和 `@Configuration` 有什麼差別？（提示：lite／排序／deferred——最後者是讓位可靠的原因）
3. 寫 starter 為什麼一定要 `@ConditionalOnMissingBean`？漏了會怎樣？（提示：讓位禮儀；和使用者的 bean 打架）

## 延伸閱讀

- [Spring Boot 官方文件：Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) — 官方的 starter 開發指南（含命名慣例與條件註解全集）
- [ImportCandidates 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/context/annotation/ImportCandidates.java) — `.imports` 檔的讀取者
- [Spring Boot 官方文件：Condition Annotations](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html#features.developing-auto-configuration.condition-annotations) — 讓位禮儀的完整工具箱
