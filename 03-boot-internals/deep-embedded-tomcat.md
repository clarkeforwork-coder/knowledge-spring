# 內嵌 Tomcat 是如何被啟動的

## 前言

這個問題懸了三章：[第 01 章的十二步表](../01-core-container/container-startup-refresh.md)裡，第 9 步 `onRefresh()` 標著「Boot 的內嵌 Tomcat 在這裡啟動」——但「啟動」到底是什麼意思？誰 `new` 的 Tomcat？`DispatcherServlet` 是怎麼被裝進去的（沒有 web.xml 的世界裡）？

追讀的起點是一個能實測的怪現象：Boot 啟動 log 裡 Tomcat 有**兩行**——`Tomcat initialized with port 8080` 和 `Tomcat started on port 8080`——中間可能隔著好幾秒，而且**這段期間 curl 連不上**。「啟動」原來是兩段式的：**建立**與**開門**是兩件事、發生在 refresh 的兩個不同步驟。這篇把兩段式的機制追完，順手回收第 01 章的 `SmartLifecycle`。

> 🔬 追讀版本：spring-boot **3.4.x**（實驗以 3.4.5 執行）／spring-framework 6.2.x。
> 前置閱讀：[refresh() 十二步](../01-core-container/container-startup-refresh.md)、[SpringApplication.run() 九步](springapplication-run.md)。

## 技術背景

### 先破除誤解：「Tomcat 在 onRefresh 啟動」只對一半

真相是兩段式：**第 9 步建立（引擎起動、port 不綁）→ 第 12 步開門（connector 掛上、開始接客）**。中間隔著整個第 11 步——所有 singleton 的量產。這個設計不是巧合，是**故意的 readiness gate**（後述）。

### 追讀一：誰覆寫了 onRefresh——context 的品種

[run() 的 d 步](springapplication-run.md)依 `WebApplicationType` 選 context 品種：SERVLET → `AnnotationConfigServletWebServerApplicationContext`。它的父類 `ServletWebServerApplicationContext` 覆寫了那個「留給子類的鉤子」（3.4.x 原文）：

```java
@Override
protected void onRefresh() {
    super.onRefresh();
    try {
        createWebServer();
    }
    catch (Throwable ex) {
        throw new ApplicationContextException("Unable to start web server", ex);
    }
}
```

第 01 章表格裡那格「子類鉤子」的主人，正式露臉。

### 追讀二：createWebServer()——建伺服器，並埋下開門的鑰匙

```java
private void createWebServer() {
    WebServer webServer = this.webServer;
    ServletContext servletContext = getServletContext();
    if (webServer == null && servletContext == null) {
        ServletWebServerFactory factory = getWebServerFactory();          // ①
        this.webServer = factory.getWebServer(getSelfInitializer());      // ②
        getBeanFactory().registerSingleton("webServerGracefulShutdown",
                new WebServerGracefulShutdownLifecycle(this.webServer));  // ③
        getBeanFactory().registerSingleton("webServerStartStop",
                new WebServerStartStopLifecycle(this, this.webServer));   // ★ 開門的鑰匙
    }
    ...
}
```

三個節點逐一拆：

**① `getWebServerFactory()`**：從容器找唯一的 `ServletWebServerFactory` bean。`TomcatServletWebServerFactory` 來自自動配置的條件裝配（`@ConditionalOnClass(Tomcat.class)`）——[starter 篇的讓位禮儀](custom-starter.md)在此應用：換 Jetty＝把 Tomcat 依賴換成 Jetty 依賴，工廠自動換人，程式碼零修改。

**② `factory.getWebServer(getSelfInitializer())`**：`getSelfInitializer()` 回傳一個 `ServletContextInitializer`——**這就是 `DispatcherServlet` 的入場通道**。Boot 沒有 web.xml、也**刻意繞過** Servlet 規範的 SCI（`ServletContainerInitializer`）classpath 掃描（Tomcat 收到的是 Boot 的 `TomcatStarter` 直接餵進來的 initializer）；容器裡的 `DispatcherServletRegistrationBean` 經由這條通道把 `DispatcherServlet` 註冊進 `ServletContext`。[knowledge-java 說的「Controller 不是入口、DispatcherServlet 才是」](../../knowledge-java/03-spring-to-spring-boot/spring-mvc-request-flow.md)——那個入口就是在這一行被裝上的。

