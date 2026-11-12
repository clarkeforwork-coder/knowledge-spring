# Filter chain 架構：Security 不在 MVC 裡

## 前言

引進 Spring Security 的第一週，工作群組裡總會冒出這幾個問題：「我寫了 `@RestControllerAdvice` 處理 `AuthenticationException`，為什麼完全沒進來？」「打一個根本不存在的 URL，為什麼回 401 而不是 404？」「我的 API 明明是 httpBasic，未登入怎麼被 **302 轉去 `/login`**？」

三個問題其實同一個答案：**Spring Security 不在 Spring MVC 裡**。它整套是 Servlet filter，站在 `DispatcherServlet` 門外。只要搞清楚它站在哪一層、裡面又分了幾層，上面三題都推得出來。

本文從 knowledge-java〈[Spring MVC 請求處理流程](../../knowledge-java/03-spring-to-spring-boot/spring-mvc-request-flow.md)〉的結尾接下去：那篇的流程圖第①站寫著「Filter chain（Spring Security、編碼、CORS 都住這）」，也比較了 Filter 與 Interceptor 的分工。這篇把第①站拆開來看。

## 技術背景

### 先破除誤解：Security 不是「一個」filter，也不是「一串」filter

常見的想像是「Security 往 Tomcat 塞了十幾個 filter」。實測 ServletContext 的 filter 名冊（Servlet 容器眼中的全部 filter）：

```
Tomcat WebSocket (JSR356) Filter
auditFilter                  ← 本文範例自己的（後面會講它為什麼不該在這）
characterEncodingFilter
formContentFilter
onceAuditFilter              ← 同上
outerFilter                  ← 本文範例自己的
requestContextFilter
springSecurityFilterChain    ← Security 只占這一格，order = -100
```

Security 在 Servlet 層**只登記了一個 filter**：`springSecurityFilterChain`（一個 `DelegatingFilterProxy`）。那十幾個安全 filter 全都在它**裡面**，Tomcat 看不到。

### 三層套娃：DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain

```
Tomcat 的 ApplicationFilterChain（Servlet 規格層）
  │ characterEncodingFilter、formContentFilter、requestContextFilter ……
  │
  ├─ springSecurityFilterChain  ← DelegatingFilterProxy（order -100）
  │     │ 第①層：橋樑——Servlet 容器管它的生命週期，它把工作委派給 Spring bean
  │     ▼
  │   FilterChainProxy          ← 第②層：分派器——逐條比對 securityMatcher，第一條命中的接手
  │     ├─ SecurityFilterChain #1  matcher=/api/**   ← 第③層：真正幹活的那串 filter
  │     │     SecurityContextHolderFilter → … → BasicAuthenticationFilter → …
  │     │     → ExceptionTranslationFilter → AuthorizationFilter
  │     └─ SecurityFilterChain #2  matcher=any request
  │           … → CsrfFilter → … → UsernamePasswordAuthenticationFilter → …
  │
  ├─ （其他 order 更大的 Servlet filter）
  ▼
DispatcherServlet → HandlerMapping → Interceptor → Controller   ← MVC 從這裡才開始
```

| 層 | 誰 | 為什麼要有這層 |
|---|---|---|
| ① | `DelegatingFilterProxy` | Servlet 容器在 Spring 容器之外建立 filter；這座橋讓 filter 本體是一個 Spring bean，能注入、能被 AOP |
| ② | `FilterChainProxy` | 單一進入點：比對 matcher 選 chain、統一做防火牆檢查（`HttpFirewall`），請求結束時清掉 `SecurityContext` |
| ③ | `SecurityFilterChain`（可多條） | 每條 chain 有自己的 filter 組合。API 用 Basic/JWT、後台用表單登入，各管各的 URL 範圍 |

跟 [01 章 BeanPostProcessor](../01-core-container/bean-post-processor.md) 那套「註解背後是誰在做事」一樣，`http.httpBasic()` 這類 DSL 的實際效果就是**往第③層的清單加一個 filter**。啟動時把清單印出來，比讀任何文件都準。

### 位置決定行為：三個推論

Security 在 `DispatcherServlet` **之前**，下面三件事都是這個位置的結果：

| 現象 | 原因 |
|---|---|
| 不存在的 URL 回 **401** 不是 404 | 授權（`AuthorizationFilter`）比選路（`HandlerMapping`）早。請求在門外就被擋下，MVC 根本沒機會判斷「這條路不存在」 |
| `@RestControllerAdvice` 接不到 `AuthenticationException` | advice 是 `DispatcherServlet` 的 `HandlerExceptionResolver` 機制，管的是**門內**丟出的例外。門外的安全例外由 `ExceptionTranslationFilter` 接，交給 `AuthenticationEntryPoint`（→401）或 `AccessDeniedHandler`（→403） |
| 被擋下的請求，Interceptor 一站都不會經過 | Interceptor 在 `DispatcherServlet` 裡面，請求沒進門就沒有它的事 |

