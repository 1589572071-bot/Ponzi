# PonziShield 产品需求文档（PRD）

| 字段 | 内容 |
|------|------|
| **产品名称** | PonziShield |
| **版本** | v1.1 |
| **文档状态** | Review |
| **创建日期** | 2026-06-25 |
| **更新日期** | 2026-06-26 |
| **课程主题** | Smart Contract Application（Hard）+ Complete Mining Algorithm |
| **仓库路径** | `/PonziShield` |

---

## 1. 文档概述

### 1.1 目的

本文档定义 **PonziShield** 的完整产品需求，作为开发、测试、演示与期末报告的唯一需求基准。PonziShield 是一个嵌入区块链节点交易验证流程的智能合约庞氏骗局检测系统。

### 1.2 范围

| 范围内 | 范围外 |
|--------|--------|
| 本地 Java 挖矿/验证节点（eth-whitepaper-java） | 主网部署、真实 ETH 资产 |
| Python 交易图检测引擎（基于 ethXpose fork） | 链上强制拦截投资（v2 规划） |
| 中介节点识别（优化 1） | LLM 大模型检测（不使用 PonziLicle LLM 模块） |
| 生命周期五维评分（优化 2） | 完整 Neo4j + GNN 生产环境（可选加分项） |
| FastAPI 服务 + CLI/简易 Web 演示 | 移动端 App |
| 实验评估与期末报告素材 | 商业化运营 |

### 1.3 术语表

| 术语 | 定义 |
|------|------|
| **Full Node / Miner** | 维护完整 state trie 并验证/执行交易的全节点 |
| **TransferGraph** | 以地址为节点、转账为边的有向交易图（NetworkX DiGraph） |
| **Intermediary Node** | 介数中心性高、资金短暂停留的中转地址 |
| **Lifecycle Stage** | 庞氏合约从募资到崩溃的四个阶段 |
| **五维特征** | fund_flow / profit_logic / referral / withdrawal_control / camouflage |
| **Risk Score** | 0–100 综合风险分，由多通道检测融合得出 |

---

## 2. 背景与问题陈述

### 2.1 行业背景

以太坊等公链上的智能合约庞氏骗局（Ponzi Scheme）已造成数亿美元损失。现有检测工具（PonziGuard、SADPonzi、X-SPIDE 等）多为 **离线批量扫描**，无法在节点验证交易时同步发现风险。

### 2.2 课程要求

课程 **Complete Mining Algorithm** 要求全节点：

1. 本地维护 state / transaction / receipt / storage trie
2. 监听并转发交易与区块
3. **验证（执行）** 区块内每笔交易
4. 处理 gas 与手续费分配
5. 校验各 trie root；挖矿时调整 nonce 满足难度

**PonziShield 的切入点**：在步骤 3「执行交易」时，同步捕获资金流转，实时构建交易图并评分。

### 2.3 核心问题

| # | 问题 | 现有方案不足 |
|---|------|-------------|
| P1 | 如何在不改共识的前提下检测庞氏合约？ | 多数工具需改 Geth 或离线跑批 |
| P2 | 如何识别资金中转的「中介节点」？ | opcode 检测看不到链上资金流 |
| P3 | 庞氏骗局在不同阶段特征不同，如何感知？ | 静态检测无法区分募资期 vs 崩溃期 |
| P4 | 如何与挖矿验证流程形成可演示闭环？ | 学术项目缺少端到端 demo |

### 2.4 产品定位（一句话）

> **PonziShield = 挖矿验证节点 + 实时交易图分析 + 中介节点识别 + 生命周期评分 + 风险告警 API**

与 ChainGuard 原方案的差异：v1.0 聚焦 **检测 + 告警**；链上强制拦截（RiskGateway）列为 v2.0。

---

## 3. 目标与非目标

### 3.1 产品目标

| ID | 目标 | 衡量标准 |
|----|------|----------|
| G1 | 在交易验证时实时捕获转账事件 | 每笔 transfer 延迟写入 < 50ms |
| G2 | 对目标合约输出综合风险分 | API 响应 < 2s（100 节点以内子图） |
| G3 | 识别中介节点并解释原因 | 返回节点列表 + 中心性指标 |
| G4 | 输出生命周期阶段与五维解释 | 4 阶段 + 5 维布尔/分值 |
| G5 | 可演示端到端场景 | 15 分钟内完成 live demo |
| G6 | 实验 F1 不低于 ethXpose 基线 | F1 ≥ 0.85（XBlock 测试集） |

### 3.2 非目标（v1.0 不做）

