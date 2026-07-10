# BeanDefinition 與 BeanFactoryPostProcessor：Bean 的「設計圖」階段

## 前言

用過 MyBatis 的 `@MapperScan` 或 Spring Data 的 `@EnableJpaRepositories` 都見過這個「靈異現象」：你只寫了**介面**，容器裡卻多出一堆你沒宣告過的 bean。它們是誰生的？在哪裡生的？

[上一篇〈ApplicationContext 啟動全景〉](container-startup-refresh.md)給了座標：第 5 步是「設計圖修改站」。本文把這一站打開來看：一張設計圖（`BeanDefinition`）上到底寫了什麼、誰有權**讀**它、**造**它、**改**它——看懂這三種權力，`@MapperScan` 的靈異現象、`@Conditional` 的判定時機、以及「怎麼用程式動態註冊 bean」就是同一件事的三個切面。

## 技術背景

### 先破除誤解：@Configuration 不是「被執行」，是「被讀」

註解只是躺在 class 檔裡的 metadata，自己不會跑。`@ComponentScan`、`@Import`、`@Bean` 之所以「生效」，是因為第 5 步有一個 `BeanFactoryPostProcessor`——`ConfigurationClassPostProcessor`——把你的配置類別**當資料讀**，讀完翻譯成一張張設計圖入庫。你寫的是宣告，翻譯是它做的。這也解釋了 `@Conditional` 的判定時機：**在翻譯的當下**（第 5 步），不是在 bean 建立時。

### 一張設計圖上寫了什麼

`BeanDefinition` 是個介面，欄位大致是「蓋這顆 bean 需要知道的一切」：

| 欄位 | 意思 |
|---|---|
| `beanClassName` | 直接 `new` 哪個類別 |
| `factoryBeanName` ＋ `factoryMethodName` | 或者：叫哪個 bean 的哪個方法來生 |
| `scope` | singleton／prototype…（空字串＝預設 singleton） |
| `lazyInit` | 要不要參加第 11 步的量產 |
| `constructorArgumentValues` / `propertyValues` | 建構子參數與屬性 |
| `dependsOn`、`primary`、`initMethodName`… | 其餘施工細節 |

重點在前兩列——**設計圖有兩種出身**，欄位長相完全不同（實測見案例一）：

| | `@Component` 掃描來的 | `@Bean` 方法來的 |
|---|---|---|
| `beanClassName` | ✅ 你的類別名 | **`null`** |
| `factoryBeanName/MethodName` | null | config 類別的 bean 名＋方法名 |
| 施工方式 | 反射呼叫建構子 | **呼叫你寫的方法** |

`@Bean` 的設計圖上根本沒有類別名——容器蓋它的方式是「去叫 `app` 這顆 bean 的 `orderService()` 方法」。這就是為什麼 `@Bean` 方法裡可以寫任意邏輯（讀設定、包裝、三元運算選實作），而 `@Component` 不行：一個是工廠方法，一個是照類別直接施工。

### 第 5 步其實有兩個小階段：先「造」後「改」

`BeanFactoryPostProcessor` 有個子介面 `BeanDefinitionRegistryPostProcessor`，第 5 步依序執行：

```
5a  BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry()
      → 可「登記新設計圖」。ConfigurationClassPostProcessor 就在這裡
        解析 @Configuration / @ComponentScan / @Import / @Bean
5b  BeanFactoryPostProcessor.postProcessBeanFactory()
      → 圖已收齊，可「讀圖、改圖」。placeholder 解析、你的自訂 BFPP 在這裡
```

先讓所有「會生圖的人」生完，再讓「改圖的人」看到完整的圖——順序反了，改圖的人會漏看後來才登記的圖。

### 「造」的正規姿勢：ImportBeanDefinitionRegistrar

想動態註冊 bean（數量、類型在編譯期不確定），標準管道是 `@Import` 一個 `ImportBeanDefinitionRegistrar`：

```java
@Import(TeamRegistrar.class)
@Configuration class App { }

class TeamRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata meta, BeanDefinitionRegistry reg) {
        for (String name : List.of("alpha", "beta")) {           // 迴圈、讀檔、掃介面……隨你
            BeanDefinition bd = BeanDefinitionBuilder
                    .genericBeanDefinition(Worker.class)
                    .addConstructorArgValue(name)
                    .getBeanDefinition();                        // 手工畫一張設計圖
            reg.registerBeanDefinition("worker-" + name, bd);    // 入庫
        }
    }
}
```

`@MapperScan` 生 Mapper、`@EnableJpaRepositories` 生 Repository，用的就是這個介面：掃描你的介面 → 每個介面畫一張「factory 生 proxy」的設計圖 → 入庫。你沒寫的 bean，是它們在第 5 步**算出來**的（Spring Data 那條線見規劃中的〈Repository 介面為什麼不用寫實作〉）。

順帶收編一個舊識：`@Import` 其實吃三種東西——普通配置類別、`ImportSelector`（回傳「要匯入哪些類別名」，[Boot 自動配置](../../knowledge-java/03-spring-to-spring-boot/spring-boot-autoconfiguration.md)的 `AutoConfigurationImportSelector` 就是它）、和這裡的 Registrar。三種都是「造圖」的變形。

### 「改」的權力有多大

