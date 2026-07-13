# 循環依賴與三級快取

## 前言

第 01 章走到最後，欠著一題：兩顆 singleton **互相** `@Autowired`——A 要先有 B 才能完工、B 要先有 A 才能完工，先有雞還是先有蛋？直覺上這是死結，但很多老專案裡的循環依賴真的能啟動。

這篇追四個問題：容器**怎麼**解的？為什麼**建構子注入**就救不了？為什麼 Spring Boot 2.6 之後**預設拒絕**？以及最深的一題——**proxy 摻進循環**會發生什麼事？第四題的追蹤過程比答案精彩：一個「人人都知道會炸」的經典場景，在新版 Spring 上沒炸，一路查下去挖出一個幾乎沒人講的事實——**炸不炸，取決於 bean 的宣告順序**。

> 🔬 追讀版本：spring-framework **6.2.x**（實驗以 spring-context 6.2.8 執行，對照組 6.1.21）。
> 前置閱讀：[Bean 生命週期](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md)、[BeanPostProcessor](bean-post-processor.md)（proxy 誕生在 after-init 站）。

## 技術背景

### 先破除誤解：「Spring 能解循環依賴」的適用範圍比想像窄

能解的只有一種組合：**singleton ＋ 屬性／欄位注入**。建構子注入無解、prototype 無解、Boot 2.6+ 預設全部拒絕——而且（實驗四會證明）就算在能解的範圍內，摻入 proxy 後的結局還會隨版本、甚至隨 `@Bean` 宣告順序改變。

解法的本質一句話：**先把「半成品」的引用借出去**。而半成品要存在，前提是實例化（建構子）已經完成——這一句就同時解釋了「為什麼欄位注入能解」與「為什麼建構子注入無解」。

### 三張 Map：DefaultSingletonBeanRegistry

```java
// DefaultSingletonBeanRegistry（6.2.x）
/** Cache of singleton objects: bean name to bean instance. */
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);          // 一級：成品

/** Cache of early singleton objects: bean name to bean instance. */
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);      // 二級：半成品引用

/** Cache of singleton factories: bean name to ObjectFactory. */
private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);         // 三級：生產授權書
```

查詢順序（`getSingleton(name, allowEarlyReference)`）：一級 → 二級 → 三級。三級命中時執行那張 `ObjectFactory`，產物**搬進二級**、三級註銷。

### 走位：A ↔ B 欄位注入的完整過程

```
getBean(A)
 ├─ 實例化 A（建構子跑完，@Autowired 欄位還是 null）
 ├─ ★ addSingletonFactory("a", () -> getEarlyBeanReference(a))   ← 三級掛上授權書
 ├─ populate A：需要 B → getBean(B)
 │    ├─ 實例化 B、掛 B 的授權書
 │    ├─ populate B：需要 A → getSingleton("a")
 │    │     一級沒有 → 二級沒有 → 三級有！執行授權書
 │    │     → 拿到 A 的「早期引用」→ 搬進二級 → 注入給 B      ← 死結在這裡解開
 │    ├─ B 初始化完成 → 進一級（B 是完整的）
 │    └─ 回傳 B
 ├─ A 拿到 B、繼續初始化（@PostConstruct、after-init…）
 └─ A 完工 → 進一級
```

死結解開的關鍵：B 要的不是「完工的 A」，是「A 的**地址**」——欄位注入允許先給地址、之後再讓 A 慢慢完工，因為用到 `a.b` 的時刻遠在啟動之後。

### 為什麼是「三」級——第三級是為 proxy 準備的

如果早期引用永遠是本尊，二級就夠了（半成品直接放二級）。第三級放的是 **`ObjectFactory`（一段延遲執行的程式）**，目的只有一個：**把「曝光的是本尊還是 proxy」這個決定，推遲到循環真的發生的那一刻**。授權書的內容（6.2.x 原文）：

```java
// AbstractAutowireCapableBeanFactory（6.2.x）
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
    Object exposedObject = bean;
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (SmartInstantiationAwareBeanPostProcessor bp :
                getBeanPostProcessorCache().smartInstantiationAware) {
            exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
        }
    }
    return exposedObject;
}
```

它去問每個 `SmartInstantiationAwareBeanPostProcessor`：「這顆 bean 給早期引用時，要不要先包？」AOP 的 auto-proxy creator 實作了這個介面——需要切面的 bean，早期引用**直接給 proxy**（並記帳，避免 after-init 再包第二次）。沒發生循環，授權書從頭到尾不會執行，proxy 照常在 [after-init 站](bean-post-processor.md)出生。二級的存在則保證授權書**只執行一次**——多個下游拿到同一顆早期引用。

### 建構子注入為什麼無解

