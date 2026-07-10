# 筆記模板

與 [knowledge-java](../knowledge-java/TEMPLATE.md) 同一套骨架。
🔰 與 🔬 **共用同一套五段骨架**，差別在深度：
🔬 的「實際案例」是原始碼追讀、實驗或 benchmark；🔰 的是可執行範例與實務情境。

## 五段骨架

```markdown
# 標題

（封面圖，選配）

## 前言

用一個工作中會遇到的提問切入（「@Async 方法丟出的例外去哪了？」），
說明為什麼值得懂。2～3 段內。
與 knowledge-java 有對應篇時，在此連回：「本文從〈xxx〉的結尾接下去」。

## 技術背景

先破除一個常見誤解、或鋪必要的前置概念，再進入主體。
主體用 ### 子章節展開。

## 實際案例

一段完整的 code walkthrough 或實務情境。
🔰：可執行範例、部署踩坑、真實錯誤訊息。
🔬：原始碼追讀／實驗／benchmark——呈現「怎麼查到的」，不只是結論。

## 技術優缺點

這個設計的優勢 vs 代價（取捨），收在「這改變了什麼實務決策」。

## 小結

- 條列回顧 3～5 條帶走訊息
- 結尾放懸念鉤子，連到 Roadmap 上的下一篇：
  已完成的直接連結；未完成的寫「見規劃中的〈標題〉」，該篇完成後回頭補連結。
  🔰 有對應深入文時，鉤子連到 `deep-` 筆記。

## 常見面試題

1～3 題經典問法，附一句話方向提示（不寫完整解答，答案就在上文）。

## 延伸閱讀

- 官方文件（docs.spring.io reference）、原始碼、GitHub issue 優先；避免易失效的部落格連結
```

## 段落內規（骨架之下的寫法）

- **先程式碼、後解釋**——範例在前，文字說明在後
- 對比類主題用表格（如 Filter vs Interceptor）
- 反模式用 ❌/✅ 標注在程式碼註解裡
- snippet 力求「複製進專案就能用」；涉及版本差異時標注 Boot 版本

## 圖片慣例

- 圖片**選配**，不強制。
- 放該章 `attachment/img/`，以圖片內容命名（kebab-case），
  例如 `security-filter-chain.png`。
- 概念圖、流程圖**優先用 ASCII**（好 diff、不會有亂字）；
  AI 生圖適合當封面與氛圍圖，使用前檢查圖內文字是否有亂碼。
- 引用格式：`![內容描述](attachment/img/xxx.png)`。

## 可執行範例（混合制）

Spring 範例有外部依賴，`java Xxx.java` 單檔跑不起來，本 repo 改用 **JBang 單檔**：

- 預設文內 snippet 即可，**不**另附範例檔。
- 只有「不跑看不出結果」的主題才附：容器啟動順序、proxy 行為、快取命中、Batch 重啟等。
- 附檔放在該章的 `examples/` 子目錄，**單一 .java 檔**，檔頭以 `//DEPS` 宣告依賴：

  ```java
  ///usr/bin/env jbang "$0" "$@" ; exit $?
  //DEPS org.springframework.boot:spring-boot-starter:3.4.5

  // ...一般的 Spring 程式碼，main 裡 SpringApplication.run(...)
  ```

- 執行方式（本機未裝 jbang 時用 Docker，已驗證可用）：

  ```bash
  # 本機有 jbang
  jbang AsyncExceptionDemo.java
  # 沒有 jbang：Docker（jbangdev/jbang-action，amd64 模擬、稍慢但可用）
  docker run --rm -v "$PWD":/ws -w /ws jbangdev/jbang-action AsyncExceptionDemo.java
  ```

- 筆記內文用一行連過去：「▶ 可執行範例：[AsyncExceptionDemo.java](examples/AsyncExceptionDemo.java)」。
