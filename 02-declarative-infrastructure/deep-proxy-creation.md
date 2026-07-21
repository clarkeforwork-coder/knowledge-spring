# proxy 是何時、被誰裝上的：AbstractAutoProxyCreator 追讀

## 前言

這一章五篇筆記，每篇都說了同一句話：「又是 proxy」。`@Async` 靠它換 thread、`@Cacheable` 靠它攔快取、`@Validated` 靠它驗參數、`@Retryable` 靠它重來——第 01 章還見過它三次側臉：[after-init 的產房](../01-core-container/bean-post-processor.md)、[循環依賴裡的早期引用](../01-core-container/deep-circular-dependency.md)、警告訊息裡的 `$$SpringCGLIB$$`。

欠的帳這篇清算。四個問題：proxy **何時**被裝上？**誰**決定要不要裝？**JDK 與 CGLIB** 怎麼選（Boot 為什麼強制選邊）？以及 `@Configuration` 類別身上那個 CGLIB——**跟 AOP 是同一回事嗎**？

> 🔬 追讀版本：spring-framework **6.2.x**（實驗以 spring-context 6.2.8 執行）。
> 前置閱讀：[BeanPostProcessor](../01-core-container/bean-post-processor.md)（工人與掉包權）、[knowledge-java 的 proxy 兩種製法](../../knowledge-java/03-spring-to-spring-boot/deep-transactional-self-invocation.md)。

## 技術背景

### 先破除誤解：Spring AOP 不是織入系統，是「BPP＋兩種標準 proxy 技術」

Spring AOP 沒有改你的 bytecode（那是 AspectJ compile-time／load-time weaving 的路線）。它的全部魔法是：**一個 BeanPostProcessor 在 after-init 站，用 JDK dynamic proxy 或 CGLIB 造一個替身，回傳給容器**。掉包權來自 [BPP 的契約](../01-core-container/bean-post-processor.md)：「回傳值就是進容器的東西」。

### 何時、誰：AbstractAutoProxyCreator 家族

裝配時機有兩個（第二個你已經深挖過）：

1. **常規路徑**：`postProcessAfterInitialization` → `wrapIfNecessary()`——初始化完成後掉包
2. **循環依賴路徑**：`getEarlyBeanReference()`——早期引用被領走時提前造 proxy（[三級快取篇](../01-core-container/deep-circular-dependency.md)的主角）

「誰」則是一個小家族，不同的 `@Enable` 僱不同的工人：

| 工人 | 僱主 | 管的事 |
|---|---|---|
| `AnnotationAwareAspectJAutoProxyCreator` | `@EnableAspectJAutoProxy`（Boot 自動） | `@Aspect` 切面 |
| `InfrastructureAdvisorAutoProxyCreator` | `@EnableTransactionManagement` | `@Transactional` 的 advisor |
| `AsyncAnnotationBeanPostProcessor` | `@EnableAsync` | 自帶 advisor 的獨行俠 |

多個 `@Enable` 共存時容器只留**一個** auto-proxy creator（`AopConfigUtils` 會把它升級成能力最強的那位），獨行俠們則把自己的 advisor **加進既有的 proxy**——這就引出下一個關鍵設計：

### 疊鏈，不疊娃

一顆 bean 同時要 `@Async`＋切面＋交易，**不會**被包三層 proxy——是**一顆 proxy、裡面串一條 advisor 鏈**。實驗二的鐵證：

```
實際型別：ReportService$$SpringCGLIB$$0     ← 只有一層
advisor 清單：
  - AnnotationAsyncExecutionInterceptor     ← @Async（刻意排最前：先切 thread，其餘advice到新thread上跑）
  - ExposeInvocationInterceptor             ← 框架自動插入的工具 advisor
  - AspectJMethodBeforeAdvice               ← 我們的 @Aspect
```

每次方法呼叫依序過鏈（`ReflectiveMethodInvocation.proceed()`——你在各篇 stack trace 裡見過它十次了）。Advisor＝pointcut（切哪裡）＋advice（做什麼）；`wrapIfNecessary` 的判斷就一句：**找得到適用的 advisor 才包，找不到就原樣放行**——這也是為什麼「沒有任何切面需求的 bean 拿到的是本尊」。

### JDK vs CGLIB：十行決策原始碼

`DefaultAopProxyFactory.createAopProxy()`（6.2.x 原文）：

