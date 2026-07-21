# BeanPostProcessor：@Autowired 與 @PostConstruct 其實是誰做的

## 前言

問一個寫了很多年 Spring 的人：「`@Autowired` 的欄位是誰填的？」十有八九答「容器」。這個答案好到沒人會追問——但它是錯的。容器本人只做一件事：**照設計圖 new**。填依賴、叫 `@PostConstruct`、換 proxy，全是第 6 步到職的那批工人——`BeanPostProcessor`——做的。

[knowledge-java 講的那條生命週期流水線](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md)，本質上是 BPP 們的**排班表**；[上一篇](bean-definition-and-bfpp.md)講完設計圖怎麼定稿，本文把第 11 步施工現場的每個工位配上名字。證據用最硬的一種：**把工人拆掉**——同一個類別放進沒有註解工人的裸容器，`@Autowired` 與 `@PostConstruct` 當場全滅。

## 技術背景

### 先破除誤解：@Autowired 不是容器內建的

`@Autowired` 的支援是一個**插件**。`AnnotationConfigApplicationContext` 建構時自動註冊了一批「註解工人」——[上一篇案例一](bean-definition-and-bfpp.md#實際案例)數到的「容器自帶 5 張設計圖」就是他們。換成 `GenericApplicationContext` 手工登記設計圖、不請任何工人，同一個類別的 `@Autowired` 欄位就是 null、`@PostConstruct` 就是不跑（實測見案例一）。

容器與工人的分工，一句話：**容器管「生」，工人管「養」**。

### 工人的班表：每個工位配上名字

`BeanPostProcessor` 介面只有兩個鉤子（都吃 bean 回傳 bean）：

```java
Object postProcessBeforeInitialization(Object bean, String beanName);  // 初始化前
Object postProcessAfterInitialization(Object bean, String beanName);   // 初始化後
```

加上子介面 `InstantiationAwareBeanPostProcessor` 的 `postProcessProperties()`（填屬性站），流水線每一站的實際負責人是：

```
實例化（照圖 new / 呼叫 factory method）    ← 容器本人
填依賴 postProcessProperties               ← AutowiredAnnotationBeanPostProcessor（@Autowired、@Value）
                                            CommonAnnotationBeanPostProcessor（@Resource）
Aware 回調                                 ← ApplicationContextAwareProcessor（before-init 站的住戶）
before-init                                ← CommonAnnotationBeanPostProcessor 在這裡呼叫 @PostConstruct
初始化回調（afterPropertiesSet、initMethod）← 容器本人
after-init                                 ← AbstractAutoProxyCreator 在這裡換 proxy（AOP 產房）
就緒
```

| 工人 | 負責的註解／工作 | 站別 |
|---|---|---|
| `AutowiredAnnotationBeanPostProcessor` | `@Autowired`、`@Value` | 填屬性 |
| `CommonAnnotationBeanPostProcessor` | `@Resource`；`@PostConstruct`／`@PreDestroy` | 填屬性；before-init |
| `ApplicationContextAwareProcessor` | `ApplicationContextAware` 等 Aware 家族 | before-init |
| `AnnotationAwareAspectJAutoProxyCreator` | `@Transactional`／`@Async`…的 proxy 製造 | after-init |

所以「生命週期面試題」的標準圖，每一站都可以再追問一句**「誰做的」**——答得出名字，才算真的看過施工現場。

### 換 bean 的權力

兩個鉤子的回傳值**就是進容器的東西**。回傳原 bean＝放行；回傳別的物件＝**掉包**。AOP 整套宣告式魔法（`@Transactional`、`@Async`、`@Cacheable`）都建立在這一行契約上：after-init 站看到需要切面的 bean，回傳一個 proxy，從此容器裡住的是替身（實測見案例二——20 行寫出迷你 AOP）。

### 工人的兩條鐵律（也是兩顆地雷）

1. **工人與工人的依賴會早產**。BPP 在第 6 步實例化，它依賴的 bean 被迫跟著提早出生、錯過其他還沒到職的工人——容器會印那個著名的 WARNING（實測見案例三，訊息甚至直接點名兇手）
2. **工人只加工「在它之後出生」的 bean**。BPP 註冊有順序（`PriorityOrdered` → `Ordered` → 其他），且對已經出生的 bean 無能為力——「我的自訂 BPP 對某些 bean 沒生效」的排查起點永遠是：那顆 bean 是不是比你的工人先出生了

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action` 執行）。

▶ 可執行範例：[WorkerRoster.java](examples/WorkerRoster.java)

### 案例一：拆掉工人

同一個 `Svc`（`@Autowired Dep dep`＋`@PostConstruct`），放進兩種容器：

```
=== 裸容器（只登記設計圖，不請任何註解工人）===
Svc 狀態：dep = null、@PostConstruct 跑了嗎？false

=== AnnotationConfigApplicationContext ===
Svc 狀態：dep = WorkerRoster$Dep@57a3af25、@PostConstruct 跑了嗎？true
```

類別一個字沒改，差別只在容器有沒有請註解工人——**`@Autowired` 是插件，不是容器的本能**，一翻兩瞪眼。

### 案例二：自己當工人，20 行迷你 AOP

自訂 BPP 在 after-init 站看到 `@Loud` 註解，就用 JDK dynamic proxy 掉包：

```
容器給的 Greeter 實際型別：$Proxy13
  [proxy] hello() 進站
  hello!
  [proxy] hello() 出站
```

`getBean()` 拿到的型別是 **`$Proxy13`**——不是你 new 的 `LoudGreeter`。這就是 [knowledge-java 那篇 self-invocation 深入文](../../knowledge-java/03-spring-to-spring-boot/deep-transactional-self-invocation.md)說「proxy 和本尊是兩個物件」的出生現場：`@Transactional` 的工人做的事和這 20 行同構，只是包的邏輯換成開關交易。

### 案例三：早產警告，訊息會點名兇手

讓 BPP 的建構子依賴業務 bean `Dep`，啟動時（原文照貼，節錄）：

```
WARNING: Bean 'dep' of type [WorkerRoster$Dep] is not eligible for getting
processed by all BeanPostProcessors (for example: not eligible for auto-proxying).
Is this bean getting eagerly injected/applied to a currently created
BeanPostProcessor [eagerBpp]? Check the corresponding BeanPostProcessor
declaration and its dependencies/advisors.
```

翻譯：`dep` 為了餵飽正在建立的工人 `eagerBpp`，被迫在其他工人（例如 auto-proxying 的那位）到職前出生——它這輩子都不會被那些工人加工了。若 `dep` 上有 `@Transactional`，症狀就是**交易安靜地消失**。6.2.x 的訊息甚至在 `[eagerBpp]` 處直接點名肇事者，比舊版的 INFO 好查得多。修法依序：BPP 不要依賴業務 bean；真有需要，改成用時才取（`ObjectProvider`，[knowledge-java 講過的](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md)同一招）。

順帶一個彩蛋：警告裡 config 類別的型別印作 `AppEager$$SpringCGLIB$$0`——`@Configuration` 類別本身也被 CGLIB 動過手腳（這是 `@Bean` 方法互相呼叫仍拿到同一顆 singleton 的原因），這條線的完整清算見 [proxy 是何時、被誰裝上的](../02-declarative-infrastructure/deep-proxy-creation.md)。

## 技術優缺點

### 「容器管生、工人管養」的架構，買到什麼

- **可插拔**：註解支援本身是插件——案例一的裸容器證明核心容器不綁任何註解，這是 Spring 能一路從 XML 演化到註解再到 Boot 而核心不動的原因
- **統一的掉包點**：換 bean 的權力集中在兩個鉤子，AOP、`@Async`、快取、你的自訂包裝全走同一條門——框架和你用的是**同一個 API**，沒有特權
- **排查有名字**：知道每站的負責人，「@PostConstruct 沒跑」就能直接問「CommonAnnotationBeanPostProcessor 在不在、這顆 bean 是不是早產」——從玄學變成點名

### 代價與地雷

- **啟動成本**：每顆 bean × 每個 BPP 的乘法，兩邊數量都會隨專案長大
- **隱形掉包**：容器裡住的可能是替身，`getClass()` 才會揭穿——不知道這件事的人，會在 debugger 裡對著 `$Proxy13` 懷疑人生
- **早產鏈**：BPP 依賴誰、誰就脫離正常班表，而且警告不會讓啟動失敗——症狀（交易消失、切面失效）與原因隔著十萬八千里

## 小結

- 容器只負責**照圖 new**；`@Autowired`、`@Value`、`@Resource`、`@PostConstruct`、AOP proxy 全是 **BPP 工人**做的（裸容器實測：拆掉工人全滅）
- 每站有名字：填依賴＝`AutowiredAnnotationBeanPostProcessor`；`@PostConstruct`＝`CommonAnnotationBeanPostProcessor`（before-init）；換 proxy＝`AbstractAutoProxyCreator`（after-init）
- 鉤子的回傳值就是進容器的東西——**回傳別的物件＝掉包**，20 行就能寫出迷你 AOP
- 兩條鐵律：工人與其依賴會**早產**（WARNING 會點名兇手）；工人**只加工比它晚出生的 bean**
- 「not eligible for getting processed」不是雜訊——它預告了某顆 bean 的切面／交易將安靜失效

填依賴的工人還藏著一個大問題沒回答：`@Autowired` 一個型別、容器裡卻有**兩顆**候選 bean 時，它怎麼選？選不出來又怎麼辦？見[依賴解析規則：@Qualifier、@Primary、泛型與集合注入](dependency-resolution.md)。

## 常見面試題

1. `@Autowired` 是誰處理的？把那個處理器拿掉會發生什麼事？（提示：`AutowiredAnnotationBeanPostProcessor`；裸容器實測 dep = null）
2. `BeanPostProcessor` 的 before／after 兩站各住著哪些著名功能？（提示：`@PostConstruct`；AOP proxy 產房）
3. 啟動時看到「Bean 'xxx' is not eligible for getting processed by all BeanPostProcessors」代表什麼？該怎麼處理？（提示：早產——某個 BPP 依賴了它；後果是切面／交易失效）

## 延伸閱讀

- [Spring Framework 官方文件：Customizing Beans by Using a BeanPostProcessor](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html#beans-factory-extension-bpp) — 官方定位與範例
- [AutowiredAnnotationBeanPostProcessor javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.html) — `@Autowired` 真正的處理者
- [PostProcessorRegistrationDelegate 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/context/support/PostProcessorRegistrationDelegate.java) — 工人註冊順序與 BeanPostProcessorChecker（早產警告）的出處
