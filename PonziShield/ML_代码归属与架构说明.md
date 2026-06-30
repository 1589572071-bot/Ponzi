# PonziShield ML 模块：代码归属与架构说明

> 作者：anchenrui（ML检测负责人）  
> 更新：2026-06-30

---

## 一、为什么需要 Etherscan API Key？能不能直接下载现成数据？

**结论：所有相关 GitHub 项目只存了"地址"，没有存"每一笔交易记录"。**

训练脚本 `train_graph_classifier.py` 需要两个文件：

```
labels.csv          → 每个地址是否为庞氏合约（已有 ✅）
transactions.csv    → 这些地址的逐笔交易记录（这是缺失的 ❌）
```

`transactions.csv` 需要的字段：

```
from, to, value, block_number, tx_hash, timestamp
```

各个 GitHub 学术项目只存了地址黑名单，不存交易记录，原因是：数百个合约的交易数据总量太大，不适合放进 git。他们的原始代码都是用脚本去 Etherscan 实时拉取的（见 `xuyl0104/blockchain_ponzi_detection` 的 `datacollector.py`，但该项目的私有数据源 `ibasetest.inpluslab.com` 已下线）。

| 方案 | 可行性 | 说明 |
|------|--------|------|
| 从 GitHub 下载现成交易数据 | ❌ 不存在 | 所有相关项目只存地址 |
| 从 Etherscan V2 API 拉取 | ✅ 需 Key | 免费注册即可，申请地址：https://etherscan.io/apis |
| 直接用现有模型，不重训 | ✅ 可行 | `graph_classifier_v1.json` 已存在，可直接推理 |

---

## 二、代码归属完整分析

### 第一层：完全 Copy 自 GitHub（需引用来源）

| 文件 / 内容 | 来源项目 | 说明 |
|------------|---------|------|
| `data/processed/labels.csv` 中的 182 个庞氏地址 | `blockchain-unica/ethereum-ponzi`（意大利 Cagliari 大学） | 论文配套黑名单数据集，直接下载 |
| `data/processed/labels.csv` 中的 200 个非庞氏地址 | `xuyl0104/blockchain_ponzi_detection` 的 `non_ponziContracts.csv` | 正常合约地址，直接下载 |
| `eth-whitepaper-java-main/` 整个 Java 目录 | `nakamo/eth-whitepaper-java` | 以太坊白皮书 Java 实现，EVM 模拟器，完整 copy |

### 第二层：AI 辅助生成的代码（需理解、能解释）

| 文件 | 内容描述 |
|------|---------|
| `fetch_transactions.py` | 从 Etherscan V2 API 批量拉取 382 个地址的交易数据，支持断点续传，处理 macOS SSL 证书问题 |
| `api/models/graph_classifier_v1.json` 里的权重数值 | 由 `train_graph_classifier.py` 训练自动生成，权重本身是训练结果 |

### 第三层：你们组原创的核心代码（作业真正的贡献点）

这部分是项目灵魂，也是 Report 和 PPT 应该重点展示的。

---

## 三、核心代码逐文件解析

### 3.1 `api/tools/graph_classifier.py` — 图分类器

**这是整个 ML 模块最核心的文件。**

#### 特征工程（12 个图结构特征）

```python
def extract_graph_features(events, contract):
```

| 特征名 | 含义 | 对应的庞氏行为直觉 |
|--------|------|------------------|
| `tx_count_norm` | 归一化交易数量 | 庞氏通常有中等规模交易 |
| `fan_in_norm` | 归一化入资方数量 | 很多人向同一合约注资 |
| `fan_out_norm` | 归一化出资方数量 | 合约向很多人分钱 |
| `in_out_balance` | 注资侧与分发侧的结构均衡度 | 庞氏的入出两侧结构相似 |
| `same_block_payout_rate` | **新钱进来、同区块就付出去的比率** | 庞氏的核心行为特征 |
| `recycling_ratio` | **短窗口内资金回流的比率** | 用新入资偿还老投资者 |
| `payout_ratio` | 流出总额 / 流入总额 | 接近 1 说明在持续转移资金 |
| `degree_centralization` | 合约在交易图中的中心化程度 | 庞氏是图的核心枢纽 |
| `temporal_burst` | 交易在短时间窗口内的爆发程度 | 庞氏运营期有阵发性特征 |
| `value_concentration` | 资金集中流向少数地址的程度 | 运营者集中提现 |
| `neighborhood_density` | 2-hop 邻域的有向边密度 | 庞氏网络是稠密子图 |
| `reciprocity_rate` | 邻域内双向资金往返的比率 | 存在资金洗回行为 |

**特征设计思路**来自 EthXpose 论文的图分析方法，具体数值阈值和组合方式是原创设计。

#### 推理逻辑

```python
def classify_graph(events, contract_address):
    # 1. 提取以合约为中心的 2-hop 邻域事件
    related = contract_neighborhood_events(events, contract, hop=2)
    # 2. 计算 12 个图特征
    features = extract_graph_features(related, contract)
    # 3. 逻辑回归推理：logit = intercept + Σ(weight_i × feature_i)
    logit = model["intercept"] + sum(model["weights"][name] * features[name] ...)
    # 4. Sigmoid 函数转为概率
    probability = 1 / (1 + exp(-logit))
    return {"p_graph": probability, "features": features, ...}
```