所以安全錯誤的回應格式要在 Security 裡自訂（自訂 `AuthenticationEntryPoint`／`AccessDeniedHandler`），不是在 advice 裡加 handler。

### 多條 chain：第一條命中的贏

```java
@Bean @Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/**")            // 只認 /api/**
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(c -> c.disable())
            .build();
}

@Bean @Order(2)
SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    return http                                       // 沒寫 securityMatcher = any request
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/error").permitAll()   // ✅ 為什麼需要這行，見實錄三
                    .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            .build();
}
```

`FilterChainProxy` 按 `@Order` 由小到大比對，**一個請求只走一條 chain**。兩個規則：

- `securityMatcher` 決定「這條 chain 管不管這個請求」；`authorizeHttpRequests` 決定「管了之後放不放行」。兩者常被搞混
- 沒寫 `securityMatcher` 的 chain 會吃下所有請求，必須排最後。順序排反的話，後面的 chain 永遠輪不到

## 實際案例

驗證環境：spring-boot-starter-web＋security 3.4.5（Spring Security 6.4.5、Tomcat 10.1.40）、JDK 17（JBang，Docker）。範例啟動在隨機 port，自己對自己發請求，每一站把站名記進 request attribute，最外層 filter 再把過站紀錄寫進回應 header `X-Trace`。

▶ 可執行範例：[FilterChainTour.java](examples/FilterChainTour.java)（`--demo.permit-error=true|false` 切換實錄三的兩種結果）

### 實錄一：第③層的 filter 清單

```
chain 1  matcher = Or [Mvc [pattern='/api/**']]
    DisableEncodeUrlFilter
    WebAsyncManagerIntegrationFilter
    SecurityContextHolderFilter
    HeaderWriterFilter
    LogoutFilter
    BasicAuthenticationFilter          ← .httpBasic() 加的
    AuditFilter                        ← addFilterAfter(BasicAuthenticationFilter) 插進來的
    OnceAuditFilter                    ← 同上
    RequestCacheAwareFilter
    SecurityContextHolderAwareRequestFilter
    AnonymousAuthenticationFilter
    ExceptionTranslationFilter         ← 把安全例外翻成 401/403
    AuthorizationFilter                ← 最後一關：放不放行
chain 2  matcher = any request
    DisableEncodeUrlFilter
    WebAsyncManagerIntegrationFilter
    SecurityContextHolderFilter
    HeaderWriterFilter
    CsrfFilter                         ← chain 1 關掉了，chain 2 預設開著
    LogoutFilter
    UsernamePasswordAuthenticationFilter   ← .formLogin() 加的
    DefaultResourcesFilter
    DefaultLoginPageGeneratingFilter       ← 那個預設登入頁就是它產生的
    DefaultLogoutPageGeneratingFilter
    RequestCacheAwareFilter
    SecurityContextHolderAwareRequestFilter
    AnonymousAuthenticationFilter
    ExceptionTranslationFilter
    AuthorizationFilter
```

兩條 chain 的組成不一樣，正好對應兩段 DSL：`.httpBasic()` 加了 `BasicAuthenticationFilter`，`.formLogin()` 加了三個表單相關的 filter，`.csrf(disable)` 拿掉了 `CsrfFilter`。注意 `ExceptionTranslationFilter` 排在 `AuthorizationFilter` 前面：後者丟出的例外往回傳，被前者接住並翻譯成 HTTP 回應。

### 實錄二：五個請求，看位置怎麼決定結果

（開啟 `/error` 放行，即 `--demo.permit-error=true`）

```
>>> GET /api/hello（未登入）          → HTTP 401  WWW-Authenticate: Basic realm="Realm"
    過站：OuterFilter > AuditFilter > OnceAuditFilter
>>> GET /api/no-such-thing（未登入）  → HTTP 401  WWW-Authenticate: Basic realm="Realm"
    過站：OuterFilter > AuditFilter > OnceAuditFilter
>>> GET /api/hello（alice 登入）      → HTTP 200
    過站：OuterFilter > AuditFilter > OnceAuditFilter > AuditFilter > Interceptor > Controller
>>> GET /api/no-such-thing（alice 登入）→ HTTP 404
    過站：OuterFilter > AuditFilter > OnceAuditFilter > AuditFilter > Interceptor
>>> GET /web/page（未登入）           → HTTP 302  Location: http://localhost:38611/login
    過站：OuterFilter
```

