# PonziShield 基座模块 —— 答辩准备指南

> **作者**: 庞中浩（Part 1 基座）
> **用途**: 应对老师提问，请逐条理解消化。

---

## 一、基座到底是什么？

### 一句话定义

**基座是 PonziShield 项目最底层的数据生产者。它用 Java 在本地模拟一条以太坊链的运行——部署一个庞氏合约、让虚拟账户逐块执行 stake/withdraw/transfer 操作，把每一笔资金流动打包成结构化的事件（JSON），通过 HTTP 发给 Python 后端。后端用一个 NetworkX 图存储这些事件，后续四个模块（ML检测、中介检测、生命周期评分、风险融合）全都在这个图上做分析。**

### 用白话讲就是

想象一下，你要检测一个庞氏骗局。但你不能真的去以太坊主网上部署一个骗局合约来做实验。所以我们在本地"假装"有一条区块链，上面跑着一个庞氏合约，我们让一群虚拟用户去存钱、提钱、分红。每发生一笔转账，我们就记下来发给后端的分析模块。

基座就是这条"假区块链"和这个"假合约"的组合体。

### "基座"和"Demo 按钮"、"Tx Stream"、"Graph"、"Lifecycle"、"区块高度"是什么关系？

| UI 要素 | 基座提供了什么 |
|---------|-------------|
| **Demo 按钮** | 基座的 `PonziDemoMain.java` 是 Demo 按钮背后的 Java 入口。按下按钮 → 触发一次模拟运行 → 产生一批事件。 |
| **Tx Stream** | 基座的 `FundFlowEmitter` 把每笔转账异步 POST 到 Python API，Python 存进 `/api/v1/history`，前端从这个接口拉数据渲染表格。现在每行多了 `event_type`（STAKE/WITHDRAW/REFERRAL_REWARD/DIVIDEND），用户能看清每笔钱的业务含义。 |
| **Graph 边** | 基座产生的每笔转账在 Python 侧被存进 NetworkX 有向图的边。边属性包括 from/to/value/block_number/event_type。前端从 `/api/v1/graph` 拉图数据渲染。现在边有了 event_type，可以按类型着色。 |
| **Lifecycle 曲线** | 基座按区块号顺序产出事件。Python 后端按区块高度聚合交易量、投资人数量等指标，Part 4 用这些数据计算 FUNDRAISING/PAYOUT/STAGNATION/COLLAPSE 四个阶段。我引入的动态利率机制让收益率在达到阈值后会收窄，Lifecycle 曲线因此出现有意义的拐点。 |
| **区块高度** | 基座的 `WorldState` 用 `BlockContext` 维护当前区块号和时间戳。每个 `FundFlowEvent` 都携带 `block_number`，这是整个系统的时序坐标轴。 |


---

## 二、代码是怎么实现的？——逐层讲解

### 整体数据流（面试时如果让你画图，就按这个顺序画）

```
PonziDemoMain.java（入口，模拟用户操作）
    ↓ 调用
PonziContract.java（庞氏合约核心逻辑：stake/withdraw/分红/推荐奖励）
    ↓ 调用 worldState.transfer(from, to, amount, eventType)
WorldState.java（账户状态管理 + 转账执行 + 事件捕获）
    ↓ 产生 FundFlowEvent（7个字段，含 event_type）
FundFlowEmitter.java（异步HTTP发射器）
    ↓ POST /api/v1/transfer（JSON）
main.py（Python FastAPI 后端）
    ↓ 存入 TransferGraph
transfer_graph.py（NetworkX MultiDiGraph 图存储）
    ↓ 提供 /api/v1/graph 和 /api/v1/history
前端 Graph/Tx Stream/Lifecycle 面板
```

### 2.1 入口：PonziDemoMain.java

**文件路径**：`eth-whitepaper-java-main/src/main/java/dev/naoki/ethwhite/ponzi/PonziDemoMain.java`

**作用**：这是按下 Demo 按钮后 Java 侧的执行入口。它部署一个 PonziContract，创建若干虚拟钱包，让它们按顺序 stake 和 withdraw。

**我新增的内容**：

```java
// 默认场景（不变）
if (demoType.equals("default")) {
    runDefaultDemo(blockContext);  // 10人线性推荐链 + 3人依次提取
}

// 我新增的 Network 场景：枢纽-辐射推荐网络
if (demoType.equals("network")) {
    runNetworkDemo(blockContext);  // 1个部署者 -> 3个hub -> 6个leaf
}

// 我新增的 Whale 场景：鲸鱼早退
if (demoType.equals("whale")) {
    runWhaleEarlyExitDemo(blockContext);  // 大户存5 ETH，锁仓期后立即提取
}
```

