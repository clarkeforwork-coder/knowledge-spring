# starter-template

knowledge-spring 的**跟練起手專案**：一個最小但完整的 Spring Boot 骨架，讓你照著各章筆記動手時，不必每次從 start.spring.io 重新產生。

| 項目 | 內容 |
|---|---|
| 版本 | Spring Boot 4.1.2（Spring Framework 7.0.10、Tomcat 11.0.27）/ JDK 17 |
| 建置 | Maven（附 Maven Wrapper，本機不需安裝 Maven） |
| 依賴 | `webmvc`、`validation`、`actuator`，以及對應的 `webmvc-test`、`validation-test`、`actuator-test` |

> 這是本 repo 唯一的 Maven 專案。筆記裡「不跑看不出結果」的範例仍維持 `examples/` 下的 JBang 單檔，見 [CLAUDE.md](../CLAUDE.md)。

## 結構

```
starter-template/
├── mvnw / mvnw.cmd / .mvn/          Maven Wrapper
├── pom.xml
└── src/
    ├── main/java/com/example/starter/
    │   ├── Application.java          @SpringBootApplication 主類別
    │   └── HelloController.java      GET /hello（含 @Size 參數驗證）
    ├── main/resources/application.yml 開放 actuator health、info
    └── test/java/com/example/starter/
        ├── ApplicationTests.java     @SpringBootTest：整個 context 能起來
        ├── HelloControllerTest.java  @WebMvcTest：只測 Web 層（含 400 驗證失敗）
        └── ActuatorHealthTests.java  RANDOM_PORT 真起 Tomcat，RestTestClient 驗 /actuator/health 為 UP
```

## 啟動

```bash
cd starter-template
./mvnw verify            # 編譯＋跑測試＋打包
./mvnw spring-boot:run   # 啟動在 8080
```

實測輸出（JDK 17.0.19 Temurin、macOS）：

```bash
$ ./mvnw -B verify
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.025 s -- in com.example.starter.ApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.414 s -- in com.example.starter.ActuatorHealthTests
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.094 s -- in com.example.starter.HelloControllerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ curl localhost:8080/hello
Hello, Spring!

$ curl 'localhost:8080/hello?name=Clarke'
Hello, Clarke!

$ curl -i "localhost:8080/hello?name=xxxxxxxxxxxxxxxxxxxxx"   # 21 字，超過 @Size(max = 20)
HTTP/1.1 400 
Content-Type: application/json
...
{"timestamp":"2026-11-12T08:45:37.690Z","status":400,"error":"Bad Request","path":"/hello"}

$ curl localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

> 和 Boot 3.5 的實測輸出比，有兩處不同：
> - health 多出 `groups`：liveness／readiness 探針群組在本機也開著。加上 `--management.endpoint.health.probes.enabled=false` 啟動，輸出就回到 `{"status":"UP"}`（已實測）。
> - 錯誤回應的 `timestamp` 由 `+00:00` 變成 `Z` 結尾（Boot 4 預設的 JSON 函式庫是 Jackson 3，`tools.jackson` 套件）。

## 從 Boot 3 升上來改了什麼

只把 parent 版本改成 4.1.2 時，`./mvnw verify` 在測試編譯階段失敗（`WebMvcTest` 找不到）；其餘幾項是順著 Boot 4 的新 starter 結構一起調整，每項都實測過：

| Boot 3.5 | Boot 4.1 | 原因 |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | 舊名在 4.1.2 仍能用，但 POM 描述已標「deprecated in favor of spring-boot-starter-webmvc」 |
| `spring-boot-starter-test` 一個包全部 | `spring-boot-starter-webmvc-test` 等「技術名-test」starter | 測試切片跟著模組拆開；`webmvc-test` 已帶入 `starter-test` 與 `spring-boot-resttestclient` |
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` | 切片註解搬進 `spring-boot-webmvc-test` 模組，舊 import 直接編譯失敗 |
| （無，原本沒有 RANDOM_PORT 測試） | `RestTestClient` + `@AutoConfigureRestTestClient` | Framework 7 新增的測試 client；拿掉註解會得到 `NoSuchBeanDefinitionException`（已實測） |

本機沒有 JDK 17 時，可用 Docker 跑：

```bash
docker run --rm -v "$PWD":/ws -w /ws eclipse-temurin:17 ./mvnw verify
```

## 怎麼拿來跟著筆記練習

1. **複製一份再改**：`cp -r starter-template ~/playground/xxx`，範本本身保持乾淨，可以一直重用。
2. **缺什麼依賴就加什麼**：例如練 02 章 `@Cacheable` 時在 `pom.xml` 加 `spring-boot-starter-cache`；版本由 parent 管理，不用寫 `<version>`。
3. **把筆記裡的 snippet 貼進 `com.example.starter` 底下**：放在主類別的同一個 package（或子 package），元件掃描才掃得到。
4. **用測試驗證行為**：仿照 `HelloControllerTest` 寫 `@WebMvcTest`，或用 `ApplicationTests` 的 `@SpringBootTest` 起完整 context。
5. **觀察內部狀態**：需要更多 actuator endpoint（`beans`、`conditions`、`env`…）時，在 `application.yml` 的 `management.endpoints.web.exposure.include` 加上去，搭配 [03 章 Actuator 筆記](../03-boot-internals/actuator.md)。
