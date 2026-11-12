///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS org.springframework.boot:spring-boot-starter-web:3.4.5
//DEPS org.springframework.boot:spring-boot-starter-security:3.4.5

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Security filter chain 導覽：啟動在隨機 port，自己對自己發請求後結束。
 *
 *   docker run --rm -v "$PWD":/ws -w /ws \
 *     -v knowledge-spring-jbang:/root/.jbang -v knowledge-spring-m2:/root/.m2 \
 *     jbangdev/jbang-action FilterChainTour.java
 *
 * 觀察點：
 *   ① 兩層結構：Servlet filter 名冊裡只有一個 springSecurityFilterChain，裡面才是兩條 SecurityFilterChain
 *   ② 位置：401 時 interceptor／controller 一站都沒到；不存在的 URL 也是 401 而非 404
 *   ③ @RestControllerAdvice 接不到 AuthenticationException
 *   ④ 第二條 chain（/web/**）未登入是 302 → /login
 *   ⑤ 宣告成 @Bean 的 Filter 又被 addFilterAfter 加進 chain → 每請求跑兩次
 */
public class FilterChainTour {

    static final String KEY = "trace";

    public static void main(String[] args) throws Exception {
        String[] all = java.util.stream.Stream.concat(java.util.stream.Stream.of(
                "--server.port=0", "--logging.level.root=WARN", "--spring.main.banner-mode=off"),
                java.util.Arrays.stream(args)).toArray(String[]::new);
        ConfigurableApplicationContext ctx = SpringApplication.run(App.class, all);
        boolean permitError = ctx.getEnvironment().getProperty("demo.permit-error", Boolean.class, false);
        System.out.println("=== demo.permit-error = " + permitError + " ===");
        int port = ctx.getBean(PortHolder.class).port;

        printServletLayer(ctx);
        printSecurityLayer(ctx);

        String alice = "Basic " + Base64.getEncoder().encodeToString("alice:pw".getBytes());
        call(port, "/api/hello", null);
        call(port, "/api/no-such-thing", null);
        call(port, "/api/hello", alice);
        call(port, "/api/no-such-thing", alice);
        call(port, "/web/page", null);

        ctx.close();
    }

    static void printServletLayer(ConfigurableApplicationContext ctx) {
        System.out.println("=== ① Servlet 容器看到的 filter（ServletContext 名冊）===");
        ctx.getBean(jakarta.servlet.ServletContext.class).getFilterRegistrations().keySet().stream()
                .sorted().forEach(n -> System.out.println("  " + n));
        DelegatingFilterProxyRegistrationBean reg = ctx.getBean(DelegatingFilterProxyRegistrationBean.class);
        System.out.println("  → springSecurityFilterChain 的 order = " + reg.getOrder()
                + "（" + reg.getFilter().getClass().getSuperclass().getSimpleName() + "）");
    }

    static void printSecurityLayer(ConfigurableApplicationContext ctx) {
        FilterChainProxy proxy = ctx.getBean(FilterChainProxy.class);
        System.out.println("=== ① FilterChainProxy 裡的 SecurityFilterChain ===");
        int i = 1;
        for (SecurityFilterChain chain : proxy.getFilterChains()) {
            var c = (org.springframework.security.web.DefaultSecurityFilterChain) chain;
            System.out.println("  chain " + i++ + "  matcher = " + c.getRequestMatcher());
            c.getFilters().forEach(f -> System.out.println("      " + f.getClass().getSimpleName()));
        }
    }

    static void call(int port, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (auth != null) b.header("Authorization", auth);
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println();
        System.out.println(">>> GET " + path + (auth != null ? "（alice 登入）" : "（未登入）")
                + " → HTTP " + r.statusCode()
                + r.headers().firstValue("Location").map(l -> "  Location: " + l).orElse("")
                + r.headers().firstValue("WWW-Authenticate").map(w -> "  WWW-Authenticate: " + w).orElse(""));
        System.out.println("    過站：" + r.headers().firstValue("X-Trace").orElse("(無)"));
        if (!r.body().isBlank() && r.body().length() < 120) System.out.println("    body：" + r.body());
    }

