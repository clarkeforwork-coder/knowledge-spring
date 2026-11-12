# starter-template

knowledge-spring 的**跟練起手專案**：一個最小但完整的 Spring Boot 骨架，讓你照著各章筆記動手時，不必每次從 start.spring.io 重新產生。

| 項目 | 內容 |
|---|---|
| 版本 | Spring Boot 3.5.16 / JDK 17 |
| 建置 | Maven（附 Maven Wrapper，本機不需安裝 Maven） |
| 依賴 | `web`、`validation`、`actuator`、`test` |

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
        └── HelloControllerTest.java  @WebMvcTest：只測 Web 層
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
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ curl localhost:8080/hello
Hello, Spring!

$ curl 'localhost:8080/hello?name=Clarke'
Hello, Clarke!

$ curl -i "localhost:8080/hello?name=xxxxxxxxxxxxxxxxxxxxx"   # 21 字，超過 @Size(max = 20)
HTTP/1.1 400
Content-Type: application/json
...
{"timestamp":"2026-11-12T08:12:31.047+00:00","status":400,"error":"Bad Request","path":"/hello"}

$ curl localhost:8080/actuator/health
{"status":"UP"}
```

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