- 不连接以太坊主网 RPC（使用本地 Java 链）
- 不使用 LLM / 外部 API Key
- 不实现链上投资拦截合约
- 不追求 GNN 达到 SOTA（作为可选扩展）
- 不实现 P2P 网络广播（eth-whitepaper-java 本身不含 networking）

---

## 4. 用户与场景

### 4.1 用户画像

| 角色 | 描述 | 核心诉求 |
|------|------|----------|
| **开发者/矿工节点** | 运行 Java 全节点 | 验证区块时自动检测可疑合约 |
| **安全分析员** | 期末项目演示者 | 输入合约地址，获得可读报告 |
| **课程评审** | 教授/TA | 看到挖矿算法 + 智能合约应用结合 |

### 4.2 核心用户故事

| ID | 用户故事 | 优先级 |
|----|----------|--------|
| US-01 | 作为矿工，我希望每笔交易执行后自动记录转账，以便构建交易图 | P0 |
| US-02 | 作为分析员，我希望输入合约地址获得 0–100 风险分 | P0 |
| US-03 | 作为分析员，我希望看到疑似中介节点列表及其中介性指标 | P0 |
| US-04 | 作为分析员，我希望知道合约处于哪个生命周期阶段 | P0 |
| US-05 | 作为分析员，我希望看到五维特征的可解释 breakdown | P0 |
| US-06 | 作为演示者，我希望能对比 PonziContract vs TokenContract 的检测结果 | P0 |
| US-07 | 作为分析员，我希望可视化交易图 | P1 |
| US-08 | 作为研究者，我希望导出 JSON 报告用于论文 | P1 |
| US-09 | 作为开发者，我希望用 GNN 子图分类提升准确率 | P2（可选） |

---

## 5. 系统架构

### 5.1 四层架构

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1 · 区块链基座（Java）                                  │
│ eth-whitepaper-java-main                                     │
│ BlockProcessor / TransactionProcessor / WorldState           │
│ + FundFlowEmitter（自研）                                     │
│ + PonziContract / TokenContract（自研 demo）                  │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP POST /api/analyze
                           │ {from, to, value, block, contract}
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2 · 交易图引擎（Python，fork ethXpose）                   │
│ transfer_graph.py      ← make_graph.py                       │
│ intermediary_detector.py ← Web3AnalyticsPython               │
│ subgraph_extract.py    ← Ponzi-Warning（可选）                │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3 · 双通道检测器                                        │
│ graph_classifier.py    ← ethXpose classify（Feather-G+GB）   │
│ lifecycle_scorer.py    ← PonziLicle 五维规则                  │
│ risk_fusion.py         ← 自研融合公式                         │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 4 · 服务与演示                                          │
│ FastAPI（ethXpose 保留）                                      │
│ CLI ponzi-shield analyze <address>                           │
│ 可选：ethXpose-frontend                                      │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 运行时数据流

```
1. 攻击者/用户调用 PonziContract.invest{value: 1 ETH}()
2. BlockProcessor.validateAndApply() 执行交易
3. FundFlowEmitter.onTransfer(from, to, value, blockNumber, txHash)
4. HTTP POST → FastAPI /api/v1/transfer
5. Python:
   a. TransferGraph.add_edge(...)
   b. subgraph = extract_subgraph(contract_addr, hop=1)
   c. intermediaries = detect_intermediaries(subgraph)
   d. p_graph = graph_classifier.predict(subgraph)
   e. lifecycle = lifecycle_scorer.score(contract_addr, blockNumber)
   f. risk = fuse(p_graph, lifecycle, intermediaries)
6. 返回 AnalysisReport JSON
7. Java 侧 / CLI / Web 展示告警
```

### 5.3 目录结构（目标）

```
PonziShield/
├── eth-whitepaper-java-main/          # Layer 1（已有）
│   └── src/.../ponzi/
│       ├── FundFlowEmitter.java
│       ├── PonziContract.java
│       └── AnalysisClient.java
├── ponzi-detector/                    # Layer 2–4（fork ethXpose）
│   ├── api/
│   │   ├── main.py                    # FastAPI 入口
│   │   └── tools/
│   │       ├── make_graph.py          # 保留
│   │       ├── classify.py            # 保留
│   │       ├── intermediary_detector.py  # 新增
│   │       ├── lifecycle_scorer.py       # 新增
│   │       ├── subgraph_extract.py       # 新增
│   │       └── risk_fusion.py            # 新增
│   ├── models/                        # 预训练 GB 模型
│   ├── data/                          # XBlock 数据集
│   └── requirements.txt
├── docs/
│   ├── PonziShield_PRD.md             # 本文档
│   └── ChainGuard_相关GitHub项目汇总.md
└── scripts/
    ├── demo.sh                        # 一键演示
    └── start_services.sh
```

