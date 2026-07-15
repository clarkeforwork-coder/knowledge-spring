# @Cacheable：快取抽象與它的 key 地雷

## 前言

`@Cacheable` 的事故有兩種對稱的長相：一種是「**同樣的參數，第二次還是打了資料庫**」——快取形同虛設；另一種是「**資料早就改了，使用者還看到舊的**」——快取陰魂不散。兩種事故的根源都不在「快取本身」，在兩個沒人細看的問題上：**key 是怎麼生的**、**東西什麼時候離開快取**。

[上一篇](async-under-the-hood.md)講過這章的公式：同一套 proxy，換一個攔截邏輯就是另一項基礎設施。`@Async` 把方法呼叫丟進執行緒池；`@Cacheable` 把它變成「先查快取，miss 才執行、執行完回填」。機制的部分十分鐘講完，剩下的篇幅全部給地雷——四顆，每顆都有實測。

## 技術背景

### 先破除誤解：@Cacheable 不等於 Redis

`@Cacheable` 屬於 Spring 的**快取抽象**（Cache Abstraction）：註解只定義「何時查、何時放、何時清」，**放在哪裡**是 `CacheManager`／`Cache` 這對 SPI 背後的 provider 決定的——本地 `ConcurrentMap`、Caffeine、Redis 都只是可抽換的實作。所以：

- plain Spring 要自己宣告一顆 `CacheManager` bean（本篇範例用 `ConcurrentMapCacheManager`）
- Boot 依 classpath 自動偵測 provider（有 Redis 用 Redis、有 Caffeine 用 Caffeine……都沒有就退回預設）
- **預設的 `ConcurrentMapCacheManager`：本地、無 TTL、無上限**——三個特性各是一顆雷：不跨機器（每台各一份）、永不過期（案例四）、無限長大（OOM 候選人）

機制本身一句話：`@EnableCaching` 僱來的工人在 after-init 站掉包 proxy，攔截時先 `cache.get(key)`——hit 直接回、miss 執行本體再 `put`。是 proxy 就有 [proxy 的失效全家福](async-under-the-hood.md)：self-invocation、非 public、忘了 `@EnableCaching`，那張表原封不動適用，不再重寫。

### 主戲：key 是怎麼生的

沒寫 `key` 屬性時，`SimpleKeyGenerator` 的規則只有三行：

```
0 個參數  → SimpleKey.EMPTY
1 個參數  → 就是那個參數本身          ← 地雷一、地雷二都住在這行
多個參數  → new SimpleKey(全部參數)
```

**推論一：參數的 `equals`／`hashCode` 直接決定命中率。** key 要在 Map 裡找得到自己，靠的是值相等。自訂查詢物件沒實作 `equals`（[knowledge-java 講過的契約](../../knowledge-java/04-collections/equals-hashcode-contract.md)）→ 每個 `new` 出來的實例都是「不同的 key」→ **永遠 miss，而且無聲**（實測見案例一）。用 record 當查詢參數，這題自動消失。

**推論二：key 裡沒有「方法名」。** 兩個方法共用同一個 cache name、參數又相同時，它們**共享同一格快取**——A 方法放進去的東西會被 B 方法拿出來，型別對不上就是 `ClassCastException`（實測見案例二）。修法二選一：

```java
// ✅ 修法一：一個方法一個 cache name（推薦，語意清楚）
@Cacheable("product-names")  String  name(Long id)  { ... }
@Cacheable("product-stocks") Integer stock(Long id) { ... }

// ✅ 修法二：顯式 key 加上區分維度
@Cacheable(value = "catalog", key = "'name:'  + #id") String  name(Long id)  { ... }
@Cacheable(value = "catalog", key = "'stock:' + #id") Integer stock(Long id) { ... }
```

`key` 屬性是 SpEL，常用素材：`#參數名`、`#user.id`、`#root.methodName`。篩選有兩個攣生屬性，差在時機：`condition` **事前**判斷（不成立就不查也不放）、`unless` **事後**判斷（可以用 `#result`）。

### 地雷三：null 也會被快取

`@Cacheable` 方法回傳 null，**null 會被放進快取**（`ConcurrentMapCache` 預設 `allowNullValues=true`，用哨兵物件存）。這是雙面刃：好處是擋住「查無資料就次次穿透到 DB」；壞處是「商品上架了，查詢卻還記得『不存在』」。不想快取 null：

```java
@Cacheable(value = "discounts", unless = "#result == null")
```

### 地雷四：抽象層沒有 TTL 這個概念

翻遍 `@Cacheable` 的屬性，**沒有 `ttl`**——過期策略是 provider 的能力，不是抽象的一部分：Caffeine 用 spec 字串、Redis 用 `entryTtl` 配置，而預設的 `ConcurrentMap` **永不過期**（實測見案例四）。抽象層給你的替代品是**寫路徑的三個註解**：

| 註解 | 語意 |
|---|---|
| `@CachePut` | 方法照常執行，結果**覆寫**進快取（更新場景） |
| `@CacheEvict` | 逐出——`allEntries = true` 清整個 cache；`beforeInvocation = true` 改成方法執行前就清 |
| `@Caching` | 上面幾個的組合器（一個方法要動多個 cache 時） |

也就是說：**一致性的責任在你**——資料更新的地方要自己編排 evict／put。至於「跨程序、跨機器的快取失效」是分散式問題，超出本 repo 範圍（Redis 等留給其他 `knowledge-*`）。

### 彩蛋（親踩）：不要透過 proxy 讀欄位

