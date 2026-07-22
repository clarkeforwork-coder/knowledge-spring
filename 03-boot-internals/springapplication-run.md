# SpringApplication.run() 到底跑了什麼

## 前言

每個 Spring Boot 專案的第一行都是它：

```java
SpringApplication.run(App.class, args);
```

[knowledge-java 的自動配置篇](../../knowledge-java/03-spring-to-spring-boot/spring-boot-autoconfiguration.md)解剖了 `@SpringBootApplication` 與條件裝配——「配置從哪來」；[第 01 章](../01-core-container/container-startup-refresh.md)拆了 `refresh()` 十二步——「容器怎麼啟動」。這篇補上中間缺的一塊：**`run()` 這個包裝禮盒本身**。拆開它才能回答四個日常疑問：`application.yml` 到底**什麼時候**被讀？`ApplicationRunner` 和 `ContextRefreshedEvent` 誰先誰後？為什麼有些 Boot 事件用 `@EventListener` **永遠聽不到**？以及——banner 印出來之前，發生了什麼？

## 技術背景

### 先破除誤解：run() ≠ 啟動容器

容器啟動（`refresh()`）只是 `run()` **中段的一步**。完整骨架（對照 Boot 3.4.x 原始碼）：

```
new SpringApplication(App.class)：
    推斷 WebApplicationType（classpath 有 Servlet？Reactive？都沒有？）
    從 spring.factories 載入 ApplicationContextInitializer 與 ApplicationListener（注意：不是 bean！）
    推斷主類（丟一個例外、看堆疊——真的）

run(args)：
    a. 發 ApplicationStartingEvent
    b. prepareEnvironment          ★ application.yml、命令列、環境變數全在這裡入疊——容器還不存在
    c. 印 Banner
    d. createApplicationContext    依 WebApplicationType 選 context 品種
    e. prepareContext              跑 initializers、把主類註冊成「第一張設計圖」、發 prepared
    f. refreshContext              ★ 第 01 章的十二步在這裡（onRefresh 站內嵌伺服器醒來）
    g. 發 ApplicationStartedEvent
    h. callRunners                 ApplicationRunner → CommandLineRunner
    i. 發 ApplicationReadyEvent
```

下面四個洞見，每個都對應一段實測。

### 洞見一：Environment 比容器早出生

`b` 在 `d` 之前——**設定檔就緒時，容器連影子都沒有**（實測：`ApplicationEnvironmentPreparedEvent` 時已能讀到命令列屬性）。這個順序不是巧合，是必然：`spring.main.web-application-type`、`spring.main.lazy-initialization` 這類屬性要**影響容器怎麼被建出來**，就必須比容器早。

讀 `application.yml` 的也因此不可能是 bean 或 BFPP——是更早的 **`EnvironmentPostProcessor`**（具體是 `ConfigDataEnvironmentPostProcessor`），它把 config 檔一層層[疊進第 01 章講過的那疊 PropertySource](../01-core-container/environment-profiles.md)。Boot 的優先序表，就是在 `b` 步疊出來的。

### 洞見二：主類只是「第一張設計圖」

`e` 步對容器做的事少得驚人：把主類**註冊成一張 `BeanDefinition`**，僅此而已。`@ComponentScan` 的展開、自動配置的匯入，全部發生在 `f` 步裡的[第 5 步（`ConfigurationClassPostProcessor`）](../01-core-container/bean-definition-and-bfpp.md)——從這一張圖開始滾雪球。所以「Boot 啟動慢」要 profile 的永遠是兩段：第 5 步的解析掃描、第 11 步的 singleton 量產。

### 洞見三：兩套事件體系——早期事件是 bean 聽不到的

Boot 有自己的事件家族（`SpringApplicationEvent` 七兄弟），和容器的 `ContextRefreshedEvent` 家族**是兩個體系**。關鍵在時間軸：`starting`／`environmentPrepared`／`contextInitialized`／`prepared` 四個早期事件發佈時，**容器裡一顆 bean 都沒有**——bean 身分的 `@EventListener` 物理上不可能聽到（實測：埋了一個監聽 `EnvironmentPrepared` 的 bean，從頭到尾沉默）。

聽早期事件的正規管道有兩條：**程式化註冊**（`app.addListeners(...)`，本篇實測用的就是它）或 **`spring.factories` 登記**（Boot 自家 listener 的方式）。refresh 之後的 `started`／`ready` 則會轉發進容器廣播，bean 才聽得到。

### 洞見四：「應用就緒後」的正確位子是 Runner

[第 01 章那張「就緒後掛載點」表](../01-core-container/container-startup-refresh.md)現在可以補上 Boot 完整版（順序全部實測）：

```
@PostConstruct（自己就緒）
  → SmartLifecycle.start()（第 12 步）
  → ContextRefreshedEvent（第 12 步）
  → ApplicationStartedEvent
  → ApplicationRunner → CommandLineRunner   ← Boot 應用的首選位子
  → ApplicationReadyEvent
```

Runner 的兩個優勢：拿得到解析好的 **`ApplicationArguments`**（實測 `--app.name` 直接取值）；且順序上 `ready` 在 runner **之後**——runner 失敗，應用就不會進入 ready，「啟動時該做的事沒做完就對外服務」這種半熟狀態被順序天然擋掉。

## 實際案例

驗證環境：spring-boot-starter 3.4.5、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[BootRunTimeline.java](examples/BootRunTimeline.java)