输出 `p_graph`：合约是庞氏骗局的概率（0~1）。

---

### 3.2 `ml/train_graph_classifier.py` — 训练脚本

**从零实现的逻辑回归，没有依赖 sklearn。**

核心训练循环（梯度下降 + L2 正则化）：

```python
def train_logistic_regression(rows, epochs, learning_rate, l2):
    for _ in range(epochs):
        for features, label in rows:
            pred = sigmoid(intercept + Σ weights[name] * features[name])
            err = pred - label
            # 梯度更新
            intercept -= learning_rate * err / n
            weights[name] -= learning_rate * (err * feature + l2 * weight) / n
```

评估指标：Accuracy、Precision、Recall、F1、AUC（手动实现 ROC-AUC）。

默认参数：
- epochs = 2500
- learning_rate = 0.25
- L2 正则 = 0.01
- test_size = 25%

---

### 3.3 `api/main.py` — 后端 API（三通道融合）

```python
@app.post("/api/v1/analyze")
def analyze(payload):
    # 通道1：图结构分类
    graph_prediction = classify_graph(events, contract)       # → p_graph
    # 通道2：中介节点检测（洗钱路由检测）
    intermediaries = detect_intermediaries(events, contract)  # → 中介节点列表
    # 通道3：生命周期评分
    lifecycle = score_lifecycle(events, contract, block)      # → 阶段评分
    # 融合
    risk = fuse_risk(p_graph, lifecycle_score, len(intermediaries))
    return {"risk_score": ..., "risk_level": "HIGH/MEDIUM/LOW"}
```

**三通道融合**是系统的核心创新点：单通道误报率高，三通道交叉验证大幅提升准确性。

---

### 3.4 `fetch_transactions.py` — 数据获取脚本（AI 生成）

作用：遍历 `labels.csv` 中 382 个合约地址，调用 Etherscan V2 API 获取每个地址的普通交易和内部交易，输出 `data/raw/xblock/transactions.csv`，供训练脚本使用。

```bash
# 用法
cd PonziShield/ponzi-detector
python3 fetch_transactions.py --api-key YOUR_KEY

# 拉完后训练
python3 ml/train_graph_classifier.py
```

---

## 四、数据流全景图

```
数据来源（GitHub 学术项目）
│
├── blockchain-unica/ethereum-ponzi → 182 个庞氏地址（label=1）
│                                              ↓
└── xuyl0104/blockchain_ponzi_detection → 200 个正常地址（label=0）
                                              ↓
                                    labels.csv（382 条）
                                              ↓
                              fetch_transactions.py
                                              ↓
                          Etherscan V2 API（需 API Key）
                                              ↓
                              transactions.csv（逐笔交易）
                                              ↓
                          train_graph_classifier.py
                              提取 12 个图特征
                              逻辑回归训练
                                              ↓
                          graph_classifier_v1.json（模型权重）
                                              ↓
                    ┌─────────────────────────────────────┐
                    │      api/main.py 三通道推理          │
                    │  图分类 + 中介检测 + 生命周期评分    │
                    └─────────────────────────────────────┘
                                              ↓
                              前端 React Dashboard 展示
```

---

## 五、Report / PPT 重点展示建议

### 你们的原创贡献（应该重点讲）

1. **12 个图特征的设计**：每个特征背后的庞氏行为直觉（尤其 `same_block_payout_rate` 和 `recycling_ratio`）
2. **三通道融合架构**：为什么单通道不够，三通道如何互补
3. **数据集构建过程**：从两个学术项目中分别获取正负样本，以及选择依据

### 需要引用的论文 / 数据集

- Bartoletti et al., *Dissecting Ponzi schemes on Ethereum: identification, analysis, and impact* (2020) — 地址黑名单来源
- Chen et al., *Ponzi Scheme Detection for Ethereum* — 图分析方法参考
- EthXpose — 图结构特征设计参考
- `blockchain-unica/ethereum-ponzi` (GitHub) — 庞氏地址数据集
- `xuyl0104/blockchain_ponzi_detection` (GitHub) — 正常合约地址数据集

### 注意事项

- Java EVM 模拟器（`eth-whitepaper-java-main/`）是 copy 的第三方库，在 Report 中应标注为"基于 xxx 改编"
- `fetch_transactions.py` 是 AI 辅助生成的工具脚本，属于数据工程部分，不是算法贡献

---

## 六、卡点：重新训练模型

当前 `graph_classifier_v1.json` 是用**模拟数据**训练的（权重可能不准）。

要用真实链上数据重训，需要：
1. 去 https://etherscan.io/apis 注册，获取免费 API Key
2. 运行 `fetch_transactions.py --api-key YOUR_KEY`（约 10 分钟）
3. 运行 `ml/train_graph_classifier.py`（约 1 分钟）
4. 新模型自动覆盖 `api/models/graph_classifier_v1.json`
