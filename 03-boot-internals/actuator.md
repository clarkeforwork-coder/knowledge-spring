# Actuator：health、metrics 與 endpoint 安全

## 前言

服務上線第一天，SRE 的三個問題就到了：「健康檢查接哪？」「記憶體和 GC 看哪？」「現在生效的配置是哪一版？」自己寫 controller 一個個回答，是還沒認識 **Actuator**——[上一篇](custom-starter.md)講的「引依賴即生效」，Boot 自家用得最徹底的就是它：引一個 starter，整組營運端點就位。

但這包禮物有稜角，本篇三個實測問題就是稜角本身：為什麼引了 Actuator 卻**只看得到 `/health`**？為什麼 health 只回一行 `UP`、我寫的檢查細節不見了？把 `/env` 打開，**裡面的密碼會外洩嗎**？

## 技術背景

### 先破除誤解：Actuator ≠ /health

它是一整組營運端點家族（精選）＋兩個擴充模型：

| 端點 | 給你什麼 |
|---|---|
| `/health` | 聚合健康狀態（LB／k8s probe 的接口） |
| `/metrics` | Micrometer 度量（JVM、HTTP、連線池…可鑽取） |
| `/env` | [第 01 章那疊 PropertySource](../01-core-container/environment-profiles.md) 的 HTTP 版 |
| `/beans`、`/conditions` | 容器裡有誰／[自動配置為何生效](custom-starter.md)（條件報告的 HTTP 版） |
| `/loggers` | **執行期**調 log level（不用重啟） |
| `/threaddump`、`/heapdump` | 事故現場採證（[knowledge-java 排查工具箱](../../knowledge-java/01-jvm/troubleshooting-toolbox.md)的遠端版） |

擴充模型兩條：自訂 `HealthIndicator`（本篇實測）與自訂 `@Endpoint`（自家營運動作）。

### 兩道門：enabled ≠ exposed

端點要能打到，得過兩道門：**enabled**（功能開著嗎，預設幾乎全開）與 **exposed**（曝光到 web 了嗎，**預設只有 `health`**）。實測：預設狀態 `GET /actuator` 只列出 health，`/actuator/metrics` 直接 404。

```properties
management.endpoints.web.exposure.include=health,metrics,env   # 開第二道門
```

default-closed 是歷史教訓換來的：早年 Boot 全開曝光，`/env` 把資料庫密碼、雲端金鑰直接端給整個內網——**營運端點就是攻擊面**。

### health 的組裝線：從 indicator 到 HTTP 503

`/health` 不是一個檢查，是**一群 `HealthIndicator` 的聚合**：內建的（diskSpace、ping、有 DataSource 就多一個 db…）加上你自訂的，全部匯總——**任何一個 DOWN，整體就 DOWN**。自訂十行搞定：

```java
@Bean
HealthIndicator coreSystem(CoreClient client) {
    return () -> client.ping()
            ? Health.up().withDetail("coreApi", "ok").build()
            : Health.down().withDetail("coreApi", "連線逾時").build();
}
```

兩個實測重點：

- **狀態會映射成 HTTP status code**：DOWN → **503**（實測 `curl -i`）。LB 和 k8s 的 probe 不用解析 JSON，看狀態碼就能摘流量——這是 health 端點最重要的契約
- **細節預設隱藏**（`show-details=never`）：只回 `{"status":"UP"}`，連自訂 indicator 的存在都看不出來——細節裡常有內部拓撲資訊，這也是 default-closed 哲學。`show-details=always`（或 `when-authorized`）才展開

k8s 場景再進一步有 liveness／readiness **群組**（`/actuator/health/liveness` 等），把「活著」和「能接客」分開答——點到為止。

### metrics：Micrometer 是度量界的 SLF4J

`/metrics` 背後是 **Micrometer**——之於監控，如同 SLF4J 之於日誌：程式碼面向門面打點，後端（Prometheus、Datadog…）用 registry 依賴抽換。端點的用法是**先列名、再鑽取**：`/actuator/metrics` 列出所有指標名，`/actuator/metrics/jvm.memory.used` 看單指標的量測值與**可用 tag 維度**（實測：`area=heap/nonheap` 可以再往下切）。對接監控後端屬於可觀測性戰場，超出本 repo 範圍。

### env 與遮罩：Boot 3 的新預設

`/env` 能看到整疊 PropertySource——那密碼呢？實測：**Boot 3 預設遮罩所有值**（`show-values=never`）：

```json
{"source": "commandLineArgs", "value": "******"}
```

值被蓋掉、**來源照樣標明**——「這個 key 從哪來」的排查能力保留，機密不外洩。要看真值得顯式打開（`show-values=always`／`when-authorized`）。即便如此，端點本身仍是情報面，實務三層防線：**只曝必要端點 → Spring Security 保護 `/actuator/**`（第 06 章的戰場）→ `management.server.port` 把營運端點挪到內部 port**。

## 實際案例

驗證環境：spring-boot-starter-web＋actuator 3.4.5、JDK 17（JBang，Docker 背景啟動＋curl 實測）。

▶ 可執行範例：[ActuatorTour.java](examples/ActuatorTour.java)（檔頭附兩種啟動姿勢與 curl 清單）

