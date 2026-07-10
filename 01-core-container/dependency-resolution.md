# 依賴解析規則：@Qualifier、@Primary、泛型與集合注入

## 前言

每個 Spring 工程師都撞過這行字：

```
No qualifying bean of type '…' available: expected single matching bean but found 2
```

第二個支付通道、第二個通知管道上線的那天，`@Autowired` 從「理所當然」變成「它到底怎麼選的」。[上一篇](bean-post-processor.md)點名了填依賴的工人是 `AutowiredAnnotationBeanPostProcessor`——本文是那位工人的**選人規則書**。

先出一題自測：欄位宣告是 `@Autowired Notifier emailNotifier`，容器裡有 `emailNotifier` 和標了 `@Primary` 的 `smsNotifier` 兩顆候選——注入誰？答 EMAIL 的人，這篇是為你寫的（實測答案是 SMS）。

## 技術背景

### 先破除誤解：「@Autowired 是 by type」只對了第一關

流傳的口訣「`@Autowired` 按型別、`@Resource` 按名字」把一條四關的裁決鏈壓縮成了半句話。完整版：

```
① 按型別收集候選 —— 泛型參與比對（Store<Apple> 與 Store<Orange> 是不同型別）
② @Qualifier 篩選 —— 有標就先砍掉不合格的
③ 仍有多顆？依序裁決：@Primary →（@Priority）→ 欄位／參數名比對 bean 名
④ 仍不唯一 → NoUniqueBeanDefinitionException
   一顆都沒有 → NoSuchBeanDefinitionException（除非宣告了「可以缺席」）
```

兩個要劃線的位置：

- **名字比對是最後一關**，且排在 `@Primary` 之後——所以開頭那題答案是 SMS。靠欄位名選 bean 的程式，會被任何人在任何角落補上的一個 `@Primary` **無聲推翻**：不炸、不警告，注入結果就是變了
- `@Qualifier` 在第②關就把人砍完，輪不到 `@Primary` 出場——這是「`@Qualifier` 贏過 `@Primary`」的機制原因

### @Autowired vs @Resource：兩本相反的規則書

| | `@Autowired` | `@Resource` |
|---|---|---|
| 出身 | Spring | JSR-250（`jakarta.annotation`，標準註解） |
| 順序 | **型別**→ qualifier → primary → 名字 | **名字**（欄位名或指定 name）→ 型別 |
| 誰處理 | `AutowiredAnnotationBeanPostProcessor` | `CommonAnnotationBeanPostProcessor` |
| 適合 | 一般注入（配 `@Qualifier` 消歧義） | 明確要「按名字拿」的場合 |

同一行欄位宣告、只把註解從 `@Autowired` 換成 `@Resource`，注入結果就從 SMS 變 EMAIL（實測見案例三）——review 時看到有人「順手統一註解」，這就是要擋下來的理由。

### 集合注入：把「全部候選」變成一等公民

多顆候選除了「選一顆」，還可以**全收**：

```java
@Autowired List<Notifier> all;             // 全部實作，依 @Order 排序
@Autowired Map<String, Notifier> byName;   // key = bean 名，value = bean
```

- `@Order` **不參與**單顆裁決（那是 `@Primary` 的事），它只管集合注入的**排序**——兩者常被混為一談
- `Map` 注入是**策略模式的一行實現**：key 天然是路由鍵。通知路由、多支付通道、多險種計算器，都是這個形狀：

```java
@Service
class NotifyRouter {
    private final Map<String, Notifier> channels;   // 建構子注入，Spring 自動裝滿
    NotifyRouter(Map<String, Notifier> channels) { this.channels = channels; }
    void send(String channel, String msg) {
        channels.get(channel + "Notifier").name();  // 按 key 路由，新增通道零改動
    }
}
```

### 泛型是型別的一部分

`@Autowired Store<Apple>` 只會匹配泛型參數是 `Apple` 的那顆 bean（`ResolvableType` 機制）——不需要任何 `@Qualifier`。Spring Data 的一堆 `Repository<T, ID>` 互不打架，靠的就是這條規則。

### 允許缺席的三種寫法

```java
@Autowired(required = false) Missing m;      // 沒有就留 null
@Autowired Optional<Missing> m;              // 沒有就 Optional.empty()
@Autowired ObjectProvider<Missing> m;        // 用時再問：getIfAvailable() 回 null
```

`ObjectProvider` 在 [knowledge-java 講 prototype](../../knowledge-java/03-spring-to-spring-boot/bean-lifecycle-and-scope.md) 時出場過（延遲「向容器要」的時機）；「優雅處理可能不存在的 bean」是它的第二個用途——自動配置裡的 `@ConditionalOnBean` 場景常見它的身影。

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action` 執行）。

▶ 可執行範例：[PickingRules.java](examples/PickingRules.java)

### 案例一：兩顆候選、零提示——錯誤訊息全文

```
No qualifying bean of type 'PickingRules$Notifier' available:
expected single matching bean but found 2: emailNotifier,smsNotifier
```

讀法：第①關（型別）收到 2 顆、②③關都沒有線索可用（欄位名 `notifier` 也對不上任何 bean 名）、第④關宣判。訊息尾巴的**候選名單**就是你的選項清單——加 `@Qualifier` 挑一個，或加 `@Primary` 設預設。

### 案例二＆三：欄位名 fallback，以及它多脆弱

```
=== 沒有 @Primary：欄位名出面 ===
@Autowired Notifier emailNotifier | EMAIL