輸出全文（banner 與啟動 log 已關，只留時間軸）：

```
[Boot事件] ApplicationStartingEvent
[Boot事件] ApplicationEnvironmentPreparedEvent——此刻已能讀 app.name=「從命令列來的值」（容器還不存在！）
[Boot事件] ApplicationContextInitializedEvent
[Boot事件] ApplicationPreparedEvent
OrderService @PostConstruct（第 11 步量產中）
Doorman.start()（SmartLifecycle，第 12 步）
收到 ContextRefreshedEvent（第 12 步）
[Boot事件] ApplicationStartedEvent
ApplicationRunner 執行（拿得到參數：app.name=[從命令列來的值]）
CommandLineRunner 執行
[Boot事件] ApplicationReadyEvent
run() 返回
```

逐段對照四個洞見：

- **第 2 行**：`EnvironmentPrepared` 時屬性已可讀、容器不存在——洞見一的現場證據
- **前 4 行全是 Boot 事件**、之後才輪到容器內的動靜——`b`→`e` 都在 refresh 之前
- **中段三行**是第 01 章十二步的老朋友（`@PostConstruct` → `SmartLifecycle` → `ContextRefreshedEvent`），被 Boot 事件前後包夾——`run()` 是 `refresh()` 的禮盒，眼見為憑
- **runner 夾在 started 與 ready 之間**，且 `ApplicationRunner` 先於 `CommandLineRunner`
- 還有一行**不存在的輸出**：`TrapListener`（bean 身分、監聽 `EnvironmentPrepared`）從頭到尾沒出聲——洞見三的沉默證據

## 技術優缺點

### run() 的設計買到什麼

- **一行拉起全棧**：類型推斷、環境疊層、容器、內嵌伺服器、啟動任務——一條龍且順序保證正確，這是 Boot「開箱即用」體感的來源
- **鉤子密度高**：七個 Boot 事件＋initializer＋runner，啟動流程的每個階段都有正規掛載點——比「在 main 裡塞程式碼」文明得多
- **Environment 先行**：配置能控制啟動本身（lazy-init、web 類型、banner）——「用設定改行為」的能力延伸到了容器誕生之前

### 代價與地雷

- **兩套事件體系的認知稅**：早期事件 bean 聽不到（實測沉默）——不知道這條線的人會寫出永遠不觸發的 listener，而且**沒有任何警告**
- **spring.factories 是另一個宇宙**：initializer 與早期 listener 不在容器裡，`@Autowired` 不能用、debug 不直覺——它們活在「容器出生前」的世界
- **啟動流程隱形**：死在 `b` 步（設定檔格式錯）和死在 `f` 步（bean 建立失敗）的錯誤長相完全不同，不懂骨架就只能亂猜——`FailureAnalyzer` 的友善報錯緩解了一部分
- **每一步都能自訂**（`SpringApplicationBuilder`、自訂 initializer）——也意味著被玩壞的專案啟動行為無法從主類推斷

## 小結

- `run()` 九步骨架：**環境（b）→ banner → 建容器（d）→ refresh（f）→ runners（h）**——容器啟動只是中段一步，前後各有一半戲
- **Environment 比容器早出生**（實測：EnvironmentPrepared 時命令列屬性已可讀）——讀 `application.yml` 的是 `EnvironmentPostProcessor`，比所有 bean 和 BFPP 都早
- 主類只是**第一張設計圖**——掃描與自動配置在 refresh 第 5 步才滾雪球
- **兩套事件體系**：早期四個 Boot 事件只有程式化／`spring.factories` 註冊聽得到，bean listener 物理性沉默（實測）
- 「就緒後」的正確位子是 **`ApplicationRunner`**（實測順序：@PostConstruct → SmartLifecycle → ContextRefreshed → started → runners → ready）——ready 在 runner 之後，失敗擋在對外服務之前

`b` 步把 `application.yml` 疊進了 Environment——但 `server.port=8080` 這行字串是怎麼變成型別安全的 `int`、`SERVER_PORT` 環境變數為什麼也對得上、`@ConfigurationProperties` 又比 `@Value` 好在哪？下一篇拆綁定機制：見 [@ConfigurationProperties 與 relaxed binding](configuration-properties.md)。

## 常見面試題

1. 描述 `SpringApplication.run()` 的大致流程。`refresh()` 在哪個位置？（提示：環境 → banner → 建 context → refresh → runners；容器啟動只是中段）
2. `application.yml` 是什麼時候、被誰讀進來的？（提示：prepareEnvironment 階段的 `EnvironmentPostProcessor`——比容器早，所以配置能影響容器的建立）
3. 想在應用完全就緒後執行初始化，Boot 給的位子是什麼？和 `@PostConstruct`、`ContextRefreshedEvent` 差在哪？（提示：Runner 的實測順序與 `ApplicationArguments`）
4. 為什麼用 `@EventListener` 聽不到 `ApplicationEnvironmentPreparedEvent`？（提示：發佈時 bean 還不存在——程式化或 spring.factories 註冊）

## 延伸閱讀

- [Spring Boot 官方文件：SpringApplication](https://docs.spring.io/spring-boot/reference/features/spring-application.html) — 事件順序、initializer 與 runner 的官方說明
- [SpringApplication 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java) — 九步骨架的第一手依據
- [ConfigDataEnvironmentPostProcessor 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/context/config/ConfigDataEnvironmentPostProcessor.java) — `application.yml` 真正的讀取者