**Network 场景具体是什么？**：部署者（Wallet 0）依次邀请 Wallet 1、2、3 作为"枢纽"（hub），每个 hub 再邀请两个"叶子"投资人。最终形成 10 人（达到 phaseThreshold），触发阶段切换。两个 hub 在锁定期满后提取。产生的转账图有明确的枢纽-辐射结构。

**Whale 场景具体是什么？**：Wallet 0 存入 5000 WEI（约为最低门槛 100 WEI 的 50 倍）——这就是"鲸鱼"。三个小投资人看到有大户参与，跟风存入。两区块锁定期一过，鲸鱼立即提取全部余额——一笔巨大的 WITHDRAW，几乎抽干合约。之后又有三个不知情的投资人加入。Tx Stream 上会看到两波入金中间夹着一笔巨大的出金。

### 2.2 核心：PonziContract.java

**文件路径**：`eth-whitepaper-java-main/src/main/java/dev/naoki/ethwhite/sample/PonziContract.java`

**作用**：这是整个项目的"心脏"。它模拟了一个真实以太坊庞氏合约的全部业务逻辑。

**原有逻辑（我没改的部分）**：
- `stake`：投资人存入 ETH → 检查是否新用户 → 支付推荐奖励 → 给老投资人分红 → 剩余部分记入投资人可提取余额
- `withdraw`：检查余额 > 0 → 检查锁仓期已过 → 从合约余额中支付
- 两级推荐：一级推荐人得 10%，二级推荐人得 5%
- 投资人池分红：40% 均分给所有在先投资人

**我新增的内容（三个创新点都聚在这个文件里）**：

**创新1——动态利率（核心改动）**：
```java
// 部署时写入两组利率配置
putBig(context, "earlyReferralBps",  ...);  // 早期推荐率：10%
putBig(context, "matureReferralBps", ...);  // 成熟期推荐率：5%（打五折）
putBig(context, "earlyPoolBps",      ...);  // 早期分红率：40%
putBig(context, "maturePoolBps",     ...);  // 成熟期分红率：30%（打七五折）
putBig(context, "phaseThreshold",     ...);  // 阈值：5人
putString(context, "dynamicPhase", "EARLY");  // 初始阶段

// 每次新投资人加入后检查是否切换阶段
maybeTransitionPhase(context);
// 投资人数 >= 5 → 写入 "MATURE"

// 所有利率计算走动态方法，而不是读固定常量
BigInteger rate = currentReferralBps(context);
// EARLY时返回 earlyReferralBps → MATURE时返回 matureReferralBps
```

**创新2——事件分类（交易路径改动）**：
```java
// 提现路径 → 标注为 WITHDRAW
context.state().transfer(context.self(), sender, payout, EventType.WITHDRAW);

// 推荐奖励 → 标注为 REFERRAL_REWARD
transferIfPossible(context, referrer, primary, remaining, EventType.REFERRAL_REWARD);

// 分红派息 → 标注为 DIVIDEND
transferIfPossible(context, investor, share, remaining, EventType.DIVIDEND);
```

**创新3——三个新查询接口**（让下游模块能读取当前状态）：
```java
case "phase"           -> response(phase(context));
    // 返回 "EARLY" 或 "MATURE"，Part 4/5 可以据此判断
case "referralBpsNow"  -> response(currentReferralBps(context).toString());
    // 返回当前实际推荐率
case "poolBpsNow"      -> response(currentPoolBps(context).toString());
    // 返回当前实际分红率
```

### 2.3 事件模型：EventType.java + FundFlowEvent.java

**EventType.java**（我新增的文件）：
```java
public enum EventType {
    STAKE,           // 投资人存入本金
    WITHDRAW,        // 投资人提取余额
    REFERRAL_REWARD, // 支付上线推荐佣金
    DIVIDEND,        // 向老投资人分红
    TRANSFER         // 通用回退
}
```

**FundFlowEvent.java**（我修改的文件）：
- 原来是 6 个字段的 record：`(from, to, value, blockNumber, txHash, timestamp)`
- 改为 7 个字段的 record：`(from, to, value, blockNumber, txHash, timestamp, eventType)`