=== smsNotifier 標上 @Primary 之後 ===
@Autowired Notifier emailNotifier | SMS   ← @Primary 贏過欄位名！
@Resource  Notifier emailNotifier | EMAIL ← @Resource 名字優先，結論反過來
@Autowired @Qualifier("emailNotifier")    | EMAIL ← @Qualifier 贏過 @Primary
```

事故劇本照著唸：你的程式靠欄位名拿到 EMAIL 跑了兩年；某天同事給新的 `smsNotifier` 標了 `@Primary`（他只想影響**他的**注入點）——你的欄位無聲變成 SMS。**沒有編譯錯誤、沒有啟動警告**。這就是為什麼多實作場景的紀律是：要嘛顯式 `@Qualifier`，要嘛注入 `Map` 自己選，**別把業務正確性押在欄位名上**。

### 案例四：集合、Map、泛型、缺席者

```
List<Notifier>（依 @Order 排）         | [SMS, EMAIL, PUSH]
Map<String, Notifier>（bean 名為 key） | [emailNotifier, smsNotifier, pushNotifier]
@Autowired Store<Apple>               | 蘋果店
ObjectProvider<Missing>.getIfAvailable() | null
```

- `@Order(1)` 的 SMS 排最前，**沒標 `@Order` 的 PUSH 排最後**——集合順序可控，但預設順序別依賴
- `Store<Apple>` 在有兩顆 `Store` 的容器裡精準拿到蘋果店，零 qualifier
- 缺席的 `Missing` 沒有炸容器，`getIfAvailable()` 安靜回 null

## 技術優缺點

### 這套裁決鏈買到什麼

- **常態零配置**：八成的注入點只有一顆候選，第①關直接命中——規則書的存在感很低，是設計成功的地方
- **歧義可推理**：四關順序固定，任何注入結果都能沿著鏈解釋——比「玄學」高一個文明等級
- **集合與泛型注入**：策略模式一行、泛型精確匹配零樣板——這兩項是很多人沒用起來的白送能力

### 代價與地雷

- **優先序反直覺**：`@Primary` 壓過名字比對，而且「壓過」的方式是無聲的——名字 fallback 是整條鏈裡最脆弱的一關，卻是很多舊專案的默認依賴
- **換個註解換套規則**：`@Autowired` 與 `@Resource` 的裁決方向相反，混用的程式庫等於同時維護兩本規則書
- **重構的隱形炸彈**：改欄位名、rename bean、加 `@Primary`，都可能改變注入結果而不驚動編譯器——依賴解析的錯誤只在 runtime（甚至只在行為上）現形

## 小結

- `@Autowired` 的完整裁決鏈：**型別（含泛型）→ @Qualifier 篩選 → @Primary → 名字比對**——名字是最後一關，不是第一關
- **`@Primary` 贏過欄位名**（實測 SMS）；`@Qualifier` 又贏過 `@Primary`（第②關就砍完人）——靠欄位名選 bean 的程式會被一個 `@Primary` 無聲推翻
- `@Resource` 名字優先，與 `@Autowired` **方向相反**——同一行宣告換註解就換答案，別「順手統一」
- 多實作的正解：顯式 `@Qualifier`，或 `List`（`@Order` 排序）／`Map`（bean 名路由）全收——`Map` 注入＝策略模式一行
- 泛型參與型別比對（`Store<Apple>` 精準命中）；缺席用 `ObjectProvider`／`Optional`／`required=false` 表達

裁決鏈回答了「注入**誰**」；`@Value("${db.url}")` 的下一個問題是「值從**哪**來」——application.yml、環境變數、系統屬性打架時誰贏？見 [Environment、Profile 與 PropertySource](environment-profiles.md)。

## 常見面試題

1. `@Autowired` 遇到同型別多顆 bean 時的完整裁決順序？（提示：qualifier → primary → 名字；名字是最後一關）
2. `@Autowired` 和 `@Resource` 有什麼差別？（提示：型別優先 vs 名字優先——實測同一行宣告、不同答案）
3. 想注入某介面的「全部實作」並控制順序或按名字取用，怎麼寫？（提示：`List`＋`@Order`；`Map<String, T>`；`@Order` 不參與單顆裁決）

## 延伸閱讀

- [Spring Framework 官方文件：Fine-tuning Annotation-based Autowiring with @Primary / @Qualifier](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-primary.html) — 官方裁決規則
- [Spring Framework 官方文件：Injection with @Resource](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/resource.html) — 名字優先語意的出處
- [DefaultListableBeanFactory#determineAutowireCandidate 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java) — primary → priority → 名字，鏈條的第一手依據
