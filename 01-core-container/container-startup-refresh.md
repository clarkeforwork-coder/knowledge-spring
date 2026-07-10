# ApplicationContext 啟動全景：refresh() 做了哪些事

## 前言

按下啟動，到 log 印出「容器就緒」之間，Spring 做了什麼？大多數人的答案是「把 bean 都 new 出來」——這個答案不能算錯，但它只覆蓋了**十二步中的一步**。

[knowledge-java 的〈Bean 生命週期與 Scope〉](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md)講了**一顆 bean** 的流水線；本文從該篇的結尾接下去，把鏡頭拉遠到**整座工廠**：設計圖是誰收的、誰有權修改、流水線上的工人（BeanPostProcessor）何時到職、廠房何時開幕。懂了這張全景圖，三個工作中會撞到的問題就有了答案：為什麼自動配置能在 bean 誕生前「改別人的設定」？為什麼有的 bean 莫名比別人早建、還冒出看不懂的 INFO 警告？想在「一切就緒後」跑啟動邏輯，為什麼 `@PostConstruct` 是錯的地方？

## 技術背景

### 先破除誤解：設計圖與成品是兩回事

`@ComponentScan` 掃到你的類別時，容器**沒有**建立任何物件——它登記的是一張**設計圖：`BeanDefinition`**（記錄 class、scope、init 方法、依賴描述…）。設計圖先全部入庫，之後才有人照圖施工。

這個分離是理解 Spring 一半機制的鑰匙：**因為施工還沒開始，設計圖是可以改的**。placeholder 解析、條件化配置、Boot 的自動配置，全都發生在「只有圖、還沒有房子」的階段。

### refresh() 的十二步：一齣三幕劇

容器啟動的入口是 `AbstractApplicationContext.refresh()`（`new AnnotationConfigApplicationContext(...)` 和 Boot 的啟動最終都走到它）。方法名對照 spring-framework 6.2.x 原始碼：

```
第一幕：蓋廠房、收設計圖
  1  prepareRefresh                     環境與 PropertySource 準備
  2  obtainFreshBeanFactory             建 BeanFactory，BeanDefinition 入庫
  3  prepareBeanFactory                 廠房標配：ClassLoader、SpEL、內建依賴
  4  postProcessBeanFactory             留給子類的鉤子
  5  invokeBeanFactoryPostProcessors    ★ 設計圖修改站（@ComponentScan 實際發生地）

第二幕：工人到職
  6  registerBeanPostProcessors         ★ 工人先建立、就位——但還沒開工
  7  initMessageSource                  i18n
  8  initApplicationEventMulticaster    事件廣播器
  9  onRefresh                          子類鉤子（Boot 的內嵌 Tomcat 在這裡啟動）
 10  registerListeners                  掛上事件監聽器

第三幕：量產、開幕
 11  finishBeanFactoryInitialization    ★ 所有 singleton 走那條流水線
 12  finishRefresh                      SmartLifecycle.start() → 發 ContextRefreshedEvent
```

knowledge-java 講的那條 bean 流水線，整條都發生在**第 11 步之內**——它前面有十步在做準備。

### 兩種 post processor：站別不同，權力不同

名字只差一個字，混淆了就看不懂框架原始碼：

| | BeanFactoryPostProcessor | BeanPostProcessor |
|---|---|---|
| 面對的是 | **設計圖**（BeanDefinition） | **成品**（bean 實例） |
| 執行次數 | 整個容器**一次**（第 5 步） | **每顆 bean** 前後各一次（第 11 步內） |
| 典型代表 | `ConfigurationClassPostProcessor`、placeholder 解析 | `@Autowired` / `@PostConstruct` 的處理器、AOP proxy 製造者 |
| 你會用它做 | 改配置、註冊額外的 BeanDefinition | 包裝、驗收、替換 bean |

兩個常被當成「容器內建魔法」的功能，其實都只是其中一員：

