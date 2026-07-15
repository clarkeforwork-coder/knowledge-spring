# knowledge-spring

![notes](https://img.shields.io/badge/notes-8_/_46-blue)
![deep dives](https://img.shields.io/badge/🔬_deep_dives-1_/_6-purple)
![roadmap](https://img.shields.io/badge/roadmap-in_progress-yellow)
![Spring](https://img.shields.io/badge/Spring-6.x_/_Boot_3.x-6DB33F)
![Java](https://img.shields.io/badge/Java-17_&_21-orange)

Spring 生態技術筆記。[knowledge-java](../knowledge-java) 的第 03 章把 Spring 當作 Java 工程師的「必修入門」——本 repo 接在它後面，把 Spring 當作**主角**：容器機制、Boot 原理、以及 knowledge-java 沒空間展開的模組（Data JPA、Security、Batch）。

八個技術章，**規劃 46 篇筆記**。與 knowledge-java 同一套寫作紀律：每篇把「魔法」拆成機制，每個結論經過**實測**，筆記之間以跨章（跨 repo）連結互相印證。

> Part of my [portfolio](https://github.com/clarkeforwork-coder/portfolio) —
> naming convention: `knowledge-*` = technical notes.

**與 knowledge-java 的分工**

| | knowledge-java 03 章 | knowledge-spring（本 repo） |
|---|---|---|
| 定位 | 訓練主軌：Java 工程師的 Spring 必修 | Spring 專門：機制深入＋模組廣度 |
| 已涵蓋 | 演進史、自動配置入門、AOP 入門、Bean 生命週期、@Transactional 傳播與失效、MVC 請求流程、例外處理與 Validation | 承接上列主題**往下挖**（容器啟動、proxy 生成時機、Boot 啟動流程…） |
| 未涵蓋 | — | Spring Data JPA、Security、Batch、測試深入 |

重疊主題**不重寫基礎**：本 repo 的筆記從 knowledge-java 對應篇的結尾接下去，開頭連回該篇。

**範圍**：Spring Framework / Boot / MVC / Data JPA / Security / Batch / 測試。Spring Cloud、微服務架構、Redis、訊息佇列**不收**，維持 `knowledge-*` 家族的既有邊界，未來以其他 repo 承接。

## 筆記深度：雙軌制

| 標記 | 軌道 | 說明 |
|---|---|---|
| 🔰 | 基礎（訓練主軌） | 概念說明、可執行範例、常見陷阱。每章以寫完 🔰 軌為「完整」 |
| 🔬 | 深入（選修軌） | 原始碼、底層機制、效能分析。檔名以 `deep-` 開頭，從對應的 🔰 筆記連結過去。寧缺勿濫 |

## 目錄

### 01 - 容器核心

knowledge-java 講了「Bean 的一生」；這一章講**容器本身的一生**：誰讀進 BeanDefinition、誰在改它、誰把註解變成行為。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [ApplicationContext 啟動全景：refresh() 做了哪些事](01-core-container/container-startup-refresh.md) | 🔰 | ✅ |
| [BeanDefinition 與 BeanFactoryPostProcessor：Bean 的「設計圖」階段](01-core-container/bean-definition-and-bfpp.md) | 🔰 | ✅ |
| [BeanPostProcessor：@Autowired 與 @PostConstruct 其實是誰做的](01-core-container/bean-post-processor.md) | 🔰 | ✅ |
| [依賴解析規則：@Qualifier、@Primary、泛型與集合注入](01-core-container/dependency-resolution.md) | 🔰 | ✅ |
| [Environment、Profile 與 PropertySource](01-core-container/environment-profiles.md) | 🔰 | ✅ |
| [事件機制：ApplicationEvent 與 @EventListener](01-core-container/application-events.md) | 🔰 | ✅ |
| [循環依賴與三級快取](01-core-container/deep-circular-dependency.md) | 🔬 | ✅ |

### 02 - 宣告式基礎設施與代理

knowledge-java 用 @Transactional 講透了 proxy 失效；這一章把同一套機制推廣到**所有**「一個註解換一項基礎設施」的功能。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| [@Async：執行緒池在哪、例外去了哪](02-declarative-infrastructure/async-under-the-hood.md) | 🔰 | ✅ |
| @Cacheable：快取抽象與它的 key 地雷 | 🔰 | 📝 |
| @TransactionalEventListener：交易邊界上的事件 | 🔰 | 📝 |
| @Validated：方法級驗證 | 🔰 | 📝 |
| Spring Retry：@Retryable 與退避策略 | 🔰 | 📝 |
| proxy 是何時、被誰裝上的：AbstractAutoProxyCreator 追讀 | 🔬 | 📝 |

### 03 - Spring Boot 深入

knowledge-java 講了條件註解與自動配置的原理；這一章講 Boot 的**其餘身體**：啟動、綁定、打包、營運。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| SpringApplication.run() 到底跑了什麼 | 🔰 | 📝 |
| @ConfigurationProperties 與 relaxed binding | 🔰 | 📝 |
| 自製一個 starter | 🔰 | 📝 |
| Actuator：health、metrics 與 endpoint 安全 | 🔰 | 📝 |
| 可執行 jar 解剖、layered jar 與優雅停機 | 🔰 | 📝 |
| 內嵌 Tomcat 是如何被啟動的 | 🔬 | 📝 |

### 04 - Web 進階

knowledge-java 講了 DispatcherServlet 的一生與統一例外處理；這一章收**擴充點與週邊**。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| 自訂 HandlerMethodArgumentResolver 與 HttpMessageConverter | 🔰 | 📝 |
| CORS：機制與 Spring 的三個設定點 | 🔰 | 📝 |
| 檔案上傳與下載 | 🔰 | 📝 |
| 非同步請求：DeferredResult 與 SSE | 🔰 | 📝 |
| HTTP 客戶端世代交替：RestTemplate → WebClient → RestClient | 🔰 | 📝 |

### 05 - Spring Data JPA

knowledge-java 08 章講了 JPA/Hibernate 本體（N+1、一級快取、鎖）；這一章講 **Spring Data 這層抽象**。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| Repository 介面為什麼不用寫實作 | 🔰 | 📝 |
| 查詢三式：derived query、@Query、投影 | 🔰 | 📝 |
| 分頁與排序：Pageable 的正確姿勢 | 🔰 | 📝 |
| Specification：動態查詢 | 🔰 | 📝 |
| Auditing 與 @Version 樂觀鎖 | 🔰 | 📝 |
| Open Session in View：開還是關 | 🔰 | 📝 |
| 多資料源配置 | 🔰 | 📝 |
| SimpleJpaRepository 與查詢的誕生：原始碼追讀 | 🔬 | 📝 |

### 06 - Spring Security

| 筆記 | 深度 | 狀態 |
|---|---|---|
| Filter chain 架構：Security 不在 MVC 裡 | 🔰 | 📝 |
| 認證流程：AuthenticationManager 與 SecurityContext | 🔰 | 📝 |
| 授權：URL 規則與方法級安全 | 🔰 | 📝 |
| PasswordEncoder 與 UserDetailsService | 🔰 | 📝 |
| CSRF、CORS 與安全 headers | 🔰 | 📝 |
| JWT resource server | 🔰 | 📝 |
| SecurityContext 的 ThreadLocal 策略與非同步傳遞 | 🔬 | 📝 |

### 07 - Batch 與排程

| 筆記 | 深度 | 狀態 |
|---|---|---|
| @Scheduled 的單執行緒陷阱與執行緒池配置 | 🔰 | 📝 |
| Spring Batch 核心：Job、Step 與 chunk | 🔰 | 📝 |
| Batch 的重啟、skip 與 retry | 🔰 | 📝 |
| 大量資料的讀取策略與分割 | 🔰 | 📝 |

### 08 - 測試深入

knowledge-java 講了分層測試策略（@WebMvcTest / @DataJpaTest / @SpringBootTest）；這一章講**測試的成本與工程化**。

| 筆記 | 深度 | 狀態 |
|---|---|---|
| TestContext 與 context caching：測試為什麼慢 | 🔰 | 📝 |
| Testcontainers：對真資料庫做整合測試 | 🔰 | 📝 |
| @MockitoBean 的代價 | 🔰 | 📝 |

## Roadmap

- **近期（機制主軸）**：01 容器核心 → 02 宣告式基礎設施 → 03 Boot 深入。先把「容器＋proxy＋Boot」這條 knowledge-java 開的頭走完，形成完整的機制敘事線。
- **中期（模組廣度）**：05 Data JPA → 06 Security → 04 Web 進階。以工作實用度排序。
- **長期**：07 Batch 與排程 → 08 測試深入。

## 慣例

- 每篇筆記一個主題，結構依 [TEMPLATE.md](TEMPLATE.md)（與 knowledge-java 同一套五段骨架）：
  - 🔰 基礎：情境開場 → 概念段落（先程式碼後解釋）→ 重點 → 常見面試題 → 延伸閱讀
  - 🔬 深入：問題起點 → 追蹤過程 → 結論 → 回到實務
- 深度：🔰 基礎（訓練主軌）/ 🔬 深入（選修軌，檔名 `deep-` 開頭）
- 🔰 筆記結尾以「🔬 想深入：[標題](deep-xxx.md)」連到對應的深入筆記
- 與 knowledge-java 重疊的主題：開頭連回對應篇、不重寫基礎，從該篇結尾接下去
- 可執行範例採混合制：預設文內 snippet；需要跑起來才看得到結果的主題，
  在該章 `examples/` 附 **JBang 單檔**（檔頭 `//DEPS` 宣告 Spring 依賴，見 TEMPLATE.md）
- 狀態：✅ 完成（符合模板）/ 🔧 待翻新 / 🚧 進行中 / 📝 待補