授權書要掛上三級，前提是實例已經 new 出來。建構子循環在「實例化」這一步就互相等待——快取裡**沒有任何東西可以借**。容器只能偵測後報錯（訊息見實驗二）。這也是官方一貫立場的礦源：建構子注入把循環依賴變成**啟動期的顯式錯誤**，而不是藏起來。

### Boot 2.6+ 的預設：拒絕整套機制

`DefaultListableBeanFactory` 有個開關 `allowCircularReferences`，Framework 預設 `true`；**Spring Boot 2.6 起預設把它關掉**（`spring.main.allow-circular-references=false`）——實驗三會證明「Boot 的行為」就是這一個 flag。官方的態度轉變值得玩味：機制還在、能力還在，但預設立場從「幫你擦屁股」變成「循環依賴是設計異味，啟動期就該面對」。

## 實際案例

驗證環境：spring-context 6.2.8（對照組 6.1.21）、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[CircularLab.java](examples/CircularLab.java)

### 實驗一～三：能解的、無解的、被禁的

```
=== 實驗一：欄位注入 A ↔ B（allowCircularReferences 預設 true）===
啟動   | 成功
身分驗證 | a.b 是容器裡那顆 B？true；b.a 是容器裡那顆 A？true

=== 實驗二：建構子注入 A ↔ B ===
啟動失敗 | Error creating bean with name 'ca': Requested bean is currently in creation:
          Is there an unresolvable circular reference or an asynchronous initialization dependency?

=== 實驗三：關掉 allowCircularReferences（Boot 2.6+ 的預設）===
啟動失敗 | Error creating bean with name 'a': Requested bean is currently in creation: …
```

實驗一的身分驗證是三級快取正確性的直接證據：借出去的半成品和最後的成品**是同一顆**。實驗三用的是和實驗一**完全相同的配置**，只多了一行 `setAllowCircularReferences(false)`——錯誤和實驗二如出一轍，證明 Boot 2.6+ 的「更嚴格」就是這個 flag。

### 實驗四：proxy 摻進循環——一路追下去的過程

這是本文的主戲，照時間軸記錄（🔬 的價值在「怎麼查到的」）：

**第一步：預期會炸，結果沒炸。** 讓 A2 帶一個 `@Async` 方法（after-init 會被包成 proxy）、與 B2 互相欄位注入。江湖知識說這會炸出著名的「raw version」錯誤——但 6.2.8 上啟動**成功**，只留下一句奇怪的 INFO：

```
INFO: Bean 'a2' marked for pre-instantiation (not lazy-init) but currently
initialized by other thread - skipping it in mainline thread
啟動      | 成功
a2 實際型別  | CircularLab$A2$$SpringCGLIB$$0
b2.a 實際型別 | CircularLab$A2$$SpringCGLIB$$0
身分驗證    | b2 拿到的 a 就是容器裡的 a2（proxy）？true
```

**第二步：排除第一個假說。** 「`@Async` 的 BPP 學會提前產 proxy 了？」用 `javap` 看 6.2.8 jar 裡的 `AbstractAdvisingBeanPostProcessor`：它確實實作 `SmartInstantiationAwareBeanPostProcessor`，但**沒有覆寫 `getEarlyBeanReference`**——假說不成立。

**第三步：檢查還在不在。** 讀 6.2.x 的 `doCreateBean`，raw-version 檢查**原封不動**：

```java
if (earlySingletonExposure) {
    Object earlySingletonReference = getSingleton(beanName, false);
    if (earlySingletonReference != null) {                    // ← 有人領走過早期引用
        if (exposedObject == bean) { ... }                    //    沒被包過 → 沒事
        else if (!this.allowRawInjectionDespiteWrapping
                 && hasDependentBean(beanName)) {             // ← 被包了、且有人拿的是 raw
            ...
            throw new BeanCurrentlyInCreationException(beanName,
                "Bean with name '" + beanName + "' has been injected into other beans …
                 in its raw version as part of a circular reference, but has eventually
                 been wrapped. …");
        }
    }
}
```

所以 6.2.8 不是刪了檢查，是**沒走進去**——`getSingleton(beanName, false)` 回了 null，代表 **a2 的早期引用從來沒被領走**。

**第四步：版本對照。** 同一份程式碼改跑 6.1.21——炸了，訊息全文：

```
Bean with name 'a2' has been injected into other beans [b2] in its raw version
as part of a circular reference, but has eventually been wrapped. This means
that said other beans do not use the final version of the bean. …
```

**第五步：關鍵實驗。** 在 6.1.21 上只做一個改動：把 `b2` 的 `@Bean` 宣告移到 `a2` 前面——**成功**，且 `b2.a` 是 proxy、身分一致。

**結論：炸不炸取決於「會被包的那顆是不是先建」。**