逐行對照「位置決定行為」那張表：

1. **未登入時 Interceptor 和 Controller 都沒出現**：請求在 `AuthorizationFilter` 就被擋下，沒進 `DispatcherServlet`
2. **不存在的 URL：未登入 401、登入後才 404**。先驗身分、後選路，順序就寫在這兩行結果裡
3. **advice 沒接到**：範例的 `@RestControllerAdvice` 寫了 `@ExceptionHandler(AuthenticationException.class)` 回 418，實測回應是 401。例外在 `DispatcherServlet` 之外就被 `ExceptionTranslationFilter` 處理掉了
4. **`/web/page` 走第二條 chain**：不在 `/api/**` 範圍，由 formLogin 的 chain 接手，未登入一律 302 轉登入頁

第 3 行的 `AuditFilter` 出現**兩次**，這是另一個陷阱，留到實錄四。

### 實錄三：API 的 401 變成 302——`/error` 的錯誤轉發

把 `/error` 的放行拿掉（`--demo.permit-error=false`），同樣的未登入 API 請求：

```
>>> GET /api/hello（未登入）  → HTTP 302  Location: http://localhost:35281/login;jsessionid=4816DB…
                                          WWW-Authenticate: Basic realm="Realm"
```

API client 收到一個 302 加上登入頁網址，而不是 401。過程如下：

```
① GET /api/hello → chain 1（/api/**）→ 未認證 → BasicAuthenticationEntryPoint：sendError(401)
② Tomcat 看到 sendError → 以 ERROR dispatch 轉發到 /error（Boot 的錯誤頁）
③ Boot 3 的 Security filter 也註冊在 ERROR dispatch 上 → /error 再過一次 FilterChainProxy
④ /error 不在 /api/** → 落進 chain 2（any request）→ 未認證 → formLogin 的 entry point：302 /login
```

回應裡同時有 chain 1 留下的 `WWW-Authenticate` 和 chain 2 寫的 302。兩條 chain 都對這個回應動過手，header 就是證據。修正就是 `webChain` 裡那行 `.requestMatchers("/error").permitAll()`，讓錯誤轉發能抵達 Boot 的 `BasicErrorController`。實錄二的 401 回應帶著 Boot 標準 JSON（`"error":"Unauthorized"`），就是放行後 `/error` 正常產生的。

只要有多條 chain、而且最後一條是表單登入，這個問題幾乎一定會遇到。單看程式碼找不出原因，因為 `/error` 這個 URL 不會出現在你的程式碼裡。

### 實錄四：雙重註冊——同一個 filter 跑了兩次

範例的 `AuditFilter` 是常見寫法：

```java
@Bean                                        // ❌ 宣告成 bean……
AuditFilter auditFilter() { return new AuditFilter(); }

http.addFilterAfter(audit, BasicAuthenticationFilter.class);   // ……又加進 chain
```

Boot 會把容器裡**所有 `Filter` 型別的 bean 自動註冊成 Servlet filter**（實錄開頭的名冊裡就有 `auditFilter`），所以它同時存在於兩層：

```
OuterFilter > AuditFilter（chain 1 裡，第③層）> OnceAuditFilter > AuditFilter（Servlet 層，第二次！）> Interceptor
```

`OnceAuditFilter` 也被雙重註冊了，卻只出現一次。因為它繼承 `OncePerRequestFilter`，在 request attribute 上做了「已執行」標記，第二次進來直接跳過。這個基底類別遮住了問題，但雙重註冊本身還在。另外，未登入的請求在 Security 就被擋下，到不了 Servlet 層的那一份，所以同一個 filter 有時跑一次、有時跑兩次，排查起來更麻煩。

修正方式擇一：

```java
// ✅ 方式一：不要宣告成 @Bean，直接 new 進 chain
http.addFilterAfter(new AuditFilter(), BasicAuthenticationFilter.class);

// ✅ 方式二：需要它是 bean（要注入別的依賴）時，關掉 Servlet 層的自動註冊
@Bean
FilterRegistrationBean<AuditFilter> auditFilterRegistration(AuditFilter f) {
    FilterRegistrationBean<AuditFilter> reg = new FilterRegistrationBean<>(f);
    reg.setEnabled(false);
    return reg;
}
```