**向后兼容的关键设计（面试时如果问到，一定强调这一点）**：
```java
// 保留旧构造函数，默认 eventType = TRANSFER
public FundFlowEvent(from, to, value, blockNumber, txHash, timestamp) {
    this(from, to, value, blockNumber, txHash, timestamp, EventType.TRANSFER);
}

// JSON追加而非插入——旧消费者不认识event_type就直接跳过
public String toJson() {
    return "{"
        + "\"from\":\"" + from.toHex() + "\","
        + "\"to\":\"" + to.toHex() + "\","
        + "\"value\":\"" + value + "\","
        + "\"block_number\":" + blockNumber + ","
        + "\"tx_hash\":\"" + Hex.prefixed(txHash) + "\","
        + "\"timestamp\":" + timestamp + ","
        + "\"event_type\":\"" + eventType.name() + "\""
        + "}";
}
```
注意：`event_type` 是加在 JSON 末尾的，不是插在中间。旧版 Python 代码如果只解析前 6 个字段，后面的 `event_type` 会被直接忽略。

### 2.4 转账执行：WorldState.java

**文件路径**：`eth-whitepaper-java-main/src/main/java/dev/naoki/ethwhite/core/WorldState.java`

**作用**：管理所有账户的状态（余额），执行转账，并在转账发生时捕获事件。

**我的修改**：新增一个带 `EventType` 的重载方法：
```java
// 原有方法（保留，默认 TRANSFER，不改任何已有调用者）
public void transfer(Address from, Address to, BigInteger amount) {
    transfer(from, to, amount, EventType.TRANSFER);
}

// 新增重载（带类型，PonziContract 内部使用）
public void transfer(Address from, Address to, BigInteger amount, EventType eventType) {
    if (amount.signum() < 0) { throw ... }
    if (amount.signum() == 0) { return; }
    getOrCreate(from).debit(amount);   // 扣钱
    getOrCreate(to).credit(amount);    // 加钱
    if (transferCapture != null) {
        transferCapture.record(from, to, amount, eventType);  // 记录事件（带类型）
    }
}
```

### 2.5 事件发射：FundFlowEmitter.java

这个文件我**没有修改**。它原本就会读取 `WorldState.capturedTransfers()`，对每个 `FundFlowEvent` 调 `toJson()`，然后 POST 到 Python API。

因为 `toJson()` 输出的 JSON 只是在末尾多了一个 `event_type` 键，Emitter 无需任何改动就能正常发送。

### 2.6 Python 后端：transfer_graph.py + main.py

**transfer_graph.py**（我修改）：
```python
@dataclass
class TransferEvent:
    from_address: str
    to_address: str
    value: int
    block_number: int
    tx_hash: str
    timestamp: int
    event_type: str = "TRANSFER"  # ← 新增，默认值保证向后兼容

def add_event(self, event):
    self.graph.add_edge(from, to,
        value=value, block_number=block_number, ...,
        event_type=event.event_type  # ← 存入 NetworkX 边属性
    )
```

**main.py**（我修改）：
```python
class TransferRequest(BaseModel):
    # ... 原有 6 个字段 ...
    event_type: str = "TRANSFER"  # ← 新增，Pydantic 可选字段

@app.post("/api/v1/transfer")
def ingest_transfer(payload):
    event = TransferEvent(..., event_type=payload.event_type)  # ← 透传
```

**为什么不会影响其他四个部分？** 我在 `TransferEvent` 和 `TransferRequest` 中都给了默认值 `"TRANSFER"`。如果 Part 2/3/4/5 的代码不传这个字段 -> 自动填 `"TRANSFER"` -> 旧有逻辑完全不受影响。如果它们的代码读了 Graph 的边属性发现多了一个 key -> 不认识就忽略，不影响分析。


---

## 三、我做了什么创新？——三个创新的逐一讲解

### 创新1：动态利率机制

**做了什么**：让庞氏合约的推荐奖励率和分红率不再是固定值，而是根据投资人数量自动切换。早期（<=5人）高利率吸引韭菜，成熟期（>5人）降息收割。

**为什么这是创新**：
- 原始代码的利率是硬编码常量，永远不会变
- 真实的庞氏骗局永远不会保持固定利率——运营者会根据资金池大小调整
- 如果仿真器只有固定利率，Lifecycle 曲线就不会出现"收益率收窄"这个最关键的信号
- Part 4 的生命周期评分器就是设计来捕捉"收益率随时间下降"这个模式的——没有动态利率，它无信号可抓

**灵感来源**：
- Chen et al. (2018) 对真实以太坊庞氏合约的实证研究发现，**高收益承诺是庞氏骗局最显著的区分特征**，且运营者会根据资金池大小动态调整
- Jin et al. (2022) 提出的双通道预警框架把"收益率异常"作为核心信号通道之一

**代码位置**：`PonziContract.java` 中 `onDeploy()` 写入两组利率、`maybeTransitionPhase()` 自动切换、`currentReferralBps()`/`currentPoolBps()` 动态读取。