- **a2 先建**：a2 populate 時觸發 b2 → b2 populate 走三級快取**領走 raw a2** → a2 回頭在 after-init 被包成 proxy → 成品 ≠ 借出去的 → 檢查引爆
- **b2 先建**：b2 populate 觸發 a2 的**完整建立**（一般遞迴，不是早期引用）→ a2 populate 領走的是 **b2 的**早期引用（B2 沒有切面，raw 即成品，無害）→ a2 完工時已是 proxy → b2 拿到的直接是 proxy → 皆大歡喜
- **6.2.8 的新行為**：那句 INFO 顯示 mainline 在預實例化時**跳過了忙碌中的 a2**（6.2 重做的 singleton 鎖定機制），實質效果等於把順序翻成「b2 先建」——舊場景於是不藥而癒

一句話總結這個發現：**「你的循環依賴會不會炸」可能取決於 `@Bean` 的宣告順序、component-scan 的檔名排序、以及 Spring 的小版本**。網路上大量教材還在教 6.1 的行為——🔬 筆記標注版本不是儀式，是必要。

## 技術優缺點

### 三級快取買到什麼

- **老專案能跑**：歷史包袱重的系統裡循環依賴俯拾皆是，容器無感地解掉了大多數——這是 Spring 對「真實世界的爛攤子」的務實妥協
- **proxy 語意在循環下（多數時候）仍一致**：第三級的延遲決定讓 AOP bean 參與循環時，下游拿到的仍是 proxy（實驗一、實驗四順序對調版的身分驗證）

### 代價與地雷

- **掩蓋設計問題**：A 認識 B、B 認識 A，通常代表有一塊職責該抽成第三顆 bean、或該用[事件](application-events.md)解耦——容器把這個訊號吃掉了。Boot 2.6 的翻案就是官方對這筆帳的清算
- **半成品曝光窗口**：早期引用借出去時，A 的 `@PostConstruct` **還沒跑**——B 若在自己的初始化裡呼叫 `a.something()`，碰到的是未初始化完的 A。這類 bug 只在特定建立順序下現形
- **順序敏感是隱形炸彈**：實驗四證明同一份程式碼，宣告順序、掃描順序、框架小版本都可能翻轉生死——這種 bug 從 stack trace 幾乎無法反推根因
- **決策順位**：碰到循環依賴，**重構（抽第三顆 bean／事件解耦）＞ 單邊 `@Lazy`（注入 proxy 佔位）＞ 開回 `allow-circular-references`**——最後一項是把技術債合法化，留給遷移期用

## 小結

- 三級快取＝**成品／半成品引用／生產授權書（`ObjectFactory`）**——第三級的意義是「循環真的發生時，才決定曝光本尊還是 proxy」（`getEarlyBeanReference` 問的是 `SmartInstantiationAwareBeanPostProcessor`）
- 只解 **singleton＋屬性注入**；建構子注入無解（實例都沒有，快取沒東西可借）；**Boot 2.6+ 預設全面拒絕**＝`allowCircularReferences` 一個 flag（實驗證明）
- 早期引用在 `@PostConstruct` **之前**就借出去了——循環下，別人可能用到「還沒初始化完」的你
- **proxy 摻進循環的生死取決於建立順序**：會被包的那顆先建就炸（raw version 錯誤）、後建就活；6.2 的新 singleton 鎖定讓舊炸法不藥而癒——讀舊文章、答面試題都要對版本
- 循環依賴是 code smell：**重構 ＞ `@Lazy` ＞ 開 flag**

第 01 章到此完結。這章看了 proxy 三次側臉：after-init 的產房、循環裡的早期引用、config 類別的 `$$SpringCGLIB$$`——但 proxy 本人還沒正面登場：JDK 與 CGLIB 怎麼選？`@Configuration` 為什麼也要被 CGLIB 動手腳？`@Async`、`@Cacheable` 的失效為什麼都是同一種病？第 02 章〈宣告式基礎設施與代理〉見。

## 常見面試題

1. Spring 怎麼解決循環依賴？三級快取各放什麼？（提示：成品／半成品引用／`ObjectFactory` 授權書；查詢順序與搬移）
2. 為什麼建構子注入的循環依賴無解？（提示：曝光半成品的前提是實例已存在）
3. 為什麼需要第三級快取，兩級不行嗎？（提示：proxy 的延遲決定——`getEarlyBeanReference` 與 AOP 的早期代理）

## 延伸閱讀

- [DefaultSingletonBeanRegistry 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java) — 三張 Map 與 `getSingleton` 的第一手依據
- [AbstractAutowireCapableBeanFactory 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java) — `doCreateBean` 的早期曝光與 raw-version 檢查
- [Spring Boot 2.6 Release Notes：Circular References Prohibited by Default](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.6-Release-Notes#circular-references-prohibited-by-default) — 預設翻案的官方公告