- `@Configuration`、`@ComponentScan`、`@Import` 的解析，全是 `ConfigurationClassPostProcessor` 這**一個 BFPP** 在第 5 步做完的
- `@Autowired` 與 `@PostConstruct` 不是容器親手處理的，是兩個 **BPP**（`AutowiredAnnotationBeanPostProcessor`、`CommonAnnotationBeanPostProcessor`）在流水線上加工的——那條「生命週期流水線」本質上就是 BPP 們的排班表

### 副作用：post processor 自己必須「早產」

BFPP 和 BPP 自己也是 bean，但它們必須**比所有人早出生**（工人得先到職才能加工別人）。於是容器在第 5、6 步就把它們實例化——**不走正常班表**。後果：它們的依賴會被連帶提早建立，而這些早產兒會**錯過還沒上班的工人的加工**。

這就是那個著名 INFO 警告的來源（實測見案例二）：用非 static 的 `@Bean` 方法宣告 BFPP 時，整個 `@Configuration` 類別被迫提早實例化，它自己的 `@Autowired`、`@PostConstruct` 就沒人處理了。解法是官方在警告裡直接給的：

```java
@Bean
static MyBeanFactoryPostProcessor mybfpp() {   // ✅ static：不必先建出 Config 實例
    return new MyBeanFactoryPostProcessor();
}
```

### 「一切就緒後」的啟動邏輯放哪