### 创新2：分类资金流事件

**做了什么**：给每笔转账打上业务类型标签（STAKE/WITHDRAW/REFERRAL_REWARD/DIVIDEND），替代原来所有转账都一样的"匿名"事件。

**为什么这是创新**：
- 原始代码里，推荐奖励、分红派息、本金提取三种完全不同的经济行为，产生的事件 JSON 长得一模一样——只能通过金额和地址"猜"这笔钱是什么
- Part 2 的 ML 分类器和 Part 4 的生命周期评分器都需要知道每笔转账的语义——原来只能"反向工程"
- 现在 Part 3 可以用 `event_type == REFERRAL_REWARD` 精确识别累积节点（accumulator），用 `event_type == DIVIDEND` 识别分发节点（distributor），不再需要启发式猜测
- Part 4 可以直接查"同一区块内同时有 STAKE 和 DIVIDEND"——这就是"即时派息"（instant payout）特征

**灵感来源**：
- Wen et al. (2024) 的 PonziLens+ 通过分析合约字节码中的操作码序列来识别庞氏行为，核心思路是**区分不同业务逻辑对应的代码路径**
- 我借鉴了这个思路但降低了层级——我不是分析字节码，而是在合约逻辑的调用点直接标注事件类型。效果相同但成本低得多，而且完全准确（不需要推断）

**代码位置**：`EventType.java` 定义枚举、`FundFlowEvent.java` 增加字段、`PonziContract.java` 在每个 transfer 调用处传递类型、`transfer_graph.py` 存储到边属性。

### 创新3：增强 Demo 场景

**做了什么**：新增两个 Demo 场景——枢纽-辐射推荐网络（network）和鲸鱼早退（whale）。

**为什么这是创新**：
- 原始 Demo 只有 10 人线性链 + 3 次提取——Graph 面板看起来就是一条直线，Lifecycle 曲线没有任何拐点
- 真实的庞氏骗局转账图有明显的枢纽-辐射结构（少数核心推荐人连接大量被推荐人）
- 真实庞氏在崩盘前往往有"大户清仓"（whale early exit）的现象

**Network 场景的目的**：让 Part 3 中介检测器有事可做——出现高介数中心性的 hub 节点，可被标注为 ACCUMULATOR / RELAY

**Whale 场景的目的**：让 Part 4 生命周期能触发 STAGNATION / COLLAPSE 阶段——出现募资后大额提取的信号，Part 5 风险融合能给出 HIGH 判定

**灵感来源**：
- Chen et al. (2018) 发现真实庞氏的转账图具有明显的枢纽-辐射结构
- PonziLens+ 对真实崩盘前行为的分析：大户在锁仓期后立即大额退出，往往是庞氏崩盘的前兆

**代码位置**：`PonziDemoMain.java` 中 `runNetworkDemo()` 和 `runWhaleEarlyExitDemo()` 方法。


---

## 四、参考文献解释（老师可能会问"你参考了哪些文献？"）

| 文献 | 核心贡献 | 我借鉴了什么 |
|------|---------|------------|
| Chen et al. (2018), WWW | 第一篇系统研究以太坊庞氏合约的论文，提出基于账户特征和交易特征的 ML 检测方法 | 庞氏合约的高收益特征和枢纽-辐射转账结构，启发我的动态利率和 network demo |
| Jin et al. (2022), arXiv | 双通道（链上交易 + 代码特征）预警框架 | "收益率异常"作为核心信号通道，启发我让利率可以动态变化 |
| Wen et al. (2024), arXiv | PonziLens+：可视化合约字节码操作序列来识别庞氏 | 区分不同业务逻辑路径的思路，启发我的 event_type 分类体系 |
| Buterin (2013), Ethereum Whitepaper | 以太坊平台和智能合约的基础设计 | 理解合约状态模型、转账语义、事件机制的理论基础 |


---

## 五、可能的追问及答案

### Q1: "为什么用 Java 而不是 Solidity？"

> 我们的项目不是在以太坊主网上运行，而是在本地仿真。Java 框架 eth-whitepaper 提供了完整的 EVM 仿真环境（WorldState、BlockContext、Address 等），可以直接运行合约逻辑并捕获每步状态变化。如果用 Solidity 写然后部署到测试网，就无法控制区块时间、无法随意构造极端场景（如鲸鱼早退）、也无法在转账发生时注入自定义字段（如 event_type）。

### Q2: "你的修改会不会影响 Part 2/3/4/5？"