寫這篇範例時踩的真坑：執行計數器原本是 `PriceService` 的欄位，透過容器拿到的 bean 去讀，**永遠是 0**。原因：容器給你的是 CGLIB proxy，**方法**會轉發到本尊，**欄位**不會——你讀到的是 proxy 自己那份從未初始化的欄位。debug 時對著 proxy 看欄位（IDE debugger 也一樣）看到的可能全是假的——要看狀態，一律走 getter。

## 實際案例

驗證環境：spring-context 6.2.8、JDK 17（JBang 單檔，Docker `jbangdev/jbang-action`）。

▶ 可執行範例：[CacheTraps.java](examples/CacheTraps.java)

### 案例一：equals 決定命中率

```
BadQuery（沒有 equals/hashCode）連查兩次 → 方法本體執行了 2 次
GoodQuery（record，自帶 equals）連查兩次 → 方法本體執行了 1 次
```

同一個 sku 查兩次：沒有 `equals` 的查詢物件次次 miss（快取形同虛設、而且**沒有任何警告**）；record 第二次直接命中。「用 DTO 當 `@Cacheable` 參數」的紀律：**必須有值語意**——record 是最省事的答案。

### 案例二：共用 cache name 的跨方法污染

```
name(1L) 先進快取：蘋果
stock(1L) 炸了：class java.lang.String cannot be cast to class java.lang.Integer
```

`name(1L)` 和 `stock(1L)` 的 key 都是 `1L`、又住同一個 `"catalog"`——`name` 放進去的 `"蘋果"`，被 `stock` 當成 `Integer` 拿出來，`ClassCastException` 當場引爆。這還是**幸運的版本**：如果兩個方法回傳型別相同，污染就不炸了——改成**安靜地回傳錯的資料**。

### 案例三：null 也被記住了

```
discount(9L) 第一次：null
discount(9L) 第二次：null
方法本體執行了 1 次——查無資料也被記住了
```

第二次呼叫沒有進方法本體——快取裡存了「這個 id 沒有折扣」這件事本身。防穿透是它、上架後查不到新資料也是它，用 `unless = "#result == null"` 選邊。

### 案例四：永不過期的髒讀，與 evict 修復

```
quote("apple")：100.0
改價後再 quote("apple")：100.0  ← 還是舊價，永不過期
evict 後再 quote("apple")：88.0
```

價格改成 88 之後，快取還在供應 100——`ConcurrentMap` 沒有 TTL，這筆舊資料**會活到程序重啟**。`@CacheEvict` 之後才回到真實。生產上的正解是兩層：更新路徑一律帶 evict／put，再選一個有 TTL 的 provider 當保險網。

## 技術優缺點

### 快取抽象買到什麼

- **快取邏輯與業務分離**：查、放、清全是宣告式——業務方法裡看不到一行快取碼，開關快取不動業務
- **provider 可抽換**：本地開發用 ConcurrentMap、正式環境換 Caffeine/Redis，業務程式碼零修改——抽象層的經典價值
- **細粒度控制就位**：`condition`／`unless`／SpEL key／`sync = true`（快取擊穿時只放一條 thread 進本體）該有的都有

### 代價與地雷

- **預設組合是三重雷**：本地＋無 TTL＋無上限——`@Cacheable` 和 `@Async` 一樣，屬於「不配置比配置危險」的功能
- **key 的兩顆雷都無聲**：equals 缺失＝永遠 miss（效能默默流失）；共用 name＝污染（運氣好才會炸給你看）
- **null 快取是雙面刃**：不知道這個預設的人，兩邊的事故都可能碰到
- **一致性責任還在你**：抽象只管單程序內的查放清——更新路徑漏一個 evict，髒讀就上線了

## 小結

- `@Cacheable` 是**快取抽象**：註解管時機、provider 管存放——預設 `ConcurrentMapCacheManager` 本地／無 TTL／無上限，三個特性三顆雷
- 預設 key 規則：**單參數＝參數本身**——參數沒有 `equals` 就永遠 miss（實測 2 次 vs 1 次）；record 是最省事的解
- **key 裡沒有方法名**：共用 cache name 的方法共享 key 空間——污染實測炸出 `ClassCastException`；一方法一 name 或顯式 key
- **null 會被快取**（實測第二次不進本體）——`unless = "#result == null"` 選邊
- 抽象層**沒有 TTL**：過期是 provider 的事，一致性靠你在更新路徑編排 `@CacheEvict`／`@CachePut`（髒讀實測 100→88）
- 彩蛋教訓：**別透過 proxy 讀欄位**——方法轉發、欄位不轉發

案例四的 evict 還藏著一個更深的問題：如果 evict 所在的方法包在交易裡，**交易還沒 commit 就先清了快取**，別的請求可能馬上用舊資料回填——清了等於沒清。「等交易成功再做」正是下一篇的主題：見規劃中的〈@TransactionalEventListener：交易邊界上的事件〉。

## 常見面試題

1. `@Cacheable` 沒指定 key 時，key 是怎麼生成的？有什麼坑？（提示：單參數＝參數本身；equals／hashCode；key 裡沒有方法名）
2. 兩個方法共用同一個 `cacheNames` 會發生什麼事？（提示：key 空間共享——污染，型別不同炸 CCE、型別相同回錯資料）
3. `@Cacheable` 會快取 null 嗎？想改變這個行為怎麼做？（提示：預設會（防穿透）；`unless = "#result == null"`）

## 延伸閱讀

- [Spring Framework 官方文件：Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html) — 抽象全貌與註解語意
- [SimpleKeyGenerator 原始碼（6.2.x）](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-context/src/main/java/org/springframework/cache/interceptor/SimpleKeyGenerator.java) — 三行 key 規則的第一手依據
- [Spring Boot 官方文件：Caching](https://docs.spring.io/spring-boot/reference/io/caching.html) — provider 偵測順序與各 provider 的 TTL 配置