```java
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
    if (config.isOptimize() || config.isProxyTargetClass() ||
            !config.hasUserSuppliedInterfaces()) {
        Class<?> targetClass = config.getTargetClass();
        if (targetClass == null || targetClass.isInterface() ||
                Proxy.isProxyClass(targetClass) || ClassUtils.isLambdaClass(targetClass)) {
            return new JdkDynamicAopProxy(config);
        }
        return new ObjenesisCglibAopProxy(config);
    }
    else {
        return new JdkDynamicAopProxy(config);
    }
}
```

翻成人話：**沒開 `proxyTargetClass` 且 bean 有介面 → JDK proxy（以介面現身）；否則 CGLIB（子類覆寫）**——邊角例外是 target 本身是介面／lambda 時仍走 JDK。

而 **Spring Boot 把 `proxyTargetClass` 預設開成 true**（`spring.aop.proxy-target-class`），全軍統一 CGLIB。原因就是實驗一那顆型別炸彈：JDK proxy 只是「長得像介面的東西」，**用實作類別去領它會當場炸**——

```
Bean named 'payService' is expected to be of type 'ProxyAutopsy$PayService'
but was actually of type '$Proxy18'
```

在大型專案裡，「有人用實作類別注入」是遲早的事——Boot 用一點 CGLIB 成本，買斷整類地雷。

### 兩種 proxy 的能力差異

| | JDK dynamic proxy | CGLIB |
|---|---|---|
| 現身方式 | 實作**介面**的合成類（`$ProxyN`） | **子類**（`$$SpringCGLIB$$0`） |
| 沒有介面 | 做不到 | 可以 |
| 用實作類別注入 | ❌ 炸型別（實驗一） | ✅ |
| final 類別 | 無所謂（不繼承） | ❌ 啟動報錯 |
| **final 方法** | 無所謂 | ⚠️ **無聲跳過**（實驗三）——覆寫不了、也不報錯 |
| 建構子 | 不涉及 | 用 Objenesis 繞過（proxy 實例不跑你的建構子） |

最陰的是 final 方法那格：切面、交易、快取掛在 final 方法上，**沒有任何錯誤，只是不生效**——本章「無聲失效」家族的最後一位成員，而且比 self-invocation 更難找（連呼叫方式都是對的）。

### @Configuration 的 CGLIB：同一把刀，另一個目的

config 類別的 `$$SpringCGLIB$$` **不是 AOP**——增強它的是 `ConfigurationClassEnhancer`，攔截的是 **`@Bean` 方法互相呼叫**：`bar()` 裡呼叫 `engine()` 時，不是真的執行方法，是**轉頭去容器拿那顆 singleton**（實驗四：full 模式下兩次 `engine()` 拿到同一顆、就是容器那顆）。

`@Configuration(proxyBeanMethods = false)`（lite 模式）關掉這層增強：`engine()` 就是普通方法呼叫、**每次 new 新的**（實驗四下半，`false／false`）。lite 換到的是啟動速度與 AOT 友善——Boot 的自動配置類**全部**是 lite 模式；代價是「@Bean 互呼叫拿 singleton」的直覺失效，依賴一律改用**方法參數**注入。

## 實際案例

驗證環境：spring-context 6.2.8、aspectjweaver 1.9.22、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[ProxyAutopsy.java](examples/ProxyAutopsy.java)

### 實驗一：JDK proxy 的型別炸彈

```
getBean(PayApi.class) 的實際型別：$Proxy18
  [切面] 攔截到 pay()
getBean(PayService.class) 炸了：Bean named 'payService' is expected to be of type
'ProxyAutopsy$PayService' but was actually of type '$Proxy18'

=== proxyTargetClass = true → CGLIB ===
getBean(PayService.class) 的實際型別：ProxyAutopsy$PayService$$SpringCGLIB$$0
```

同一顆 bean、同一個切面，只差 `proxyTargetClass` 一個旗標：JDK 版以 `$Proxy18` 現身（只認介面），CGLIB 版以子類現身（實作類別也能領）。錯誤訊息裡的 `$Proxy18` 就是「介面注入 vs 實作注入」教訓的現場照。

### 實驗二：一顆 proxy、一條 advisor 鏈

`@Async`＋`@Aspect` 疊加，型別只有一層 `$$SpringCGLIB$$0`，`instanceof Advised` 為 true，advisor 三筆按序排列（async 攔截器在最前）。「疊娃」的心智模型可以扔了——**宣告式功能是共乘一顆 proxy 的**。

### 實驗三：final 的沉默

```
呼叫 nonFinal 方法：
  [切面] 攔截到 pay()
  pay() 本體執行
呼叫 final 方法（同樣標了 @Traced）：
  payFinal() 本體執行（final）
```

同一個類別、同一個註解，final 方法的切面**憑空消失**——CGLIB 覆寫不了 final，而且它選擇沉默。把這行輸出和 `@Transactional`／`@Cacheable` 連起來想一遍，寒意自然來。

