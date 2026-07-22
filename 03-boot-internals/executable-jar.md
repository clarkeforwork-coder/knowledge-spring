# 可執行 jar 解剖、layered jar 與優雅停機

## 前言

`mvn package` 吐出一顆 20MB 的 jar，`java -jar` 就能跑——習以為常到沒人多想一秒。但停下來想就不對勁：**JDK 根本不支援「jar 裡的 jar」**——標準 classloader 讀不了嵌套 jar 裡的 class。Boot 是怎麼辦到的？

這是「交付物」的第一題。第二題在下線那一刻：滾動更新時 `kill` 掉舊實例，**正在處理的請求怎麼辦**？一筆保單試算跑到一半、一個對帳請求等著回應——連線被腰斬（實測給你看腰斬長什麼樣），還是「說完最後一句話再下班」？本篇把打包與停機兩件佈署大事一次實測完。

## 技術背景

### 先破除誤解：Boot 的 fat jar 不是「攤平」

把所有依賴的 class 解開攤進一顆 jar，那是 **shade／uber jar** 的做法——伴隨著 `META-INF` 服務檔互相覆蓋、同名資源衝突的老問題。Boot 走另一條路：**jar 套 jar，依賴原封不動**，再自帶一個能讀嵌套 jar 的 loader。實測解剖（結構節選）：

```
app-0.0.1.jar
 ├─ META-INF/MANIFEST.MF
 ├─ org/springframework/boot/loader/launch/JarLauncher.class   ← Boot 自帶的「殼」
 ├─ BOOT-INF/classes/demo/DemoApp.class                        ← 你的程式碼
 ├─ BOOT-INF/classes/application.properties
 ├─ BOOT-INF/lib/spring-boot-3.4.5.jar                         ← 依賴：完整的 jar，沒拆
 ├─ BOOT-INF/lib/tomcat-embed-core-10.1.40.jar
 └─ BOOT-INF/layers.idx                                        ← 分層索引（後述）
```

MANIFEST 裡藏著**雙主類**設計（實測原文）：

```
Main-Class: org.springframework.boot.loader.launch.JarLauncher   ← java -jar 的真入口
Start-Class: demo.DemoApp                                        ← 你的邏輯入口
```

啟動是**兩段式**：`java -jar` 啟動 `JarLauncher` → 它建一個看得懂 `BOOT-INF/` 嵌套結構的 classloader → 反射呼叫 `Start-Class` 的 `main` → 才輪到你熟悉的 [`SpringApplication.run()`](springapplication-run.md)。（classloader 的開放性讓這種花招成為可能——[knowledge-java 的 ClassLoader 深入篇](../../knowledge-java/01-jvm/deep-classloader.md)講過這個設計的另一面。）

還有一個常被忽略的實測細節：`target/` 裡躺著兩顆 jar——**20MB 的成品與 2.9KB 的 `.jar.original`**。`repackage` 是「加殼」不是「重編」：原始的普通 jar 被留档，這也是把模組發佈成別人依賴時要小心的點（要發佈的是 original，不是 fat jar）。

### layered jar：給容器映像的分層設計

容器時代的痛點：fat jar 是**一顆原子**——改一行程式碼，20MB（實務上幾百 MB）整層 image cache 失效，每次部署都重傳全量。Boot 的解法是把 jar 內容按**變動頻率**切層（`layers.idx` 預設就在，實測四層）：

```
dependencies              ← 最少變（第三方依賴）
spring-boot-loader
snapshot-dependencies
application               ← 最常變（你的 code 與資源）
```

配合多階段 Dockerfile 用 `jarmode` 展開：

```dockerfile
FROM eclipse-temurin:17-jre AS builder
COPY target/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:17-jre
COPY --from=builder extracted/dependencies/ ./
COPY --from=builder extracted/spring-boot-loader/ ./
COPY --from=builder extracted/snapshot-dependencies/ ./
COPY --from=builder extracted/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

效果：依賴沒動時，重建與推送只涉及 `application` 層（幾十 KB）——CI 快、registry 省、節點拉取快。

### 優雅停機：SIGTERM 之後的兩種死法

容器編排的下線流程都是同一句話開場：**SIGTERM**。收到之後有兩種死法（本篇都實測了）：

- **`immediate`（預設）**：立刻關——in-flight 請求連線被斬，客戶端拿到 Empty reply
- **`server.shutdown=graceful`**：**停收新連線 → 等 in-flight 完成（上限 `spring.lifecycle.timeout-per-shutdown-phase`）→ 才關**

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```

與 k8s 的配合是一道算術題：`terminationGracePeriodSeconds` 必須 **大於** 你的 timeout（否則 SIGKILL 先到）；再加 `preStop` sleep 讓 LB 先把流量摘乾淨。機制上，graceful shutdown 掛在 context close 流程裡，與[第 01 章的 `SmartLifecycle.stop()`](../01-core-container/container-startup-refresh.md) 同屬一族。

## 實際案例

驗證環境：拋棄式最小 Maven 專案（Boot 3.4.5，`spring-boot-starter-web`＋上述兩行 properties＋一個睡 5 秒的 `/slow` 端點），Docker `maven:3.9-eclipse-temurin-17` 打包、`eclipse-temurin:17-jre` 執行。

### 實錄一：解剖