而 `TomcatWebServer` 的建構子裡就呼叫了 `tomcat.start()`——引擎真的起動了（log 印出 `Tomcat initialized`），**但 Boot 先把 connector 拆了下來**：引擎轉、port 不綁，外面誰都連不進來。

**③ 兩顆手工註冊的 singleton**：`WebServerGracefulShutdownLifecycle`（[上一篇](executable-jar.md)那行 `Commencing graceful shutdown` 的出處——stop 階段反向運作）與 **`WebServerStartStopLifecycle`**。後者是個 `SmartLifecycle`——[第 01 章的知識](../01-core-container/container-startup-refresh.md)直接接上：它會在**第 12 步 `finishRefresh()`** 被 `LifecycleProcessor` 叫醒。

### 追讀三：開門在第 12 步

`WebServerStartStopLifecycle.start()` → `webServer.start()` → Tomcat 把**先前拆下的 connector 掛回去**、綁定 port → 發佈 `WebServerInitializedEvent`。至此才有第二行 log：`Tomcat started on port 8080`。

### 為什麼要拆成兩段？

因為**第 11 步量產期間不能接客**。如果 port 在第 9 步就開，request 會打進一個 bean 還沒建完、`@PostConstruct` 還沒跑的半成品容器。兩段式讓「port 開了」嚴格等價於「所有 singleton 就緒」——**一個內建於啟動順序的 readiness gate**，不需要任何配置（實驗一就是它的現場直播）。

### 請求跑在誰身上

開門之後，main thread 的工作就結束了（它跑完 runner 便功成身退）；接客的是 Tomcat 的執行緒池——`http-nio-8080-exec-N`（實驗二）。[第 02 章 @Async 篇](../02-declarative-infrastructure/async-under-the-hood.md)說的「主流程 thread」，在 web 場景裡指的就是這些 exec thread。

## 實際案例

驗證環境：spring-boot-starter-web 3.4.5、JDK 17（JBang，Docker 背景啟動＋host curl 探測）。

▶ 可執行範例：[TomcatWakeup.java](examples/TomcatWakeup.java)

### 實驗一：建立與開門之間，隔著整個第 11 步（內外雙證）

讓一個 bean 的 `@PostConstruct` 睡 8 秒（拉長第 11 步），內部 log 時間軸：

```
06:47:28.885  Tomcat initialized with port 8080 (http)          ← 第 9 步：建立
06:47:28      SlowBean @PostConstruct 開始（第 11 步）——睡 8 秒
06:47:36      SlowBean @PostConstruct 結束
06:47:37.394  Tomcat started on port 8080 (http)                ← 第 12 步：開門
06:47:37      WebServerInitializedEvent：port 8080 開門了
```

兩行 Tomcat log 相隔 **8.5 秒**——正是量產的耗時。外部同步佐證：host 端每秒 curl 一次，開門前全部連線失敗（curl exit 56），開門後第一發即 200。「initialized 卻連不上」不是網路問題，是設計。

### 實驗二：誰在處理請求

```
$ curl http://localhost:18083/whoami
處理我的執行緒：http-nio-8080-exec-2
```

### 實驗三：server.port=0——隨機埠與取回管道

```
Tomcat started on port 35751 (http)
WebServerInitializedEvent：port 35751 開門了（第 12 步）
server.port=0 → 實際綁定的 port=35751
```

port 設 0 讓 OS 隨機分配，實際埠號從 `WebServerInitializedEvent`（或 `getWebServer().getPort()`）取回——`@SpringBootTest(webEnvironment = RANDOM_PORT)` 平行測試不搶埠的機制基礎，第 08 章見。

## 技術優缺點

### 兩段式設計買到什麼