> 完全不会。这是核心设计原则：
> 1. `event_type` 字段在所有接口中都有默认值 `"TRANSFER"`——旧代码不传就不受影响
> 2. Python 后端 `TransferEvent` 和 `TransferRequest` 的新字段都是 optional，有默认值
> 3. JSON 序列化是追加而非插入——旧消费者不认识就跳过
> 4. 动态利率改的是合约内部的数值，传输 schema 不变
> 5. Demo 默认行为完全不变，新场景需要显式选择（-Ddemo.type=xxx）

### Q3: "动态利率的阈值为什么是 5 人？"

> 5 是一个可配置参数（phaseThreshold），部署时可以自定义。选择默认 5 是因为：在 Network Demo 的场景里，前 5 个投资人加入时处于 EARLY 期高利率，第 6 个开始进入 MATURE 期降低利率。这使得 Lifecycle 曲线出现一个清晰的"拐点"——基金募集到一定程度后收益率收窄，这正是 Part 4 需要捕捉的信号。

### Q4: "Whale Demo 的设计意图是什么？"

> 真实世界的庞氏骗局在崩盘前往往有一个"大户清仓"的阶段：早期投入最多的投资者（被高收益吸引进来的"鲸鱼"）在锁仓期一到就立即提现走人。这个行为有双重效果：一是抽干合约资金池，让后面的人取不到钱；二是产生一个巨大的 WITHDRAW 事件，在 Tx Stream 和 Lifecycle 曲线上非常显眼。我的 Whale Demo 精确复现这个模式——让 Part 4 生命周期能够触发 STAGNATION 或 COLLAPSE 阶段，也让 Risk Gauge 有足够证据给出 HIGH 判定。

### Q5: "你的 event_type 和 Part 3 的中介检测有什么关系？"

> Part 3 需要区分不同类型的中介节点。有了 event_type 之后：
> - **ACCUMULATOR（累积节点）**：收到大量 REFERRAL_REWARD 的钱包——因为它的下线在持续存钱，它持续收到推荐佣金
> - **DISTRIBUTOR（分发节点）**：发出大量 DIVIDEND 的钱包——合约持续向它支付分红
> - 原来 Part 3 只能靠金额大小启发式区分，现在可以直接按事件类型精确区分

### Q6: "如果老师让你画架构图……"

> 从上到下画六层：
> 1. **Demo 按钮** → PonziDemoMain.java
> 2. **合约逻辑** → PonziContract.java（stake/withdraw/分红/推荐）
> 3. **转账层** → WorldState.java（扣款入账 + 事件捕获）
> 4. **事件发射** → FundFlowEmitter.java（异步HTTP POST）
> 5. **Python 后端** → main.py → transfer_graph.py（NetworkX 图存储）
> 6. **消费方** → Part 2 ML / Part 3 中介 / Part 4 生命周期 / Part 5 风险融合
>
> 然后标注你的三个创新点分别在哪个位置：
> - 创新2（event_type）：贯穿第 2-6 层
> - 创新1（动态利率）：第 2 层（合约内部）
> - 创新3（Demo场景）：第 1 层（入口）


---

## 六、一分钟电梯演讲（答辩开场模板）

> "我负责的是 PonziShield 项目的基座模块。基座是整个系统的底层数据引擎——它用 Java 在本地模拟以太坊链上运行一个庞氏合约，把每一笔 stake、withdraw、推荐奖励、分红都打包成结构化事件，通过 HTTP 发送给 Python 后端存入图数据库。后续四个模块——机器学习检测、中介节点识别、生命周期评分、风险融合——全部在基座产生的数据上做分析。
>
> 我在原有代码基础上做了三个创新：
>
> 第一，**分类资金流事件**。给每笔转账打上业务类型标签（STAKE/WITHDRAW/REFERRAL_REWARD/DIVIDEND），让下游不用再猜这笔钱是什么性质。灵感来自 PonziLens+ 论文，他们通过分析操作码序列区分业务逻辑，我把它简化到合约调用点直接标注。
>
> 第二，**动态利率机制**。引入两阶段利率模型（EARLY/MATURE），在投资人数达到阈值后自动降息，模拟真实庞氏骗局"早期高收益诱惑、后期降息收割"的经典模式。灵感来自 Chen et al. (2018) 对真实庞氏的实证研究和 Jin et al. (2022) 的收益率异常信号通道。
>
> 第三，**增强 Demo 场景**。新增辐射状推荐网络和大户早退两个场景，让检测链路能对更复杂的结构做出反应。
>
> 三个改动全部向后兼容，新增字段都有默认值，不影响其他四位同学的代码。"