5b 階段拿到的是 `ConfigurableListableBeanFactory`，任何一張圖都可以改：換 scope、改 lazy、塞屬性、換 class。框架自己也這麼用——placeholder 能把 `${db.url}` 換成真值，靠的就是「圖上的屬性值只是字串，施工前都可以改」。

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action` 執行）。

▶ 可執行範例：[BlueprintLab.java](examples/BlueprintLab.java)——讀、造、改三種權力一次演完：

```
[5] 註冊階段(Registrar)     | 登記了 worker-alpha、worker-beta 兩張新設計圖
[5] 修改階段(BFPP)          | 讀 orderService 的設計圖：beanClassName=null、
                            factoryBean=blueprintLab.App、factoryMethod=orderService、scope=""
[5] 修改階段(BFPP)          | 把 heavyReport 的設計圖改成 lazy——它將錯過第 11 步的量產
[11] 量產                  | OrderService 建構子
[11] 量產                  | Worker(alpha) 建構子——沒有對應的原始碼宣告，圖是算出來的
[11] 量產                  | Worker(beta) 建構子——沒有對應的原始碼宣告，圖是算出來的
--- refresh() 返回：容器就緒（注意：HeavyReport 建構子還沒出現）---
[getBean]                 | HeavyReport 建構子——lazy 化之後，用時才蓋
```

三個實測重點：

- **讀**：`orderService` 是 `@Bean` 來的，圖上 `beanClassName=null`——施工指令是「叫 `blueprintLab.App` 這顆 bean 的 `orderService()` 方法」。`scope=""` 印證空字串＝預設 singleton
- **造**：Registrar 在 5a 登記的兩顆 `Worker`，第 11 步照圖出生。原始碼裡**不存在** `worker-alpha` 的宣告——這就是 `@MapperScan` 靈異現象的最小重現
- **改**：`heavyReport` 的圖被 5b 改成 lazy 後，「容器就緒」時建構子**還沒跑**，`getBean()` 當下才蓋——證明第 11 步的量產名單，就是照圖上的 `lazyInit` 欄位點名的

## 技術優缺點

### 設計圖階段開放讀／造／改，買到什麼

- **元編程能力**：一個註解生一族 bean（`@MapperScan`、`@EnableJpaRepositories`、Boot 自動配置），整個 Spring 生態的「Enable 家族」都站在這上面
- **配置與施工分離**：`@Conditional`、placeholder、profile 全在圖上解決，第 11 步開工後不再有變數——啟動完成的容器是穩定的
- **可觀察**：圖是資料，可以 dump（案例一那樣）——排查「這顆 bean 到底哪來的、為什麼是這個 scope」有第一手證據，不用猜

### 代價與地雷

- **IDE 追不到**：算出來的 bean 沒有對應的原始碼宣告，「find usages」斷鏈——動態註冊用得越兇，啟動後的容器離原始碼越遠
- **BFPP 活在 bean 世界之前**：它自己在第 5 步就實例化（[上一篇的「早產」](container-startup-refresh.md)），此刻 `@Value` 沒人解析、一般 bean 不能依賴——在 BFPP 裡注入業務 bean 是經典錯誤
- **改圖是黑魔法**：改別人的設計圖（尤其第三方 starter 的）等於推翻它的假設，出事時 stack trace 完全指不到你——能用官方配置開關就不要動圖

## 小結

- 註解不會自己跑：`@Configuration`／`@ComponentScan`／`@Import` 是**被 `ConfigurationClassPostProcessor` 在第 5 步讀進來翻譯成 BeanDefinition** 的；`@Conditional` 的判定就在翻譯當下
- 設計圖兩種出身：`@Component` 記類別名、`@Bean` 記 **factory 方法**（`beanClassName=null`，實測為證）——這是 `@Bean` 方法能寫邏輯的原因
- 第 5 步先「造」後「改」：`BeanDefinitionRegistryPostProcessor`（5a，登記新圖）→ `BeanFactoryPostProcessor`（5b，讀圖改圖）
- 動態 bean 的正規姿勢是 `ImportBeanDefinitionRegistrar`——`@MapperScan` 靈異現象的謎底
- lazy、scope、屬性值都只是圖上的欄位，**第 11 步照圖點名施工**（lazy 化的 bean 實測缺席量產）

圖收齊了、也定稿了，第 11 步開始照圖施工——但施工時圖上寫的依賴（`@Autowired`）是誰去填的？`@PostConstruct` 又是誰呼叫的？答案都是第 6 步到職的那批工人：見 [BeanPostProcessor：@Autowired 與 @PostConstruct 其實是誰做的](bean-post-processor.md)。

## 常見面試題

1. `@Bean` 和 `@Component` 產生的 bean 有什麼本質差別？（提示：設計圖的兩種出身——factory 方法 vs 類別名；一個能寫邏輯一個不能）
2. 如何用程式動態註冊 bean？`@MapperScan` 是怎麼運作的？（提示：`ImportBeanDefinitionRegistrar` 在 5a 造圖）
3. `@Conditional` 在什麼時候被評估？（提示：第 5 步翻譯設計圖時，不是 bean 建立時）

## 延伸閱讀

- [Spring Framework 官方文件：Basic Concepts: @Bean and @Configuration](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html) — 配置類別的官方語意
- [Spring Framework 官方文件：Customizing Configuration Metadata with a BeanFactoryPostProcessor](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html#beans-factory-extension-factory-postprocessors) — 改圖的官方管道
- [ConfigurationClassPostProcessor 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java) — 第 5 步最大玩家的第一手依據