- **免費的 readiness gate**：port 開了＝全部 singleton 就緒——「啟動到一半就接客」這類半熟狀態被啟動順序天然消滅，一行配置都不用
- **伺服器是容器生態的一員**：factory 可條件替換（Tomcat／Jetty／Undertow）、customizer 可注入調參、graceful shutdown 以 lifecycle 對稱掛載——web server 不再是「容器外面那個東西」
- **無 web.xml、繞過 SCI 掃描**：`DispatcherServlet` 的註冊路徑完全在 Boot 掌控中——啟動可預測，不受 classpath 上其他 jar 的 SCI 驚喜干擾

### 代價與地雷

- **啟動期 port 全關**：k8s 的探針要容忍這段窗口（`startupProbe` 或足夠的 `initialDelay`）——探測太早只會得到 connection refused，被誤判成故障就進重啟循環
- **慢初始化直接延長不可服務窗口**：實驗一那 8 秒就是 8 秒的服務空白——重活該搬到 [runner](springapplication-run.md)（開門後才跑）或非同步預熱，而不是塞在 `@PostConstruct`
- **兩段式知識不普及**：「log 說 initialized 了怎麼連不上」是新人常見誤判——兩行 log 的差別值得寫進團隊 runbook

## 小結

- 內嵌 Tomcat 是**兩段式甦醒**：第 9 步 `onRefresh()` → `createWebServer()` **建立**（引擎起動、connector 拆下、port 不綁）；第 12 步 `WebServerStartStopLifecycle`（**SmartLifecycle**）**開門**——兩行 log `initialized`／`started` 各是一段（實測相隔＝第 11 步耗時，外部 curl 同步佐證）
- `DispatcherServlet` 經 **`ServletContextInitializer`**（`getSelfInitializer()`）裝進 Tomcat——沒有 web.xml、刻意繞過 SCI 掃描
- 工廠（`TomcatServletWebServerFactory`）由**條件裝配**提供——換伺服器＝換依賴
- 兩段式＝內建 readiness gate：port 開了嚴格等價於 singleton 全就緒——慢 `@PostConstruct` 直接延長不可服務窗口，重活搬到 runner
- `server.port=0` 隨機埠，實際埠從 `WebServerInitializedEvent` 取回（實測 35751）——平行整合測試的地基

第 03 章到此完結：run() 的九步、綁定、starter、Actuator、交付物、以及這篇的伺服器甦醒——Boot 的「身體」看完了。Roadmap 進入中期段，換第 05 章 **Spring Data JPA**：第 01 章那個 `@MapperScan` 靈異事件的正宗版——**Repository 介面為什麼不用寫實作**？

## 常見面試題

1. 內嵌 Tomcat 在啟動流程的哪一步建立、哪一步開始接受連線？為什麼要分開？（提示：onRefresh 建立／finishRefresh 的 SmartLifecycle 開門；readiness gate——兩行 log 為證）
2. 沒有 web.xml，`DispatcherServlet` 是怎麼被註冊進內嵌 Tomcat 的？（提示：`getSelfInitializer()` 的 `ServletContextInitializer` 通道；繞過 SCI 掃描）
3. `server.port=0` 是做什麼用的？怎麼拿到實際綁定的埠？（提示：隨機埠——平行整合測試；`WebServerInitializedEvent`／`getWebServer().getPort()`）

## 延伸閱讀

- [ServletWebServerApplicationContext 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/servlet/context/ServletWebServerApplicationContext.java) — `onRefresh`／`createWebServer` 與兩顆 lifecycle 的註冊現場
- [TomcatWebServer 原始碼（3.4.x）](https://github.com/spring-projects/spring-boot/blob/3.4.x/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/embedded/tomcat/TomcatWebServer.java) — connector 拆裝與 `initialized`／`started` 兩行 log 的出處
- [Spring Boot 官方文件：Embedded Web Servers](https://docs.spring.io/spring-boot/how-to/webserver.html) — 換伺服器、customizer 與 port 配置的官方指南