| 掛載點 | 觸發時刻 | 語意 |
|---|---|---|
| `@PostConstruct` | 第 11 步，**自己**下線時 | ❌ 只保證自己就緒——別的 bean 可能還沒建 |
| `SmartLifecycle.start()` | 第 12 步，事件**之前** | ✅ 全部 singleton 就緒；`close()` 時對稱回呼 `stop()` |
| `@EventListener(ContextRefreshedEvent)` | 第 12 步，最後 | ✅ 全廠開幕的廣播，最通用的「就緒後」掛載點 |
| Boot 的 `ApplicationRunner` | refresh() 返回之後 | ✅ Boot 應用的首選（見規劃中的〈SpringApplication.run() 到底跑了什麼〉） |

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action` 執行）。

### 案例一：十二步直播

在每個擴充點掛上 log（站名是對照 `refresh()` 原始碼標上的），一次啟動、一次關閉：

▶ 可執行範例：[RefreshTimeline.java](examples/RefreshTimeline.java)

```
=== new AnnotationConfigApplicationContext(App.class)：refresh() 開始 ===
[5] invokeBeanFactoryPostProcessors   | DesignReviewer 建構子（BFPP 自己先出生）
[5] invokeBeanFactoryPostProcessors   | 設計圖入庫 10 張；orderService 實例存在？false
[6] registerBeanPostProcessors        | 工人 Inspector 到職（建構子）
[11] finishBeanFactoryInitialization  | OrderService 建構子
[11] finishBeanFactoryInitialization  | OrderService @PostConstruct
[11] finishBeanFactoryInitialization  | Inspector 驗收 orderService（AOP proxy 誕生的那一站）
[12] finishRefresh                    | Doorman.start()（SmartLifecycle）
[12] finishRefresh                    | 收到 ContextRefreshedEvent——全廠開幕，所有 singleton 已就緒
=== refresh() 返回：容器就緒 ===
[close]                               | Doorman.stop()
```

逐行對照三幕劇：

- **第 5 步時設計圖已有 10 張**（4 顆自訂 bean ＋ config 類別＋容器自帶的 5 個註解處理器），但 `orderService` 實例**不存在**——設計圖全到了、施工還沒開始
- **工人（BPP）在第 6 步到職**，比第 11 步才出生的業務 bean 早——順序反了整個機制就不成立
- `@PostConstruct` 之後才輪到 **BPP 的 after 驗收**——這正是 knowledge-java 說的「[AOP proxy 產房](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md)」在全景圖上的位置
- **`SmartLifecycle.start()` 在 `ContextRefreshedEvent` 之前**，且 `close()` 時對稱地收到 `stop()`——事件監聽器沒有這個對稱性

### 案例二：把 static 拿掉，警告原文重現

把範例裡 BFPP 的 `@Bean static` 改成 `@Bean`，啟動時多出這段（原文照貼）：

```
INFO: @Bean method App.designReviewer is non-static and returns an object assignable
to Spring's BeanFactoryPostProcessor interface. This will result in a failure to
process annotations such as @Autowired, @Resource and @PostConstruct within the
method's declaring @Configuration class. Add the 'static' modifier to this method
to avoid these container lifecycle issues; see @Bean javadoc for complete details.
```

翻譯成全景圖的語言：容器要在**第 5 步**拿到這個 BFPP，而非 static 方法得先有 `App` 實例才能呼叫——於是 `App` 被迫早產，錯過第 6 步才到職的 `@Autowired`/`@PostConstruct` 工人。警告說的 "container lifecycle issues" 就是**站別錯誤**：一個 bean 被拉到它的加工者上班之前出生。

## 技術優缺點

### 設計圖／成品分離＋固定站別，買到什麼

- **配置的可編輯性**：BeanDefinition 在施工前可改，placeholder、profile 條件、Boot 自動配置（[條件註解那篇](../../knowledge-java/03-spring-to-spring-boot/spring-boot-autoconfiguration.md)的地基）才有立足點
- **擴充點語意明確**：想改配置站第 5 步、想加工 bean 站第 11 步、想在就緒後動作站第 12 步——框架與你的程式碼各就各位，不互相踩
- **對稱的生命週期**：`SmartLifecycle` 的 start/stop 隨容器開關自動觸發，資源型元件（排程器、連線、監聽 MQ）有明確的開機／收攤點

### 代價與地雷

- **啟動成本**：十二步、大量反射與掃描——bean 越多越慢。這是 Boot 啟動優化、AOT 與 Native Image 整條路線的原始動機
- **站別錯誤很隱蔽**：早產警告只是 INFO，不炸不停；症狀（`@Autowired` 是 null、`@PostConstruct` 沒跑）出現在離原因很遠的地方
- **「就緒」有四種**，掛錯點的 bug 只在特定時序下現形——上面那張表值得收藏

## 小結

- `refresh()` 是三幕劇：**收設計圖（1–5）→ 工人到職（6）→ 量產開幕（11–12）**；「把 bean new 出來」只是第 11 步
- **BFPP 改設計圖**（容器一次）、**BPP 加工成品**（每顆 bean）——名字像，站別和權力完全不同
- `@ComponentScan`／`@Import`／`@Configuration` 解析＝一個 BFPP（`ConfigurationClassPostProcessor`）在第 5 步做完；`@Autowired`／`@PostConstruct`＝兩個 BPP 在第 11 步加工
- post processor 與其依賴會**早產**——BFPP 的 `@Bean` 方法要宣告 `static`（實測警告原文為證）
- 「全廠就緒後」的邏輯掛 `@EventListener(ContextRefreshedEvent)` 或 `SmartLifecycle`，**不是** `@PostConstruct`

第 5 步那位一手包辦 `@Configuration` 解析的最大玩家，是怎麼把你的註解變成一張張設計圖的？@Import、@Conditional 又是在哪個瞬間被判定的？見 [BeanDefinition 與 BeanFactoryPostProcessor：Bean 的「設計圖」階段](bean-definition-and-bfpp.md)。

## 常見面試題

1. 描述 Spring 容器的啟動流程。（提示：三幕劇——設計圖入庫與修改、BPP 註冊、singleton 預實例化、就緒事件）
2. `BeanFactoryPostProcessor` 和 `BeanPostProcessor` 差在哪？（提示：設計圖 vs 成品；一次 vs 每顆 bean；第 5 步 vs 第 11 步）
3. 為什麼宣告 `BeanFactoryPostProcessor` 的 `@Bean` 方法建議加 `static`？（提示：早產——config 類別被迫在加工者上班前實例化）

## 延伸閱讀

- [Spring Framework 官方文件：Container Extension Points](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html) — BFPP／BPP 的官方定位
- [AbstractApplicationContext.refresh() 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java) — 十二步的第一手依據
- [@Bean javadoc — Bootstrapping 一節](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Bean.html) — static 建議的官方說明