### 5.4 Layer 1 模块实现定稿

> 经 GitHub 调研确认：**无可直接 fork 的 Java 版 PonziContract 或 FundFlowEmitter**。Layer 1 采用「Solidity 逻辑移植 + 本地模板 + 薄封装 Observer」方案，不引入 Web3j / eventeum / Magician-Scanning。

#### 5.4.1 PonziContract.java

| 维度 | 定稿方案 |
|------|----------|
| **逻辑来源** | [alexroan/EthereumPonzi](https://github.com/alexroan/EthereumPonzi)：`Tree.sol`（推荐机制 + 多级 fund_flow）+ `GradualPonzi.sol`（余额分配 + withdraw） |
| **代码结构** | 本地 `TokenContract.java` → 继承 `AbstractNativeContract`，注册于 `ContractCatalog` |
| **五维规格** | [PonziLicle/PonziDetector.py](https://github.com/chenpp1881/PonziLicle/blob/main/PonziDetector.py) 对照验收（仅静态规则，不用 LLM） |
| **标注数据** | [blockchain-unica/ethereum-ponzi](https://github.com/blockchain-unica/ethereum-ponzi) 可选验证行为模式 |
| **实现路径** | `eth-whitepaper-java-main/src/main/java/dev/naoki/ethwhite/sample/PonziContract.java` |

**Solidity → Java 移植映射**：

| 源文件 | 行为 | Java 方法 | 对应五维 |
|--------|------|-----------|----------|
| `Tree.sol` | `enter(inviter)` 收 ETH，沿 inviter 链逐级分红 | `stake(referrer)` *（故意伪装命名 → camouflage）* | referral, fund_flow, profit_logic |
| `Tree.sol` | `mapping(address => User)` 推荐树 | storage: `referrer:{addr}` | referral_mechanism |
| `GradualPonzi.sol` | 新投资分给所有旧投资者 | `_distributeToInvestors()` | fund_flow, profit_logic |
| `GradualPonzi.sol` | `withdraw()` 提取累积余额 | `withdraw()` + 区块时间锁 | withdrawal_control |
| `GradualPonzi.sol` | `investors[]` 投资者列表 | storage: `investor:{i}` | fund_flow |

**对外 API（CallData method）**：

| 方法 | 参数 | 说明 |
|------|------|------|
| `stake` | `referrer`（Address hex） | 投资入口，最低 0.01 ETH；触发多级分红 |
| `withdraw` | 无 | 提取余额；需满足 `blockNumber - lastStakeBlock >= lockBlocks` |
| `balanceOf` | `owner` | 查询待提取余额 |
| `referrerOf` | `owner` | 查询推荐人 |

**对照组**：复用现有 `TokenContract`（正常 ERC-like 行为，`risk_level` 应为 LOW/MEDIUM）。

#### 5.4.2 FundFlowEmitter.java

| 维度 | 定稿方案 |
|------|----------|
| **实现方式** | 自研 Observer 模式（约 30–50 行，无第三方依赖） |
| **挂载点** | 首选 `WorldState.transfer()` 末尾；备选 `TransactionProcessor.apply()` 成功路径 |
| **下游** | 异步 POST → ethXpose FastAPI `POST /api/v1/transfer` |
| **失败策略** | API 不可达时追加写入本地 JSON 队列 `data/pending_transfers.jsonl` |
| **实现路径** | `eth-whitepaper-java-main/src/main/java/dev/naoki/ethwhite/ponzi/FundFlowEmitter.java` |
| **HTTP 客户端** | `AnalysisClient.java`（封装 POST，可配置 `analysis.api.url`） |

**不采用的 GitHub 项目及原因**：

| 项目 | 为何不采用 |
|------|-----------|
| [Web3j](https://docs.web3j.io/) | 订阅外部 Geth RPC，无法嵌入 `BlockProcessor.validateAndApply()` |
| [eventeum](https://github.com/eventeum/eventeum) | 独立微服务 + Kafka，过重，非验证流程内嵌 |
| [Magician-Scanning](https://github.com/Magician-Blockchain/Magician-Scanning) | 公链 RPC 扫描器，不参与本地区块验证 |
| [PonziGuard geth_detect](https://github.com/PonziDetection/PonziGuard) | 需改 C++ Geth 源码，与 Java 白皮书基座不兼容 |

**接口定义**：

```java
public interface FundFlowListener {
    void onTransfer(Address from, Address to, BigInteger value,
                    long blockNumber, byte[] txHash, long timestamp);
}

public final class FundFlowEmitter implements FundFlowListener {
    // 单例；register 到 WorldState；异步 dispatch
}
```

**挂载伪代码**（`WorldState.transfer()`）：

```java
getOrCreate(from).debit(amount);
getOrCreate(to).credit(amount);
if (fundFlowEmitter != null) {
    fundFlowEmitter.onTransfer(from, to, amount, currentBlock, currentTxHash, timestamp);
}
```

**POST `/api/v1/transfer` 载荷**（与 §8.1 一致）：

```json
{
  "from": "0xabc...",
  "to": "0xdef...",
  "value": "1000000000000000000",
  "block_number": 42,
  "tx_hash": "0x123...",
  "timestamp": 1719300000
}
```

#### 5.4.3 Layer 1 文件清单

| 文件 | 类型 | 来源 |
|------|------|------|
| `sample/PonziContract.java` | 新增 | 移植 alexroan/EthereumPonzi |
| `ponzi/FundFlowEmitter.java` | 新增 | 自研 Observer |
| `ponzi/AnalysisClient.java` | 新增 | 自研 HTTP 客户端 |
| `ponzi/FundFlowEvent.java` | 新增 | 自研 DTO |
| `sample/ContractCatalog.java` | 修改 | 注册 `ponzi` 合约 id |
| `core/WorldState.java` | 修改 | 注入 FundFlowEmitter 回调 |
| `sample/TokenContract.java` | 保留 | 正常对照组 |
| `ponzi/PonziDemoMain.java` | 新增 | 部署 → 10×stake → 3×withdraw |

---

## 6. 功能需求

### 6.1 Layer 1 — Java 区块链基座

#### FR-1.1 交易验证与执行（继承 eth-whitepaper-java）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-1.1.1 | 维护 WorldState，支持 transfer / contract call | P0 |
| FR-1.1.2 | BlockProcessor 验证 stateRoot / txRoot / gasUsed | P0 |
| FR-1.1.3 | 支持 PoW 难度校验与 uncle 奖励 | P0 |

#### FR-1.2 FundFlowEmitter（自研 Observer，详见 §5.4.2）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-1.2.1 | 在 `WorldState.transfer()` 末尾触发 `FundFlowListener.onTransfer()` | P0 |
| FR-1.2.2 | 记录字段：`from`, `to`, `value`, `blockNumber`, `txHash`, `timestamp` | P0 |
| FR-1.2.3 | 异步 POST 至 `POST /api/v1/transfer`（ethXpose FastAPI）；失败写 `pending_transfers.jsonl` | P0 |
| FR-1.2.4 | 可配置：`analysis.api.url`（默认 `http://localhost:8000`）、`analysis.enabled` | P1 |
| FR-1.2.5 | **不采用** Web3j / eventeum / Magician-Scanning / PonziGuard geth_detect | P0 |

#### FR-1.3 PonziContract（移植 alexroan/EthereumPonzi，详见 §5.4.1）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-1.3.1 | 移植 `Tree.sol` + `GradualPonzi.sol` 为 `PonziContract.java`（结构仿 `TokenContract.java`） | P0 |
| FR-1.3.2 | 暴露方法：`stake(referrer)` / `withdraw()` / `balanceOf` / `referrerOf` | P0 |
| FR-1.3.3 | 五维行为覆盖：fund_flow、profit_logic、referral、withdrawal_control、camouflage（`stake` 伪装命名） | P0 |
| FR-1.3.4 | 复用 `TokenContract` 作为正常对照组 | P0 |
| FR-1.3.5 | `PonziDemoMain`：部署 → 10 笔 stake → 3 笔 withdraw | P0 |
| FR-1.3.6 | 可选：用 blockchain-unica/ethereum-ponzi 地址行为做对照验证 | P2 |

---

### 6.2 Layer 2 — 交易图引擎

#### FR-2.1 TransferGraph（基于 ethXpose `make_graph.py`）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-2.1.1 | 使用 NetworkX `DiGraph`，边属性含 `weight`, `blockNumber`, `timestamp` | P0 |
| FR-2.1.2 | 支持增量 `add_edge()`，无需每次全量重建 | P0 |
| FR-2.1.3 | 支持导出 GraphML / JSON 供可视化 | P1 |
| FR-2.1.4 | 支持以合约地址为中心提取 1 阶/2 阶子图 | P0 |

#### FR-2.2 中介节点检测（优化 1，基于 Web3AnalyticsPython）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-2.2.1 | 计算 `betweenness_centrality`, `degree_centrality`, `in_degree`, `out_degree` | P0 |
| FR-2.2.2 | 计算 `avg_holding_blocks`（资金停留区块数） | P0 |
| FR-2.2.3 | 中介判定规则（满足 ≥2 项）：见 §7.1 | P0 |
| FR-2.2.4 | 返回 `IntermediaryNode[]`：address, role, scores, evidence | P0 |

#### FR-2.3 子图抽取（可选，借鉴 Ponzi-Warning）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-2.3.1 | 以目标合约为中心 BFS 提取 N 跳子图（默认 N=1） | P1 |
| FR-2.3.2 | 按时间戳排序边，支持截取最近 K 条（默认 K=100） | P1 |
| FR-2.3.3 | 可选：接入 Ponzi-Warning GNN 模型做子图分类 | P2 |

---

### 6.3 Layer 3 — 双通道检测器

#### FR-3.1 图嵌入分类（基于 ethXpose `classify.py`）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-3.1.1 | 加载预训练 `first_Feather-G_GB.joblib` 模型 | P0 |
| FR-3.1.2 | 子图 → Feather-G embedding → 庞氏概率 `p_graph` ∈ [0,1] | P0 |
| FR-3.1.3 | 支持离线 `train.py` 重训练 | P1 |

#### FR-3.2 生命周期评分（优化 2，基于 PonziLicle 五维）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-3.2.1 | 维护合约 `deployBlock`，计算 `age = currentBlock - deployBlock` | P0 |
| FR-3.2.2 | 判定生命周期阶段：FUNDRAISING / PAYOUT / STAGNATION / COLLAPSE | P0 |
| FR-3.2.3 | 五维特征检测（静态规则，不用 LLM）：见 §7.2 | P0 |
| FR-3.2.4 | 输出 `lifecycle_score` ∈ [0,1] 及逐维解释 | P0 |

#### FR-3.3 风险融合（自研）

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-3.3.1 | 公式：`risk = 100 × (w1·p_graph + w2·lifecycle + w3·intermediary)` | P0 |
| FR-3.3.2 | 默认权重：w1=0.45, w2=0.35, w3=0.20（可配置） | P0 |
| FR-3.3.3 | `intermediary` = min(1, count(intermediaries) / 3) | P0 |
| FR-3.3.4 | 风险等级：LOW(0–39) / MEDIUM(40–69) / HIGH(70–100) | P0 |

---

### 6.4 Layer 4 — API 与演示

#### FR-4.1 REST API（基于 ethXpose FastAPI）

| 端点 | 方法 | 描述 | 优先级 |
|------|------|------|--------|
| `/api/v1/health` | GET | 健康检查 | P0 |
| `/api/v1/transfer` | POST | 接收单笔转账事件（Java 调用） | P0 |
| `/api/v1/analyze` | POST | 分析指定合约，返回完整报告 | P0 |
| `/api/v1/graph/{address}` | GET | 返回子图 JSON（可视化） | P1 |
| `/api/v1/history` | GET | 历史分析记录 | P2 |

#### FR-4.2 CLI

```bash
ponzi-shield analyze 0xCONTRACT [--format json|table]
ponzi-shield demo                    # 运行内置演示
ponzi-shield train                   # 重训练模型
```

#### FR-4.3 演示脚本

| 需求 ID | 描述 | 优先级 |
|---------|------|--------|
| FR-4.3.1 | `scripts/demo.sh` 一键启动 Java 节点 + Python API | P0 |
| FR-4.3.2 | 自动部署 PonziContract，模拟 10 笔投资 | P0 |
| FR-4.3.3 | 终端输出风险报告高亮（HIGH 红色） | P1 |

---

## 7. 核心算法规格

### 7.1 中介节点判定（优化 1）

**输入**：以合约 `C` 为中心的 1 阶子图 `G = (V, E)`

**对每个节点 `v ∈ V, v ≠ C` 计算**：

| 指标 | 公式/方法 |
|------|-----------|
| `BC(v)` | NetworkX `betweenness_centrality(G)[v]` |
| `in_d(v)` | `G.in_degree(v)` |
| `out_d(v)` | `G.out_degree(v)` |
| `ratio(v)` | `in_d / max(out_d, 1)` |
| `hold(v)` | 该节点收到资金到转出的平均区块间隔 |

**判定为 intermediary 当满足 ≥2 条**：

```
BC(v) > 0.05
in_d(v) >= 3 AND out_d(v) >= 2
hold(v) <= 10 blocks
ratio(v) > 2.0 AND out_d(v) >= 2
```

**角色标签**：

| 条件 | 角色 |
|------|------|
| 高中介 + 连接 C 与多个 EOA | `RELAY` |
| 高入度低出度 | `ACCUMULATOR` |
| 低入度高出度 | `DISTRIBUTOR` |

---

### 7.2 生命周期五维评分（优化 2）

**来源**：PonziLicle `PonziDetector.py` 五维定义，实现为 **纯规则引擎**（无 LLM）。

| 维度 | 检测规则（链上行为） | 信号 |
|------|---------------------|------|
| **fund_flow** | 新投资者 → 合约 → 旧投资者 的 transfer 链存在 | 新→旧转账模式 |
| **profit_logic** | 分红金额与新 invest 金额正相关 | 后续用户依赖 |
| **referral_mechanism** | 存在 referrer mapping 或 invest 触发多级转账 | 推荐层级 |
| **withdrawal_control** | withdraw 存在 block 间隔限制 | 锁仓/冷却 |
| **camouflage** | 函数名含 stake/mine/game 但实际为 transfer | 命名伪装 |

**阶段划分**（`age = currentBlock - deployBlock`）：

| 阶段 | 区块范围 | 加权维度 |
|------|----------|----------|
| `FUNDRAISING` | 0 – 100 | referral×0.4, in_degree_growth×0.3, fund_flow×0.3 |
| `PAYOUT` | 101 – 500 | fund_flow×0.5, profit_logic×0.3, referral×0.2 |
| `STAGNATION` | 501 – 1000 | withdrawal_control×0.4, outflow_rate×0.3, fund_flow×0.3 |
| `COLLAPSE` | 1001+ | balance_drop×0.5, intermediary_disappear×0.3, fund_flow×0.2 |

**lifecycle_score** = 当前阶段对应维度的加权平均（各维度 0/1 布尔或归一化分值）。

---

### 7.3 风险融合公式

```
intermediary_factor = min(1.0, len(intermediaries) / 3)

risk_raw = w1 * p_graph + w2 * lifecycle_score + w3 * intermediary_factor

risk_score = round(risk_raw * 100, 1)

level = HIGH   if risk_score >= 70
        MEDIUM if risk_score >= 40
        LOW    otherwise
```

**默认权重**：w1=0.45, w2=0.35, w3=0.20

---

## 8. API 规格

### 8.1 POST `/api/v1/transfer`

**用途**：Java FundFlowEmitter 推送单笔转账。

**Request**：
```json
{
  "from": "0xabc...",
  "to": "0xdef...",
  "value": "1000000000000000000",
  "block_number": 42,
  "tx_hash": "0x123...",
  "timestamp": 1719300000
}
```

**Response**：`202 Accepted`

---

### 8.2 POST `/api/v1/analyze`

**Request**：
```json
{
  "contract_address": "0xCONTRACT...",
  "current_block": 150
}
```

**Response**：
```json
{
  "contract_address": "0xCONTRACT...",
  "risk_score": 82.5,
  "risk_level": "HIGH",
  "lifecycle": {
    "stage": "PAYOUT",
    "age_blocks": 250,
    "score": 0.78,
    "dimensions": {
      "fund_flow": {"detected": true, "evidence": "3 new→old transfer chains"},
      "profit_logic": {"detected": true, "evidence": "payout correlates with new invest"},
      "referral_mechanism": {"detected": true, "evidence": "2-level referral transfers"},
      "withdrawal_control": {"detected": false, "evidence": ""},
      "camouflage": {"detected": true, "evidence": "invest() named like stake()"}
    }
  },
  "graph_analysis": {
    "p_graph": 0.91,
    "node_count": 15,
    "edge_count": 28
  },
  "intermediaries": [
    {
      "address": "0xINT...",
      "role": "RELAY",
      "betweenness": 0.32,
      "in_degree": 8,
      "out_degree": 5,
      "avg_holding_blocks": 3
    }
  ],
  "weights": {"w1": 0.45, "w2": 0.35, "w3": 0.20},
  "analyzed_at": "2026-06-25T10:00:00Z"
}
```

---

## 9. 非功能需求

| ID | 类别 | 要求 |
|----|------|------|
| NFR-1 | 性能 | 单次 analyze（≤100 节点子图）< 2s |
| NFR-2 | 性能 | transfer 写入延迟 < 50ms（异步） |
| NFR-3 | 可用性 | demo.sh 一键启动，无需手动改配置 |
| NFR-4 | 兼容性 | Python 3.9+；Java 21+ |
| NFR-5 | 可维护性 | 模块解耦，各检测器可独立单测 |
| NFR-6 | 安全 | 不硬编码 API Key；不暴露私钥 |
| NFR-7 | 可观测性 | 结构化日志（contract, risk, latency） |
| NFR-8 | 文档 | README 含安装、演示、架构图 |

---

## 10. 依赖的开源项目

### 10.1 直接 fork / 集成

| 项目 | URL | 用途 |
|------|-----|------|
| eth-whitepaper-java | 本地已有 | Layer 1 区块链基座 |
| ethXpose | https://github.com/adrian-io/ethXpose | 建图、分类、FastAPI、`/api/v1/transfer` 下游 |
| alexroan/EthereumPonzi | https://github.com/alexroan/EthereumPonzi | **PonziContract 逻辑来源**（Tree.sol + GradualPonzi.sol） |
| PonziLicle | https://github.com/chenpp1881/PonziLicle | **五维特征规格**（PonziDetector.py，不用 LLM） |
| Web3AnalyticsPython | https://github.com/DefiMan1729/Web3AnalyticsPython | Layer 2 中心性算法参考 |
| Ponzi-Warning | https://github.com/asd-git/Ponzi-Warning | Layer 2 子图抽取 + GNN（可选） |

### 10.2 Layer 1 自研模块（无现成 GitHub 可 fork）

| 模块 | 实现方式 | 说明 |
|------|----------|------|
| `PonziContract.java` | 移植 Solidity + 仿 TokenContract | 无 Java 版现成仓库 |
| `FundFlowEmitter.java` | 自研 Observer (~30–50 行) | 挂 `WorldState.transfer()` |
| `AnalysisClient.java` | 自研 HTTP POST | 对接 ethXpose `/api/v1/transfer` |

### 10.3 Layer 1 明确不采用的项目

| 项目 | URL | 不采用原因 |
|------|-----|-----------|
| Web3j | https://docs.web3j.io/ | 外部 RPC 订阅，非内嵌验证流程 |
| eventeum | https://github.com/eventeum/eventeum | 独立微服务，过重 |
| Magician-Scanning | https://github.com/Magician-Blockchain/Magician-Scanning | 公链扫描器，不参与 BlockProcessor |
| PonziGuard geth_detect | https://github.com/PonziDetection/PonziGuard | 改 C++ Geth，与 Java 基座不兼容 |

### 10.4 数据集

| 来源 | 说明 |
|------|------|
| XBlock（ethXpose 内置） | 200 Ponzi + 1660 Phishing + 1700 Normal |
| blockchain-unica/ethereum-ponzi | **PonziContract 行为可选验证**；含 solidity/ 样本与 ponzi-addresses.csv |
| Messi-Q/Smart-Contract-Dataset | 补充 opcode 特征对比实验 |
| 自建 demo 链数据 | PonziContract 模拟 stake/withdraw 交易 |

### 10.5 仅引用（Related Work）

PonziGuard · SADPonzi · X-SPIDE · PonziSentinel · PonziSense · Onchain_Ponzi_Detection（架构参考）· FriendlyUser/solidity-smart-contracts（GradualPonzi 备选）

---

## 11. 课程 Mining Algorithm 问答（报告必含）

| 问题 | 答案 |
|------|------|
| 区块包含完整 trie 吗？ | **否**。区块头只存 root hash；完整 trie 由全节点本地维护 |
| 矿工能跳过验证吗？ | **能**，但产出无效区块；PonziShield 假设节点诚实执行验证 |
| 多人不验证会怎样？ | 链分叉，诚实节点拒绝无效链 |
| 冲突交易怎么处理？ | 同 nonce 仅一笔成功；检测只记录成功执行的 transfer |
| 收到新区块还挖旧块？ | 应切换到 canonical chain；检测以最新状态为准 |

**结合点**：PonziShield 在 `validateAndApply()` 执行路径上挂载 FundFlowEmitter，是验证流程的自然延伸，非额外共识改动。

---

## 12. 里程碑与排期

| 阶段 | 时间 | 交付物 | 验收标准 |
|------|------|--------|----------|
| **M1 基座跑通** | W1 | ethXpose clone + train；Java mvn test | classify 返回概率；Java 测试全绿 |
| **M2 数据联通** | W2 | FundFlowEmitter + FastAPI /transfer | Java 转账后 Python 收到事件 |
| **M3 双优化** | W3 | intermediary_detector + lifecycle_scorer + PonziContract（移植 EthereumPonzi） | analyze 返回完整报告 |
| **M4 演示评估** | W4 | demo.sh + 实验 + 报告 | 15min demo；F1≥0.85 |

---

## 13. 验收标准（Definition of Done）

### 13.1 功能验收

- [ ] Java 节点执行 PonziContract 10 笔 invest 后，API 返回 `risk_level: HIGH`
- [ ] 同场景下 TokenContract 返回 `risk_level: LOW` 或 `MEDIUM`
- [ ] 报告含 ≥1 个 intermediary 节点及 betweenness 数值
- [ ] 报告含 lifecycle stage 及五维 breakdown
- [ ] `scripts/demo.sh` 零配置一键运行

### 13.2 性能验收

- [ ] analyze 响应 < 2s（100 节点子图）
- [ ] 连续 100 笔 transfer 无丢失

### 13.3 实验验收

- [ ] XBlock 测试集 F1 ≥ 0.85
- [ ] 消融实验：分别关闭 intermediary / lifecycle 模块，记录 F1 变化

### 13.4 文档验收

- [ ] README：安装、架构、演示步骤
- [ ] 期末报告含架构图、Related Work、Mining Q&A、实验表格

---

## 14. 实验设计

### 14.1 对比基线

| 模型 | 说明 |
|------|------|
| ethXpose GB only | 仅图嵌入分类（w2=0, w3=0） |
| + Lifecycle | 加入五维评分 |
| + Intermediary | 加入中介节点因子 |
| **PonziShield Full** | 三通道融合 |

### 14.2 评估指标

- Accuracy / Precision / Recall / F1
- 消融：各模块对 F1 的贡献
- 案例研究：PonziContract 各阶段 risk_score 曲线

### 14.3 测试集

1. **XBlock Held-out**：ethXpose 30% test split
2. **Demo Chain**：自建 PonziContract 模拟交易
3. **（可选）PUP_node_dataset**：Ponzi-Warning 标注合约

---

## 15. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| ethXpose 数据集缺失 | 无法训练 | 从 XBlock 平台下载；先用预训练模型 |
| Java-Python 通信失败 | 检测中断 | 本地 JSON 队列 fallback |
| 中介节点规则误报 | 准确率低 | 调阈值；消融实验选最优 |
| Ponzi-Warning 需 Neo4j | 集成复杂 | v1 不依赖 Neo4j，仅借鉴算法 |
| 时间不足 | 无法完成 GNN | GNN 列为 P2，不影响 MVP |

---

## 16. 版本规划

| 版本 | 范围 | 时间 |
|------|------|------|
| **v1.0 MVP** | 检测 + 告警 API + demo | 期末 |
| v1.1 | ethXpose-frontend 可视化 | +1 周 |
| v2.0 | 链上 RiskGateway 拦截投资 | 未来 |
| v2.1 | 接入真实 testnet RPC | 未来 |

---

## 17. 附录

### A. 创新点总结（vs 已有工作）

| 已有工作 | PonziShield v1.0 |
|----------|----------------|
| 离线批量扫描 | **嵌入挖矿验证流程**，实时捕获 transfer |
| 纯 opcode / 静态分析 | **链上交易图** + 中介节点拓扑 |
| 单时间点检测 | **生命周期四阶段**动态权重 |
| 黑盒概率 | **五维 + 中介节点**可解释报告 |

### B. 演示脚本流程（15 分钟）

```
1. ./scripts/start_services.sh          # 启动 Python API
2. mvn exec:java -Dexec.mainClass=...DemoMain  # 启动 Java 链
3. 观察终端：10 笔 invest 后 risk 从 LOW → HIGH
4. curl POST /api/v1/analyze             # 展示 JSON 报告
5. 对比 TokenContract 结果
6. Q&A：Mining Algorithm 五个问题
```

### C. 参考文档

- [ChainGuard_相关GitHub项目汇总.md](./ChainGuard_相关GitHub项目汇总.md)
- ethXpose 论文：Graph Embedding based Fraud Detection on the Ethereum Blockchain
- PonziLicle：From Investment to Collapse: Lifecycle-Aware Explanation and Detection
- alexroan/EthereumPonzi Medium 系列：Chain / Tree / Waterfall Ponzi 分析
- 课程 Complete Mining Algorithm 讲义

### D. Layer 1 实现方案速查（v1.1 定稿）

```
PonziContract.java
  逻辑来源  → alexroan/EthereumPonzi (Tree.sol + GradualPonzi.sol)
  代码结构  → 本地 TokenContract.java（AbstractNativeContract）
  五维规格  → PonziLicle/PonziDetector.py（对照检查，不用 LLM）
  标注数据  → blockchain-unica/ethereum-ponzi（可选验证）
  路径      → sample/PonziContract.java

FundFlowEmitter.java
  实现方式  → 自研 Observer（~30–50 行）
  挂载点    → WorldState.transfer()（首选）
  下游      → ethXpose FastAPI POST /api/v1/transfer
  不 fork   → Web3j / eventeum / Magician-Scanning / PonziGuard geth_detect
  路径      → ponzi/FundFlowEmitter.java + ponzi/AnalysisClient.java
```

---

*文档结束 · PonziShield PRD v1.1*