    // 把每站的報到記在 request attribute，最外層 filter 在回應送出前寫進 header
    static void mark(ServletRequest req, String station) {
        Object prev = req.getAttribute(KEY);
        req.setAttribute(KEY, prev == null ? station : prev + " > " + station);
    }

    static class PortHolder {
        int port;
    }

    @Configuration
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import({Hello.class, Advice.class})
    static class App implements WebMvcConfigurer {

        @Bean
        PortHolder portHolder() {
            return new PortHolder();
        }

        @Bean
        org.springframework.context.ApplicationListener<WebServerInitializedEvent> portListener(PortHolder h) {
            return e -> h.port = e.getWebServer().getPort();
        }

        // 最外層的普通 Servlet filter：比 Security（-100）更早
        @Bean
        FilterRegistrationBean<Filter> outerFilter() {
            Filter f = (req, res, chain) -> {
                mark(req, "OuterFilter");
                HttpServletResponse resp = (HttpServletResponse) res;
                // 用 wrapper 攔住「回應提交」的時機，把過站紀錄塞進 header
                var wrapped = new jakarta.servlet.http.HttpServletResponseWrapper(resp) {
                    void stamp() {
                        if (!resp.isCommitted()) resp.setHeader("X-Trace", String.valueOf(req.getAttribute(KEY)));
                    }
                    @Override public void sendError(int sc) throws IOException { stamp(); super.sendError(sc); }
                    @Override public void sendError(int sc, String m) throws IOException { stamp(); super.sendError(sc, m); }
                    @Override public void sendRedirect(String l) throws IOException { stamp(); super.sendRedirect(l); }
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException { stamp(); return super.getOutputStream(); }
                    @Override public java.io.PrintWriter getWriter() throws IOException { stamp(); return super.getWriter(); }
                    @Override public void flushBuffer() throws IOException { stamp(); super.flushBuffer(); }
                };
                chain.doFilter(req, wrapped);
                wrapped.stamp();
            };
            FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(f);
            reg.setName("outerFilter");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return reg;
        }

        // ⑤ 反模式：Filter 宣告成 @Bean，Boot 會自動註冊成 Servlet filter；又被 addFilterAfter 加進 chain
        @Bean
        AuditFilter auditFilter() {
            return new AuditFilter();
        }

        // ⑤ 同樣雙重註冊，但繼承 OncePerRequestFilter
        @Bean
        OnceAuditFilter onceAuditFilter() {
            return new OnceAuditFilter();
        }

        @Bean
        @Order(1)
        SecurityFilterChain apiChain(HttpSecurity http, AuditFilter audit, OnceAuditFilter onceAudit) throws Exception {
            return http
                    .securityMatcher("/api/**")
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(c -> c.disable())
                    .addFilterAfter(audit, BasicAuthenticationFilter.class)
                    .addFilterAfter(onceAudit, BasicAuthenticationFilter.class)
                    .build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain webChain(HttpSecurity http, org.springframework.core.env.Environment env) throws Exception {
            boolean permitError = env.getProperty("demo.permit-error", Boolean.class, false);
            return http
                    .authorizeHttpRequests(a -> {
                        // ✅ 錯誤轉發（ERROR dispatch）到 /error 也會再過一次 Security：放行它，401 才不會被改寫
                        if (permitError) a.requestMatchers("/error").permitAll();
                        a.anyRequest().authenticated();
                    })
                    .formLogin(Customizer.withDefaults())
                    .build();
        }

        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}pw").roles("USER").build());
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                    mark(req, "Interceptor");
                    return true;
                }
            });
        }
    }

    static class AuditFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            mark(req, "AuditFilter");
            chain.doFilter(req, res);
        }
    }

    static class OnceAuditFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            mark(req, "OnceAuditFilter");
            chain.doFilter(req, res);
        }
    }

    @RestController
    static class Hello {
        @GetMapping("/api/hello")
        String hello(HttpServletRequest req) {
            mark(req, "Controller");
            return "hello";
        }
    }

    // ③ 想在這裡把認證失敗改成 418——它根本接不到
    @RestControllerAdvice
    static class Advice {
        @ExceptionHandler(AuthenticationException.class)
        ResponseEntity<String> onAuth(AuthenticationException e) {
            return ResponseEntity.status(418).body("advice caught it");
        }
    }
}