這是 [Spring Security 官方文件](https://docs.spring.io/spring-security/reference/servlet/architecture.html#adding-custom-filter)明確提醒的寫法。Servlet filter 的自動註冊、`ServletContextInitializer` 通道，跟 [03 章 deep 篇](../03-boot-internals/deep-embedded-tomcat.md)裡 `DispatcherServlet` 進入 Tomcat 走的是同一條路。

## 技術優缺點

### 放在 filter 層買到什麼

- **與 Web 框架無關**：Security 只依賴 Servlet 規格，靜態資源、Actuator 端點、甚至不是 MVC 處理的 servlet 都在保護範圍內。放在 MVC 裡的話，`DispatcherServlet` 以外的東西就保護不到
- **拒絕得早、成本低**：未授權請求不進選路、不解析參數、不碰業務程式碼。實錄二未登入那兩行，過站紀錄只到 Security 為止
- **多條 chain 隔離**：API（無狀態、Basic/JWT、關 CSRF）和後台（session、表單登入、開 CSRF）各用一組 filter，互不干擾
- **可以檢查**：每條 chain 的 filter 清單可以在啟動時印出來（實錄一），安全配置有沒有生效，看清單就知道

### 代價與地雷

- **MVC 的工具用不上**：advice、Interceptor、`@ExceptionHandler` 都管不到安全錯誤。錯誤格式要另寫一套 entry point/handler，前後端錯誤 JSON 的一致性要自己維持
- **錯誤轉發會再過一次 Security**（實錄三）：多 chain 加上表單登入時，`/error` 沒放行就會把 401 改寫成 302。症狀出現在 API client，原因在另一條 chain
- **自訂 filter 容易被註冊兩次**（實錄四）：`@Bean` 加上 `addFilter*` 會讓 filter 同時存在於 Servlet 層和 Security 層。`OncePerRequestFilter` 會遮住這個問題
- **chain 順序是隱性依賴**：`@Order` 排錯時，沒寫 matcher 的 chain 會吃掉所有請求，後面的 chain 完全不生效，而且不會報錯

## 小結

- Security 在 Servlet 層**只占一格**（`springSecurityFilterChain`，order -100）。裡面有三層：`DelegatingFilterProxy`（橋接容器）→ `FilterChainProxy`（選 chain）→ `SecurityFilterChain`（實際執行的 filter 清單）
- 它在 `DispatcherServlet` **之前**，所以：不存在的 URL 未登入回 401（實測）、advice 接不到 `AuthenticationException`（實測 401 而非 418）、被擋的請求不經過 Interceptor
- 多條 chain 由 `securityMatcher` 依 `@Order` 比對，**第一條命中的處理**，沒寫 matcher 的放最後
- 錯誤轉發到 `/error` 也會過 Security。多 chain 時沒放行 `/error`，API 的 401 會變成 302 轉登入頁（實測兩種結果對照）
- 自訂 filter 不要同時宣告成 `@Bean` 又 `addFilter*`，否則會跑兩次（實測）。需要它是 bean 時，用 `FilterRegistrationBean.setEnabled(false)`

這篇看的是 filter 的排列。清單裡的 `BasicAuthenticationFilter`、`UsernamePasswordAuthenticationFilter` 在做什麼？它們把帳密交給誰驗證，驗證結果又存在哪裡，讓後面的 `AuthorizationFilter` 和你的 controller 都讀得到？見規劃中的〈認證流程：AuthenticationManager 與 SecurityContext〉。

## 常見面試題

1. Spring Security 是怎麼接上 Servlet 容器的？`DelegatingFilterProxy` 和 `FilterChainProxy` 各做什麼？（提示：三層結構；Servlet 容器的生命週期 vs Spring bean）
2. 為什麼 `@ControllerAdvice` 處理不了認證失敗？要怎麼自訂 401/403 的回應？（提示：filter 在 `DispatcherServlet` 之前；`ExceptionTranslationFilter`、`AuthenticationEntryPoint`、`AccessDeniedHandler`）
3. 一個應用要同時支援 API 的 token 認證和後台的表單登入，怎麼配置？（提示：多條 `SecurityFilterChain`＋`securityMatcher`＋`@Order`；別忘了 `/error`）

## 延伸閱讀

- [Spring Security 官方文件：Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html) — DelegatingFilterProxy、FilterChainProxy、多 chain、加自訂 filter 的官方說明（含雙重註冊的提醒）
- [Spring Security 官方文件：Handling Security Exceptions](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter) — `ExceptionTranslationFilter` 的流程
- [Spring Boot 官方文件：Servlet Filters 的註冊](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.servlets-filters-listeners) — Filter bean 自動註冊與 `FilterRegistrationBean`
- [FilterChainProxy 原始碼](https://github.com/spring-projects/spring-security/blob/6.4.5/web/src/main/java/org/springframework/security/web/FilterChainProxy.java) — `doFilterInternal` 裡的 chain 比對與 `VirtualFilterChain`
