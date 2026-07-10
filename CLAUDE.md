# knowledge-spring 專案規則

Spring 生態技術筆記 repo（knowledge-* 家族的一員，portfolio 的一部分）。
筆記以繁體中文撰寫。慣例大量繼承自 [knowledge-java](../knowledge-java/CLAUDE.md)。

## 範圍（嚴格遵守）

- 只收 **Spring 生態**：Framework 核心、Boot、MVC、Data JPA、Security、Batch、測試。
- Spring Cloud、微服務架構、Redis、訊息佇列、Docker 等主題**不收**，
  未來由其他 `knowledge-*` repo 承接。被要求寫這類主題時，先提醒範圍邊界。

## 與 knowledge-java 的分工（本 repo 特有）

- knowledge-java 第 03 章（Spring 到 Spring Boot）是**入門訓練主軌**；本 repo 是**深入與模組擴展**。
- 與該章重疊的主題（演進史、自動配置、AOP 入門、Bean 生命週期、@Transactional、
  MVC 請求流程、例外處理與 Validation）**不重寫基礎**：
  筆記前言連回 knowledge-java 對應篇，內容從該篇的結尾接下去。
- JPA/Hibernate 本體機制（N+1、一級快取、資料庫鎖）在 knowledge-java 08 章；
  本 repo 05 章只寫 **Spring Data 抽象層**，機制部分用連結承接。
- 跨 repo 連結用相對路徑 `../knowledge-java/...`。

## 雙軌制

- 🔰 **基礎**（訓練主軌）：教學式筆記。每章以寫完 🔰 軌為「完整」。
- 🔬 **深入**（選修軌）：檔名以 `deep-` 開頭，寧缺勿濫，只寫最有價值的主題。
- 🔰 筆記結尾以「🔬 想深入：[標題](deep-xxx.md)」連到對應深入文（有才放）。

## 筆記結構

每篇筆記必須遵守 [TEMPLATE.md](TEMPLATE.md)，🔰 與 🔬 共用五段骨架：

- `## 前言`（工作中會遇到的提問切入；有 knowledge-java 對應篇時在此連回）→
  `## 技術背景`（先破除誤解再進主體，主體用 ### 展開）→ `## 實際案例` →
  `## 技術優缺點`（優勢 vs 代價）→ `## 小結`（條列 3～5 條＋懸念鉤子）→
  附錄：`## 常見面試題`（1～3 題，只給方向提示）、`## 延伸閱讀`（官方 reference、原始碼優先）。
- 段落內規：先程式碼後解釋、對比用表格、反模式用 ❌/✅ 註解。
- 圖片選配：放該章 `attachment/img/`、以內容命名（kebab-case）；概念圖優先 ASCII。

## 範例驗證

- 筆記裡的程式碼輸出、行為描述**必須實測**，不可憑印象編造；
  實測與預期不符時，修正筆記內容而非數據。
- 版本基準：Spring Boot 3.x / Spring Framework 6.x / JDK 17（Temurin）。
- Spring 範例以 **JBang 單檔**驗證（檔頭 `//DEPS` 宣告依賴）。本機未裝 jbang 時走 Docker
  （已驗證可用，amd64 模擬）：
  `docker run --rm -v "$PWD":/ws -w /ws jbangdev/jbang-action Xxx.java`
- 原始碼追讀（🔬）標注所讀的版本（如 spring-framework 6.2.x tag）。

## 可執行範例（混合制）

- 預設文內 snippet，力求「複製進專案就能用」。
- 只有「不跑看不出結果」的主題（容器啟動順序、proxy 行為、快取命中、Batch 重啟）
  才附範例檔：放該章 `examples/` 子目錄、**單一 .java 檔（JBang）**，不引入 Maven/Gradle 專案。

## README 是索引與進度表

- 新增筆記時**必須**同步更新 README 目錄表（深度、狀態兩欄），並把規劃中的標題換成實際連結。
- 狀態：✅ 完成（符合模板）/ 🔧 待翻新 / 🚧 進行中 / 📝 待補。
- 章節資料夾（`NN-kebab-name/`）在該章第一篇筆記落地時才建立，不預建空資料夾。
- 寫作優先順序依 README 的 Roadmap（近期：01→02→03；中期：05→06→04；長期：07→08）。
