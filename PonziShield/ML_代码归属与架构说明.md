# PonziShield · ML 模块完整技术说明

> 作者：anchenrui（ML 检测负责人）  
> 更新：2026-06-30  
> 面向读者：完全不了解本项目的人，以及课程答辩的评审者

---

## 目录

1. [我们在解决什么问题](#1-我们在解决什么问题)
2. [ML 的核心原理：从"看交易记录"到"判断是否庞氏"](#2-ml-的核心原理)
3. [数据：从哪里来，长什么样](#3-数据从哪里来长什么样)
4. [特征工程：把交易记录变成数字](#4-特征工程把交易记录变成数字)
5. [模型训练：逻辑回归从零实现](#5-模型训练逻辑回归从零实现)
6. [训练结果与模型验证](#6-训练结果与模型验证)
7. [推理：一个新地址进来如何判断](#7-推理一个新地址进来如何判断)
8. [三通道融合：ML 如何服务于整体系统](#8-三通道融合ml-如何服务于整体系统)
9. [代码归属一览](#9-代码归属一览)
10. [复现步骤](#10-复现步骤)

---

## 1. 我们在解决什么问题

### 什么是庞氏骗局（Ponzi Scheme）

庞氏骗局的核心逻辑只有一句话：**用后来者的钱，还给先来者。** 没有真实盈利，只是资金在参与者之间流转，直到无法维持崩盘。

以太坊上的庞氏合约是这一骗局的智能合约版本。用户把 ETH 转给合约，合约承诺高额回报，但实际上只是把新用户的 ETH 转给老用户，直到资金链断裂。

**典型例子：Rubixi**（`0xe82719202e5965cf5d9b6673b7503a3b92de20be`）
- 317 笔真实交易
- 承诺 "multiply your Ether" 
- 2016 年崩盘，造成大量用户损失

### 我们要做什么

**输入**：一个以太坊合约地址 + 它的历史交易记录  
**输出**：这个合约是庞氏骗局的概率（0~1 的一个数字）

---

## 2. ML 的核心原理

### 2.1 为什么用"图"来分析

交易记录天然是一张图：每个地址是节点，每笔转账是有向边，金额是边的权重。

```
用户A ──100 ETH──▶ 庞氏合约 ──50 ETH──▶ 用户B
用户C ──200 ETH──▶ 庞氏合约 ──50 ETH──▶ 用户A
                                ──100 ETH──▶ 用户D
```

庞氏合约在这张图中有非常独特的结构：
- 它是中心节点（大量资金进出都经过它）
- 资金进来之后很快就流出给其他人（同区块 payout）
- 会有资金"回流"现象（新钱还旧债）

普通合约（比如 ERC20 代币）的图结构则非常不同：资金流向更分散，不会有明显的"收钱→立即付钱"模式。

### 2.2 为什么用逻辑回归

我们需要一个**二分类模型**（庞氏 or 非庞氏），输入是从交易图中提取的 12 个数值特征。

逻辑回归的公式：

```
p = sigmoid( w₀ + w₁x₁ + w₂x₂ + ... + w₁₂x₁₂ )

sigmoid(z) = 1 / (1 + e^(-z))
```

- `x₁...x₁₂`：12 个图特征的数值
- `w₁...w₁₂`：每个特征的权重（通过训练学到）
- `w₀`：截距（bias）
- `p`：输出概率，越接近 1 越像庞氏

选择逻辑回归的原因：
1. **可解释性强**：每个特征的权重直接说明"这个特征有多重要"
2. **样本量适中**（308个合约），复杂模型容易过拟合
3. **从零实现简单**，不依赖 sklearn 等第三方库，方便 Report 解释

---

## 3. 数据：从哪里来，长什么样

### 3.1 标签数据（labels.csv）

来源于两个学术 GitHub 项目：

| 类别 | 数量 | 来源 | 说明 |
|------|------|------|------|
| 庞氏合约（label=1） | 182 个 | `blockchain-unica/ethereum-ponzi` | 意大利 Cagliari 大学维护，配套论文：*Dissecting Ponzi schemes on Ethereum* (2020) |
| 正常合约（label=0） | 200 个 | `xuyl0104/blockchain_ponzi_detection` | 从 Etherscan 随机抽取的合法合约 |

文件格式（`data/processed/labels.csv`）：
```
address,label,source,source_file
0xe82719202e5965cf5d9b6673b7503a3b92de20be,1,blockchain_unica,...
0xf41624c6465e57a0dca498ef0b62f07cbaab09ca,0,xuyl0104,...
```

### 3.2 交易数据（transactions.csv）

这是最核心也是最难获取的数据。每个合约地址需要拉取它的所有历史交易。

**数据来源**：Etherscan V2 API（以太坊官方区块链浏览器的接口）

Etherscan 提供两种交易：
- **普通交易（txlist）**：EOA 账户发起的转账，如用户存入 ETH
- **内部交易（txlistinternal）**：合约发起的转账，如合约自动分红

两种都需要，因为庞氏合约的"还款"行为往往是内部交易。

**我们的获取脚本**（`fetch_transactions.py`）对每个地址分别调用两个 API，合并去重后保存：

```python
# 对每个合约地址：
normal_url   = "https://api.etherscan.io/v2/api?chainid=1&module=account&action=txlist&address={addr}&apikey={key}"
internal_url = "https://api.etherscan.io/v2/api?chainid=1&module=account&action=txlistinternal&address={addr}&apikey={key}"
```

**最终规模**：
- 382 个地址 × 约 660 条/地址（平均）= **255,349 条真实以太坊交易记录**

文件格式（`data/raw/xblock/transactions.csv`）：
```
from,to,value,block_number,tx_hash,timestamp
0xdd68da49...,0xe82719202...,1000000000000000000,1150481,0xabc...,1458627840
```
- `value` 单位是 wei（1 ETH = 10^18 wei）
- `block_number` 是区块高度，可以理解为"时间戳的离散版"

---

## 4. 特征工程：把交易记录变成数字

这是整个 ML 系统最核心、最需要理解的部分。

**核心思路**：以目标合约为中心，取它的 2-hop 邻域（即直接交易过的地址，以及那些地址的交易对象），在这个子图上计算 12 个描述图结构的数值特征。

### 为什么是 2-hop？

1-hop 只看合约直接收/付款的地址，信息太少。  
3-hop 会把太多不相关的地址拉进来，噪音太大。  
2-hop 是学术界对此类任务的标准设置（EthXpose 论文中提出）。

### 12 个特征的完整解释

代码入口：`api/tools/graph_classifier.py` 中的 `extract_graph_features()` 函数

---

#### 特征 1：`tx_count_norm` — 归一化交易数量

```python
"tx_count_norm": min(1.0, len(events) / 24)
```

**含义**：2-hop 邻域内总交易数，除以 24 归一化到 [0,1]。  
**为什么**：庞氏合约需要足够多的交易才能运转；交易太少（如只有 1-2 笔）的合约图特征不稳定。  
**注意**：此特征权重为负（-0.06），说明模型发现交易数量本身不是强庞氏信号。

---

#### 特征 2：`fan_in_norm` — 归一化入资方数量

```python
inbound_senders = {event.from_address for event in inbound}
"fan_in_norm": min(1.0, len(inbound_senders) / 10)
```

**含义**：向合约转账的唯一地址数，除以 10 归一化。  
**为什么**：庞氏骗局需要大量"投资者"持续注资，入资方数量多是特征之一。

---

#### 特征 3：`fan_out_norm` — 归一化出资方数量

```python
outbound_receivers = {event.to_address for event in outbound}
"fan_out_norm": min(1.0, len(outbound_receivers) / 10)
```

**含义**：合约向外转账的唯一地址数。  
**为什么**：庞氏需要持续"返利"给多个地址，是最强的正向特征之一（权重 +0.83）。

---

#### 特征 4：`in_out_balance` — 入出两侧均衡度

```python
fan_delta = abs(len(inbound_senders) - len(outbound_receivers))
fan_total = max(1, len(inbound_senders) + len(outbound_receivers))
"in_out_balance": 1 - min(1.0, fan_delta / fan_total)
```

**含义**：入资方数量和出资方数量越接近，此值越高。  
**为什么**：庞氏的"入"和"出"两侧参与者数量相近（进来多少人就要还多少人）；普通合约往往是一对多（如众筹）或多对一（如交易所）。

---

#### 特征 5：`same_block_payout_rate` — 同区块付款率 ⭐

```python
same_block_payouts = [
    event for event in outbound
    if any(in_event.block_number == event.block_number for in_event in inbound)
]
"same_block_payout_rate": len(same_block_payouts) / max(1, len(outbound))
```

**含义**：合约付款（outbound）中，有多少是发生在"同一区块也有人给合约转账"的区块。  
**为什么**：这是庞氏骗局最典型的行为模式——**新钱一到，立刻用来还老账**。在同一个以太坊区块（约12秒）内就完成了"收钱-付钱"闭环。正常的业务合约很少有这种极度敏感的即时付款行为。

---

#### 特征 6：`recycling_ratio` — 资金回流比率 ⭐

```python
def _recycled_payouts(inbound, outbound):
    inbound_blocks = [event.block_number for event in inbound]
    return [
        payout for payout in outbound
        if any(0 <= payout.block_number - block <= 2 for block in inbound_blocks)
    ]
"recycling_ratio": len(recycled_payouts) / max(1, len(outbound))
```

**含义**：付款事件中，有多少是发生在"有人存钱后 2 个区块以内"。  
**为什么**：比 `same_block_payout_rate` 稍宽松（允许 0-2 个区块的延迟），捕捉的是同样的行为：**新入资几乎立刻被用来支付**。权重与 `same_block_payout_rate` 相同（+0.28），说明两个特征互补。

---

#### 特征 7：`payout_ratio` — 资金流出比

```python
total_in  = sum(event.value for event in inbound)
total_out = sum(event.value for event in outbound)
"payout_ratio": min(1.0, total_out / max(1, total_in))
```

**含义**：合约流出总金额 / 流入总金额。  
**为什么**：庞氏合约几乎把收到的所有 ETH 都还出去（ratio ≈ 1），正常合约通常会留存资金（如 ICO 合约）或消耗资金（如 DeFi 协议）。  
**注意**：此特征权重极小（-0.008），说明单看流出比不够有判别力。

---

#### 特征 8：`degree_centralization` — 合约中心度

```python
possible_edges = max(1, graph.number_of_nodes() * (graph.number_of_nodes() - 1))
centrality = graph.degree(contract) / possible_edges
"degree_centralization": min(1.0, centrality * 4)
```

**含义**：合约节点的度（连接数）占理论最大连接数的比例。  
**为什么**：庞氏合约是交易图的中心枢纽，所有资金都流经它。  
**注意**：权重为负（-1.24），这是最重要的负向特征。可能是因为过于"中心化"的合约在数据集里也包含了正常的大型合约（如 MultiSig Wallet）。

---

#### 特征 9：`temporal_burst` — 时间爆发度

```python
block_counts = Counter(event.block_number for event in events)
max_block_tx = max(block_counts.values(), default=0)
block_span   = max(block_numbers) - min(block_numbers)
"temporal_burst": min(1.0, max_block_tx / max(1, block_span + 1))
```

**含义**：单区块最大交易数 / 总区块跨度，衡量交易在时间上的集中程度。  
**为什么**：庞氏骗局往往有明显的"爆发期"——大量交易集中在极短时间内，然后迅速沉寂。

---

#### 特征 10：`value_concentration` — 资金集中度

```python
counterparty_value = defaultdict(int)
for event in inbound:  counterparty_value[event.from_address] += event.value
for event in outbound: counterparty_value[event.to_address]   += event.value
total_value = sum(counterparty_value.values())
"value_concentration": max(counterparty_value.values()) / total_value
```

**含义**：最大单一对手方的资金流量 / 所有对手方资金流量之和。  
**为什么**：庞氏骗局的操控者（deployer）往往集中提现大量资金，导致资金集中度极高。权重 +0.42，是第三强的正向特征。

---

#### 特征 11：`neighborhood_density` — 邻域密度

```python
possible_edges = max(1, graph.number_of_nodes() * (graph.number_of_nodes() - 1))
density = graph.number_of_edges() / possible_edges
"neighborhood_density": min(1.0, density * 8)
```

**含义**：2-hop 邻域内实际边数 / 理论最大边数。  
**为什么**：如果庞氏参与者之间还互相有交易（如提现后再投入其他庞氏），邻域就会形成高密度子图。权重为负（-0.30），说明密度不是庞氏的强正向信号。

---

#### 特征 12：`reciprocity_rate` — 双向往返率 ⭐

```python
reciprocal_pairs = sum(
    1 for src, tgt in graph.edges()
    if src != tgt and graph.has_edge(tgt, src)
)
undirected = graph.to_undirected()
"reciprocity_rate": min(1.0, reciprocal_pairs / max(1, undirected.number_of_edges()))
```

**含义**：邻域内存在 A→B 又存在 B→A 的边对数量 / 总无向边数。  
**为什么**：这是**模型学到的最强特征**（权重 +2.19）。庞氏网络中，参与者会先存钱、收到返利、然后再存钱，形成大量双向边。这在正常合约中极少出现——普通用户不会既给合约转账，又收到合约转账（除了退款场景）。

---

### 特征提取的代码全貌

```python
# api/tools/graph_classifier.py

def extract_graph_features(events: list[TransferEvent], contract: str) -> dict[str, float]:
    # 分离入站/出站事件
    inbound  = [e for e in events if e.to_address   == contract]
    outbound = [e for e in events if e.from_address == contract]
    
    # 建图（NetworkX 有向图）
    graph = nx.DiGraph()
    for event in events:
        graph.add_edge(event.from_address, event.to_address, weight=event.value)
    
    # 计算各特征...
    return {
        "tx_count_norm":          min(1.0, len(events) / 24),
        "fan_in_norm":            min(1.0, len(inbound_senders) / 10),
        "fan_out_norm":           min(1.0, len(outbound_receivers) / 10),
        "in_out_balance":         1 - min(1.0, fan_delta / fan_total),
        "same_block_payout_rate": len(same_block_payouts) / max(1, len(outbound)),
        "recycling_ratio":        len(recycled_payouts) / max(1, len(outbound)),
        "payout_ratio":           min(1.0, total_out / max(1, total_in)),
        "degree_centralization":  min(1.0, centrality * 4),
        "temporal_burst":         min(1.0, max_block_tx / max(1, block_span + 1)),
        "value_concentration":    max(cv.values()) / total_value if total_value else 0.0,
        "neighborhood_density":   min(1.0, density * 8),
        "reciprocity_rate":       min(1.0, reciprocal_pairs / max(1, undirected.number_of_edges())),
    }
```

---

## 5. 模型训练：逻辑回归从零实现

### 5.1 训练数据构建

对 labels.csv 中每一个地址，在 transactions.csv 中找到它的 2-hop 邻域事件，提取 12 个特征，形成一个训练样本。

```python
# ml/train_graph_classifier.py

def build_dataset(labels, events):
    dataset = []
    for address, label in labels.items():
        neighborhood = contract_neighborhood_events(events, address, hop=2)
        if not neighborhood:
            continue   # 该地址在交易图中没有邻域数据，跳过
        features = extract_graph_features(neighborhood, address)
        dataset.append((features, label))
    return dataset
```

最终有效样本：**308 个**（382 个地址中 74 个在交易图中没有足够邻域数据被剔除）

### 5.2 数据集划分

按照庞氏/非庞氏比例进行分层抽样：

```python
# 训练集 75% = 232 个样本（116 庞氏 + 116 正常）
# 测试集 25% = 76  个样本（37  庞氏 + 39 正常）
train_rows, test_rows = stratified_split(dataset, test_size=0.25, seed=42)
```

### 5.3 梯度下降训练

**没有使用 sklearn**，从零实现了带 L2 正则的逻辑回归：

```python
def train_logistic_regression(rows, epochs=2500, learning_rate=0.25, l2=0.01):
    weights   = {name: 0.0 for name in DEFAULT_FEATURES}  # 初始化所有权重为 0
    intercept = 0.0
    n = len(rows)
    
    for epoch in range(epochs):
        grad_b = 0.0
        grad_w = {name: 0.0 for name in DEFAULT_FEATURES}
        
        for features, label in rows:
            # 前向传播：计算预测概率
            logit = intercept + sum(weights[name] * features[name] for name in DEFAULT_FEATURES)
            pred  = sigmoid(logit)
            
            # 误差 = 预测 - 真实标签
            err = pred - label
            
            # 累加梯度
            grad_b += err
            for name in DEFAULT_FEATURES:
                grad_w[name] += err * features[name]
        
        # 更新参数（梯度下降 + L2 正则化）
        intercept -= learning_rate * grad_b / n
        for name in DEFAULT_FEATURES:
            gradient      = grad_w[name] / n + l2 * weights[name]  # L2 防过拟合
            weights[name] -= learning_rate * gradient
    
    return intercept, weights
```

**L2 正则化的作用**：在梯度中加入 `l2 * weight` 项，使权重倾向于保持较小的值，防止模型对训练数据过拟合。

### 5.4 评估指标

手动实现了所有评估指标（含 AUC）：

```python
def evaluate(rows, intercept, weights):
    predictions = [1 if sigmoid(logit) >= 0.5 else 0 for each sample]
    tp = 真正例（正确预测庞氏）
    fp = 假正例（误报：正常合约被判为庞氏）
    tn = 真负例（正确预测正常）
    fn = 假负例（漏报：庞氏合约被判为正常）
    
    precision = tp / (tp + fp)   # 预测为庞氏的里有多少真的是庞氏
    recall    = tp / (tp + fn)   # 所有真实庞氏里有多少被找到了
    f1        = 2 * precision * recall / (precision + recall)
    auc       = ROC 曲线面积（衡量整体排序能力）
```

---

## 6. 训练结果与模型验证

### 6.1 模型性能指标

训练完成后，模型保存至 `api/models/graph_classifier_v1.json`。

| 指标 | 训练集 | 测试集 |
|------|:------:|:------:|
| Accuracy | 83.6% | **75.0%** |
| Precision | 84.7% | **75.7%** |
| Recall | 81.7% | **73.7%** |
| F1 Score | 83.2% | **74.7%** |
| AUC | 90.2% | **86.6%** |

- 训练样本：232 个（153 庞氏 + 155 正常中取 75%）  
- 测试样本：76 个（未参与训练）  
- 测试集 AUC **0.866** 意味着随机取一个庞氏和一个正常合约，模型有 86.6% 的概率正确识别庞氏的分数更高

### 6.2 学到的特征权重

```
特征权重（正值=庞氏信号，负值=正常信号）

+████████████████████  +2.187   reciprocity_rate       ← 最强信号：双向资金往返
+████████             +0.833   fan_out_norm            ← 向多地址分发资金
+████                 +0.421   value_concentration     ← 资金集中流向少数人
+███                  +0.315   fan_in_norm             ← 多地址向合约注资
+██                   +0.276   same_block_payout_rate  ← 同区块即时付款
+██                   +0.276   recycling_ratio         ← 短窗口资金回流
+██                   +0.223   temporal_burst          ← 时间爆发性
+                     +0.075   in_out_balance
─                     -0.008   payout_ratio
─                     -0.061   tx_count_norm
───                   -0.296   neighborhood_density
────────────          -1.239   degree_centralization   ← 最强负向：过度中心化
```

**截距（intercept）= -1.422**：模型默认偏向"不是庞氏"，需要正向特征积累足够证据才会判为庞氏。

### 6.3 实测验证：庞氏 vs 正常合约的分离度

对 10 个已知庞氏合约 + 10 个正常合约分别推理：

| 类别 | 平均 p_graph | 说明 |
|------|:-----------:|------|
| 庞氏合约（10个） | **0.6436** | 大多数 > 0.5，会被判为庞氏 |
| 正常合约（10个） | **0.2733** | 大多数 < 0.5，会被判为正常 |
| **分离度** | **0.3704** | 远超阈值 0.2，区分能力显著 |

部分样本详情：
```
地址              真实      p_graph    判断
0x143e8bd26d…   庞氏      0.8111     庞氏 ✓
0x16a4ff5360…   庞氏      0.8716     庞氏 ✓
0x1afd952269…   庞氏      0.8816     庞氏 ✓
0xf41624c646…   正常      0.1922     正常 ✓
0x0230cfc895…   正常      0.0522     正常 ✓
0x268b7976e9…   正常      0.2363     正常 ✓
```

---

## 7. 推理：一个新地址进来如何判断

当系统收到一个待分析的合约地址时，推理流程如下：

```python
# api/tools/graph_classifier.py

def classify_graph(events: list[TransferEvent], contract_address: str):
    
    # Step 1：从全局交易图中，提取以该合约为中心的 2-hop 子图
    related_events = contract_neighborhood_events(events, contract_address, hop=2)
    
    # Step 2：计算 12 个图特征
    features = extract_graph_features(related_events, contract_address)
    
    # Step 3：加载训练好的模型权重
    model = json.load(open("api/models/graph_classifier_v1.json"))
    
    # Step 4：逻辑回归推理
    logit = model["intercept"] + sum(
        model["weights"][name] * features[name]
        for name in DEFAULT_FEATURES
    )
    probability = 1 / (1 + math.exp(-logit))  # sigmoid
    
    # Step 5：返回结果
    return {
        "p_graph":   round(probability, 4),   # 庞氏概率
        "features":  features,                 # 12个特征值（可解释）
        "evidence":  top_3_contributing_features,  # 最重要的3个证据
    }
```

输出示例（一个庞氏合约）：
```json
{
  "p_graph": 0.8716,
  "features": {
    "reciprocity_rate": 0.82,
    "fan_out_norm": 0.90,
    "same_block_payout_rate": 0.71,
    ...
  },
  "evidence": [
    "邻域内存在双向资金往返 (0.82)",
    "合约向多个地址分发资金 (0.90)",
    "新资金进入后同区块出现 payout (0.71)"
  ]
}
```

---

## 8. 三通道融合：ML 如何服务于整体系统

图分类器（`p_graph`）只是整个检测系统的一个通道。系统共有三个独立的检测维度，最终融合输出一个综合风险分。

```
输入：合约地址 + 历史交易
          │
    ┌─────┴──────┐
    │            │
    ▼            ▼
┌──────────┐ ┌──────────────────┐ ┌────────────────┐
│ 通道 1   │ │ 通道 2           │ │ 通道 3         │
│ 图分类   │ │ 中介节点检测     │ │ 生命周期评分   │
│          │ │                  │ │                │
│ → p_graph│ │ → 中介节点列表   │ │ → score [0,1]  │
│ 庞氏概率 │ │ 洗钱路由检测     │ │ 运营阶段判断   │
│  [0,1]   │ │                  │ │                │
└────┬─────┘ └────────┬─────────┘ └───────┬────────┘
     │                │                   │
     └────────────────┴───────────────────┘
                      │
                      ▼
              fuse_risk() 融合函数
                      │
                      ▼
          risk_score: 0~100  |  risk_level: HIGH/MEDIUM/LOW
```

### 通道 1：图分类器（本文档的主题）
- 输出：`p_graph` ∈ [0, 1]
- 检测的是：交易图的**结构模式**

### 通道 2：中介节点检测（`api/tools/intermediary_detector.py`）
- 基于图论中的 **Betweenness Centrality（介数中心性）**
- 检测的是：在庞氏网络中充当"资金中转站"的地址
- 这些地址往往是庞氏运营者用来洗钱或中转资金的钱包

### 通道 3：生命周期评分（`api/tools/lifecycle_scorer.py`）
- 检测的是：合约是否处于庞氏骗局的典型**生命周期阶段**
  - 早期：大量资金流入，少量流出（吸引投资者）
  - 中期：资金流入流出平衡（庞氏维持期）
  - 晚期：资金快速流出，新入资减少（崩盘前夕）

### 融合逻辑（`api/tools/risk_fusion.py`）

```python
def fuse_risk(p_graph, lifecycle_score, intermediary_count):
    # 三个信号加权融合
    raw_score = (
        0.5 * p_graph           +   # 图分类最重要
        0.3 * lifecycle_score   +   # 生命周期次之
        0.2 * min(1.0, intermediary_count / 3)  # 中介节点辅助
    ) * 100
    
    risk_level = "HIGH" if raw_score > 70 else "MEDIUM" if raw_score > 40 else "LOW"
    return {"risk_score": raw_score, "risk_level": risk_level}
```

**为什么要三通道融合？**  
单一指标误报率高。`p_graph` 高可能只是因为该合约是大型 DEX（交易所），本身交易模式复杂；但如果同时检测到生命周期异常 + 中介节点，则综合置信度大幅提升。

---

## 9. 代码归属一览

| 代码 / 数据 | 归属 | 来源 |
|------------|------|------|
| `labels.csv` 中庞氏地址 | **直接复用学术数据集** | `blockchain-unica/ethereum-ponzi` (GitHub) |
| `labels.csv` 中正常地址 | **直接复用学术数据集** | `xuyl0104/blockchain_ponzi_detection` (GitHub) |
| `eth-whitepaper-java-main/` | **完整 copy 第三方库** | `nakamo/eth-whitepaper-java` (GitHub) |
| `fetch_transactions.py` | **AI 辅助生成** | 数据工程工具脚本 |
| `api/tools/graph_classifier.py` | **原创核心算法** | 特征设计灵感来自 EthXpose 论文 |
| `ml/train_graph_classifier.py` | **原创** | 从零实现逻辑回归 |
| `api/main.py` | **原创** | 三通道 FastAPI 后端 |
| `api/tools/intermediary_detector.py` | **原创** | 介数中心性算法 |
| `api/tools/lifecycle_scorer.py` | **原创** | 生命周期评分逻辑 |
| `api/tools/risk_fusion.py` | **原创** | 三通道融合逻辑 |
| `api/models/graph_classifier_v1.json` | **训练产物** | 由训练脚本自动生成，权重是数据训练的结果 |

---

## 10. 复现步骤

如果你想从零复现 ML 部分，按以下顺序执行：

### 前提：安装依赖

```bash
cd PonziShield/ponzi-detector
pip3 install networkx fastapi uvicorn
```

### Step 1：确认标签数据

```bash
wc -l data/processed/labels.csv
# 预期输出: 383（1 行表头 + 182 庞氏 + 200 正常）
```

### Step 2：拉取真实交易数据（需 Etherscan API Key）

```bash
# 申请免费 Key: https://etherscan.io/apis
python3 fetch_transactions.py --api-key YOUR_KEY
# 运行约 15 分钟，输出 data/raw/xblock/transactions.csv
# 支持断点续传，中断后重新运行会跳过已下载的地址
```

### Step 3：训练模型

```bash
PYTHONPATH=. python3 ml/train_graph_classifier.py
# 输出: api/models/graph_classifier_v1.json
# 预期: Train F1 ≈ 0.83, Test F1 ≈ 0.75, Test AUC ≈ 0.87
```

### Step 4：启动后端 API

```bash
PYTHONPATH=. uvicorn api.main:app --reload --port 8000
# 访问 http://localhost:8000/docs 查看 API 文档
```

### Step 5：发送分析请求

```bash
# 先注入一些交易数据
curl -X POST http://localhost:8000/api/v1/transfer \
  -H "Content-Type: application/json" \
  -d '{"from":"0xabc","to":"0xe82719202e5965cf5d9b6673b7503a3b92de20be","value":"1000000000000000000","block_number":1150481,"tx_hash":"0xtest","timestamp":1458627840}'

# 分析合约
curl -X POST http://localhost:8000/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{"contract_address":"0xe82719202e5965cf5d9b6673b7503a3b92de20be","current_block":1151000}'
```

---

## 附：关键论文引用

1. Bartoletti, M., et al. (2020). *Dissecting Ponzi schemes on Ethereum: identification, analysis, and impact.* Future Generation Computer Systems. — **庞氏地址黑名单数据集来源**

2. Chen, W., et al. (2018). *Detecting Ponzi Schemes on Ethereum: Towards Healthier Blockchain Technology.* WWW'18. — **图分析方法参考**

3. Huang, Y., et al. (2020). *EthXpose: Anomaly Transaction Detection on Ethereum.* — **2-hop 邻域特征设计参考**