### 實錄一：預設的封閉世界

```
GET /actuator          → _links 只有 self、health、health-path
GET /actuator/metrics  → HTTP 404
GET /actuator/health   → {"status":"UP"}
```

三行實錄講完 default-closed：metrics enabled 但沒曝光（404）；health 曝光但不給細節（自訂的 coreSystem indicator 已經在跑，你卻看不見它）。

### 實錄二：打開之後——DOWN 的完整解剖

模擬核心系統故障（`--demo.core-down=true`）＋開細節：

```
HTTP/1.1 503
{
  "status": "DOWN",
  "components": {
    "coreSystem": { "status": "DOWN",
                    "details": { "coreApi": "連線逾時", "lastSuccess": "2026-07-22T01:00:00Z" } },
    "diskSpace":  { "status": "UP", "details": { "free": 873274142720, ... } },
    "ping":       { "status": "UP" },
    "ssl":        { "status": "UP" }
  }
}
```

第一行就是重點：**HTTP 503**——一個 indicator 的 DOWN 蓋掉三個 UP、直接反映在狀態碼上，probe 契約成立。`components` 裡自訂與內建的並排聚合，故障細節（哪個依賴、最後成功時間）就是 on-call 的第一手線索。

### 實錄三：metrics 鑽取與 env 遮罩

```
GET /actuator/metrics/jvm.memory.used → measurements: 73864648 bytes；availableTags: area=[heap,...]
GET /actuator/env/my.secret           → {"source": "commandLineArgs", "value": "******"}
```

`P@ssw0rd` 沒有出現在任何回應裡——Boot 3 的預設遮罩用一行實測收案。

## 技術優缺點

### Actuator 買到什麼

- **營運介面標準化**：health／metrics／env 的格式全公司一致，SRE 的 runbook 和監控模板一次寫好到處用——比每個團隊自寫 `/status` 文明一個世代
- **probe 契約內建**：status→HTTP code 的映射讓 LB／k8s 零解析接入；liveness／readiness 群組把「活著」與「能接客」分開
- **Micrometer 門面**：換監控後端不動業務碼——SLF4J 的成功公式重演
- **排查端點是第一章的 HTTP 版**：`/env` 是疊層 dump、`/conditions` 是條件報告、`/beans` 是容器名冊——你在本 repo 學的排查手段全部有了遠端介面

### 代價與地雷

- **攻擊面**：env／beans／heapdump 都是情報金礦（heapdump 裡有記憶體中的明文機密）——default-closed 不是不方便，是在保護你；曝光決策要一個端點一個端點做
- **health 檢查寫太重**：indicator 每次 probe 都真打 DB／下游全家，等於讓監控流量壓垮自己——indicator 要輕（快取上次結果、設短 timeout）
- **DOWN 的連坐**：一個非關鍵依賴的 indicator DOWN 會讓整體 503、被 LB 摘掉——「哪些檢查該進 readiness」是設計決策，不是全塞
- **兩道門常被搞混**：enabled≠exposed，`include` 寫了沒生效的第一個檢查點永遠是「你開的是哪道門」

## 小結

- Actuator 是**營運端點家族**：兩道門控制可見性——enabled（預設幾乎全開）與 exposed（**web 預設只有 health**，實測 metrics 404）
- health＝**HealthIndicator 聚合**：一個 DOWN 全體 DOWN、**映射 HTTP 503**（實測 `curl -i`）——LB／k8s probe 的天然接口；細節預設隱藏（`show-details`）
- 自訂 indicator 十行接入，但要**輕**——監控流量不該壓垮被監控者
- metrics 走 **Micrometer 門面**：先列名再鑽取、tag 可切維度（實測 `jvm.memory.used`）
- `/env` 在 Boot 3 **預設遮罩值、保留來源**（實測 `******`＋`commandLineArgs`）——即便如此，營運端點的三層防線（少曝光、上安全、隔 port）仍是標配

營運端點有了，下一個問題輪到**交付物本身**：`mvn package` 出來的那顆 fat jar 憑什麼 `java -jar` 就能跑？裡面的 jar 套 jar 是怎麼被載入的？滾動更新時怎麼「說完最後一句話再下班」？見規劃中的〈可執行 jar 解剖、layered jar 與優雅停機〉。

## 常見面試題

1. Actuator 預設曝光哪些端點？想開更多要設什麼？（提示：兩道門——enabled vs exposed；`management.endpoints.web.exposure.include`）
2. health 檢查 DOWN 時 HTTP 回應是什麼？這個設計給誰用？（提示：503；LB／k8s probe 靠狀態碼摘流量；liveness vs readiness）
3. 自訂 HealthIndicator 怎麼寫？有什麼實務注意事項？（提示：`Health.up()/down().withDetail()`；要輕量、注意 DOWN 連坐整體）

## 延伸閱讀

- [Spring Boot 官方文件：Actuator Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) — 端點全集、曝光控制與安全建議
- [Spring Boot 官方文件：Health Information](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health) — indicator 聚合、show-details 與 probe 群組
- [Micrometer 官方文件](https://docs.micrometer.io/micrometer/reference/) — 度量門面的概念與 registry 生態