### 實驗四：full vs lite 的 @Configuration

```
full 模式 config 類別型別：FullConfig$$SpringCGLIB$$0
bar() 裡呼叫兩次 engine()：同一顆？true；就是容器那顆？true
lite 模式 config 類別型別：LiteConfig
bar() 裡呼叫兩次 engine()：同一顆？false；就是容器那顆？false
```

`@Bean` 互呼叫的「singleton 直覺」是 CGLIB 給的，不是語言給的——lite 模式一關，那就是三顆不同的 `Engine`。

## 技術優缺點

### 這套 proxy 架構買到什麼

- **純 runtime**：不改編譯流程、不掛 agent（對照 AspectJ weaving），任何建置環境都能跑——Spring 能普及的工程原因之一
- **疊鏈設計**：N 個宣告式功能共乘一顆 proxy，開銷是一條鏈不是 N 層包裝；順序還可控（`@Order`）
- **Boot 統一 CGLIB**：消滅「介面注入 vs 實作注入」整類地雷，一個預設值省掉全公司的踩坑時間

### 代價與地雷

- **final 無聲跳過**：CGLIB 世界裡「final＋宣告式註解」是個不報錯的死組合——值得進 code review 清單甚至 lint 規則
- **self-invocation 的總根源**：`this` 不經過 proxy——本章四次實證（@Async／@Validated／@Retryable／事件篇的 @Async listener）的病灶都是這一條
- **每次呼叫過鏈**：advisor 鏈是真實的 per-call 成本，高頻小方法上疊一堆宣告式註解要掂量
- **debug 認知負擔**：`$Proxy18`／`$$SpringCGLIB$$0`、[欄位不轉發](cache-abstraction.md)、Objenesis 跳過建構子——不知道這些的人在 debugger 裡寸步難行

## 小結

- proxy 的「何時／誰」：**after-init 站的 auto-proxy creator 家族**（循環依賴時提前到 `getEarlyBeanReference`）；`wrapIfNecessary` 找得到 advisor 才包
- 多個宣告式功能＝**一顆 proxy＋一條 advisor 鏈**（實驗二三筆實錄），不是套娃
- 選型十行碼：**沒開 `proxyTargetClass` 且有介面 → JDK，否則 CGLIB**；Boot 預設全 CGLIB，為的是消滅實作類別注入的型別炸彈（實驗一原文）
- CGLIB 的死角：final 類別啟動報錯、**final 方法無聲跳過**（實驗三）——「無聲失效」家族的最後一員
- `@Configuration` 的 CGLIB 是**另一回事**（`@Bean` 互呼叫語意）；lite 模式下互呼叫＝普通方法呼叫（實驗四 true/true vs false/false），Boot 自動配置全是 lite

第 02 章到此完結：五個宣告式成員＋一篇 proxy 總清算。下一章換主角——把容器、proxy、自動配置整包產品化的 **Spring Boot**：`SpringApplication.run()` 那一行裡到底跑了什麼？內嵌 Tomcat 何時醒來？`application.yml` 何時被讀？第 03 章見。

## 常見面試題

1. Spring AOP 的 proxy 是什麼時候、由誰建立的？（提示：after-init 的 auto-proxy creator；循環依賴時提前；`wrapIfNecessary`）
2. JDK proxy 和 CGLIB proxy 怎麼選？Spring Boot 為什麼預設用 CGLIB？（提示：`createAopProxy` 十行決策；實作類別注入的型別炸彈）
3. 一顆 bean 同時有 `@Async`、`@Transactional`、自訂切面，會被包幾層 proxy？（提示：一層——advisor 鏈；順序可用 `@Order` 調）
4. `@Configuration` 的 CGLIB 增強和 AOP proxy 是同一回事嗎？`proxyBeanMethods = false` 有什麼影響？（提示：`@Bean` 互呼叫語意 vs 切面；lite 模式互呼叫＝每次 new）

## 延伸閱讀

- [Spring Framework 官方文件：Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) — JDK vs CGLIB 的官方說明與注意事項
- [AbstractAutoProxyCreator 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-aop/src/main/java/org/springframework/aop/framework/autoproxy/AbstractAutoProxyCreator.java) — `wrapIfNecessary` 與 `getEarlyBeanReference` 的第一手依據
- [DefaultAopProxyFactory 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-aop/src/main/java/org/springframework/aop/framework/DefaultAopProxyFactory.java) — 本文引用的十行決策
- [ConfigurationClassEnhancer 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassEnhancer.java) — `@Configuration` 那把「另一個目的的 CGLIB」