結構、MANIFEST 雙主類、`20M vs 2.9K` 的成品對比——全文見技術背景（皆為實測原文）。

### 實錄二：四層清單

```
$ java -Djarmode=tools -jar app-0.0.1.jar list-layers
dependencies
spring-boot-loader
snapshot-dependencies
application
```

### 實錄三：優雅停機 vs 立即停機（同一顆 jar，一個參數之差）

**graceful**（5 秒請求進行到第 1 秒時 `docker stop`）：

```
>> /slow 開始處理（模擬 5 秒的工作）
INFO --- [ionShutdownHook] GracefulShutdown : Commencing graceful shutdown.
                                              Waiting for active requests to complete
>> /slow 處理完成
INFO --- [tomcat-shutdown] GracefulShutdown : Graceful shutdown complete

curl 拿到完整回應「慢工出細活，處理完成」、結束碼 0
docker stop 實耗 4.2 秒（等完請求就走，而不是耗滿 25 秒 timeout）
```

三個證據互相咬合：log 順序（先宣布停機、再等請求說完）、客戶端拿到 200、стоп 耗時剛好是請求剩餘時間。

**immediate**（`--server.shutdown=immediate` 覆蓋，同場景）：

```
docker stop 實耗 0.198 秒
curl 結束碼 52（Empty reply from server）、回應空白
```

0.2 秒下線的代價就是那個 52——**請求被腰斬**。滾動更新期間的「偶發 5xx／連線重置」事故，很多就是這 0.2 秒的效率換來的。

## 技術優缺點

### 這套交付設計買到什麼

- **單一交付物**：一顆 jar 就是完整應用（含伺服器），`java -jar` 是唯一的執行知識——比 war＋外部容器的年代少一整類環境問題
- **依賴不攤平**：嵌套 jar 保留了每個依賴的完整性，shade 時代的 `META-INF` 合併衝突整類消失
- **分層與容器共舞**：`layers.idx` 讓 image cache 命中率最大化——這是「Boot 認真對待容器時代」的具體證據
- **優雅停機一行開啟**：說完最後一句話再下班，從自己攔 signal 的黑魔法變成一行配置（實測從 52 變 200）

### 代價與地雷

- **自訂 loader 的兼容稅**：讀 `BOOT-INF` 需要 Boot 的 classloader——有些「掃 classpath」的老庫在 fat jar 裡行為異常；`.jar.original` 與 fat jar 的用途要分清（發佈依賴用前者）
- **graceful 不是免死金牌**：等待上限一到照樣強關；比 timeout 更長的請求（大報表、長輪詢）需要的是任務層的中斷設計，不是無限期等待
- **k8s 算術題寫錯全白搭**：`terminationGracePeriod` ≤ shutdown timeout 時，SIGKILL 會在 graceful 完成前到場——兩個數字必須一起 review
- **啟動多一段**：JarLauncher 與嵌套 jar 掃描是啟動成本的一部分——追極致冷啟動的場景（serverless）會改走 extract 後直跑或 AOT 路線

## 小結

- Boot fat jar＝**jar 套 jar＋自帶 loader**（非攤平）；MANIFEST 雙主類：`Main-Class=JarLauncher`（真入口）→ `Start-Class`（你的入口），兩段式啟動（實測解剖）
- `repackage` 是**加殼**：`.jar.original`（2.9KB）與成品（20MB）並存——發佈依賴用 original
- **layered jar** 按變動頻率切四層（實測 `list-layers`），配 `jarmode extract` 的多階段 Dockerfile——改 code 只重傳 application 層
- 優雅停機一行 `server.shutdown=graceful`：實測 **SIGTERM 後等完 in-flight 才關**（stop 耗 4.2s、客戶端 200）；immediate 對照組 **0.2s 下線、curl 52 腰斬**
- k8s 配合：`terminationGracePeriodSeconds ＞ timeout-per-shutdown-phase`＋`preStop` 摘流量——兩個數字一起 review

交付物解剖完了，只剩一個懸了三章的問題：`refresh()` 第 9 步 `onRefresh()` 裡，**內嵌 Tomcat 到底是怎麼被拉起來的**？誰 new 的它、request 又是怎麼從 Tomcat 的 thread 走進 `DispatcherServlet` 的？第 03 章的章末深入文見。

## 常見面試題

1. Boot 的 fat jar 和 shade/uber jar 有什麼不同？`java -jar` 是怎麼跑起來的？（提示：jar 套 jar＋JarLauncher 兩段式；雙主類）
2. layered jar 解決什麼問題？預設分哪幾層？（提示：容器映像快取；四層按變動頻率）
3. 優雅停機怎麼開？和 k8s 的 `terminationGracePeriodSeconds` 是什麼關係？（提示：`server.shutdown=graceful`＋phase timeout；grace period 必須更長，否則 SIGKILL 搶先）

## 延伸閱讀

- [Spring Boot 官方文件：The Executable Jar Format](https://docs.spring.io/spring-boot/specification/executable-jar/index.html) — 嵌套 jar 結構與 loader 的官方規格
- [Spring Boot 官方文件：Packaging Layered Jars](https://docs.spring.io/spring-boot/reference/packaging/container-images/efficient-images.html) — 分層與 Dockerfile 實踐
- [Spring Boot 官方文件：Graceful Shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html) — 各伺服器的停機語意與 timeout 設定
