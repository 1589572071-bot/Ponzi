# ChainGuard 相关 GitHub 项目汇总

> 整理时间：2026-06-24
> 用途：期末项目 ChainGuard 参考资料 / 论文相关工作引用

---

## 🏆 第一类：直接相关 —— 庞氏骗局检测（ML + Opcode）

这几个是**项目的核心参考**，方案文档里直接引用了其中部分项目。

| 项目 | 说明 | 链接 |
|---|---|---|
| **xuyl0104/blockchain_ponzi_detection** | 🎯 **最直接参考**，课程级别的 Ponzi 检测 Pipeline，Python + XGBoost，方案文档明确引用 | https://github.com/xuyl0104/blockchain_ponzi_detection |
| **LucaPennella/x-spide-smart-ponzi-detection** | 🎯 **X-SPIDE 论文复现**，用 SHAP + PDP + opcode 特征，Jupyter Notebook，2025 年 5 月更新 | https://github.com/LucaPennella/x-spide-smart-ponzi-detection |
| **LucaPennella/SmartPonzi_MachineLearning** | 同作者早期版本，专注 ML 分类，对比多个模型 | https://github.com/LucaPennella/SmartPonzi_MachineLearning |
| **SnideSE/FYPPonziDetectionUsingML** | 毕业设计级别，用 ML 检测以太坊 Ponzi 合约，Python 实现 | https://github.com/SnideSE/FYPPonziDetectionUsingML |
| **junyu301/PonziSentinel** | 用 XGBoost + Word2Vec + TF-IDF 做合约检测，思路与 ChainGuard 高度一致 | https://github.com/junyu301/PonziSentinel |
| **chenpp1881/PonziLicle** | 2026 年最新论文配套代码，"生命周期感知"的 Ponzi 检测，有可解释性分析 | https://github.com/chenpp1881/PonziLicle |
| **BryceTsui/SmartPonziDetector** | 早期论文"行为树相似度"检测 Ponzi，附完整数据集 | https://github.com/BryceTsui/SmartPonziDetector |
| **yang42012617-ctrl/PonziSense** | 即将发表于 ICSE 2026 的最新论文，可解释 Ponzi 检测 | https://github.com/yang42012617-ctrl/PonziSense |
| **kawtikat/Ponzi-Scheme-detection** | JavaScript 实现的 Ponzi 检测，2026 年 3 月更新 | https://github.com/kawtikat/Ponzi-Scheme-detection |
| **Mayank004-ux/Onchain_Ponzi_Detection** | 基于链上交易流分析的 Ponzi 检测框架，2026 年 4 月更新 | https://github.com/Mayank004-ux/Onchain_Ponzi_Detection |
| **nimeshmali/Ethereum-Fraud-Detection** | ML 驱动的以太坊钱包欺诈检测，覆盖 Ponzi / 钓鱼等多类骗局 | https://github.com/nimeshmali/Ethereum-Fraud-Detection |

---

## 📦 第二类：数据集来源

训练数据的主要来源，可直接用于模型训练。

| 项目 | Stars | 说明 | 链接 |
|---|---|---|---|
| **Messi-Q/Smart-Contract-Dataset** | ⭐ 200 | **最权威的智能合约安全数据集**，持续更新，包含各类漏洞和骗局合约标注数据 | https://github.com/Messi-Q/Smart-Contract-Dataset |

> **数据集说明：**
> - `bytecode_opcode_8k.csv`：约 8000 个合约的 opcode 特征矩阵（来自 X-SPIDE）
> - `DS_deployed_bytecode.csv`：补充账户行为数据（交易数、发送者数等）
> - 标注方式：链上行为分析 + 人工审核 + 已知骗局黑名单

---

## 🔐 第三类：智能合约安全检测（技术参考 / 论文相关工作引用）

适合在 Thesis 的"Related Work"章节引用。

| 项目 | Stars | 说明 | 链接 |
|---|---|---|---|
| **Messi-Q/GNNSCVulDetector** | ⭐ 152 | 用图神经网络检测智能合约漏洞，IJCAI-20 论文 | https://github.com/Messi-Q/GNNSCVulDetector |
| **Messi-Q/GraphDeeSmartContract** | ⭐ 156 | GNN 检测合约漏洞（DR-GCN），经典工作 | https://github.com/Messi-Q/GraphDeeSmartContract |
| **Messi-Q/GPSCVulDetector** | ⭐ 120 | GNN + 专家知识融合检测合约漏洞，TKDE 论文 | https://github.com/Messi-Q/GPSCVulDetector |
| **Messi-Q/AMEVulDetector** | ⭐ 100 | 神经网络 + 专家模式融合检测漏洞，IJCAI-21 | https://github.com/Messi-Q/AMEVulDetector |
| **Messi-Q/ReChecker** | ⭐ 57 | 专门检测**重入攻击（Re-entrancy）**漏洞，Sequential Model 方法 | https://github.com/Messi-Q/ReChecker |
| **Messi-Q/Cross-Modality-Bug-Detection** | ⭐ 34 | 跨模态互学习检测合约漏洞 | https://github.com/Messi-Q/Cross-Modality-Bug-Detection |

---

## 🏪 第四类：SafeSwap（托管合约）相关参考

若切换为 SafeSwap 方向，以下项目可作参考。

| 项目 | Stars | 说明 | 链接 |
|---|---|---|---|
| **ryo-wijaya/sprout** | ⭐ 3 | 含 DAO 争议解决的链上自由职业市场，有完整 Escrow 合约 | https://github.com/ryo-wijaya/sprout |
| **simon19f3/Decentralized-Escrow-Marketplace** | ⭐ 0 | Hardhat + React + MetaMask 完整实现，结构与 SafeSwap 高度吻合 | https://github.com/simon19f3/Decentralized-Escrow-Marketplace |
| **Talha-Shamas-dev/Decentralized-Freelance-Escrow** | ⭐ 1 | 生产级 Solidity Escrow 合约，含超时机制、仲裁、惩罚逻辑 | https://github.com/Talha-Shamas-dev/Decentralized-Freelance-Escrow-Smart-Contract-with-Dispute-Resolution-Penalties-Auto-Timeout |
| **ChapuKosi/Decentralized-Escrow-Marketplace** | ⭐ 0 | 多 Token 支持的 P2P Escrow，含仲裁者角色和完整测试 | https://github.com/ChapuKosi/Decentralized-Escrow-Marketplace |

---

## 💡 核心结论

### 已有工作 vs. ChainGuard 的创新点

| 已有工作 | ChainGuard 的不同之处 |
|---|---|
| 只做检测，输出风险报告 | **检测 + 链上强制执行**，高风险合约在区块链代码层面直接拒绝投资 |
| 离线批量扫描数据集 | **实时扫描**任意合约地址，签发 Attestation 证明书 |
| 无链上业务闭环 | **完整业务闭环**：扫描 → 上架 → 合规投资 → 社区举报 → 自动暂停 |
| 纯学术实验 | 可演示的 **DApp 前端 + 链上合约**，有真实链上交易记录 |

> **一句话**：现有项目只是"报警器"，ChainGuard 是"带锁的门"。

---

## 📚 推荐学习顺序（给完全小白）

1. **先跑通数据**：克隆 `xuyl0104/blockchain_ponzi_detection`，在本地跑通 XGBoost 训练流程
2. **理解特征**：读 `LucaPennella/x-spide-smart-ponzi-detection` 的 Notebook，看 SHAP 图怎么解释
3. **看数据集**：浏览 `Messi-Q/Smart-Contract-Dataset`，了解标注数据长什么样
4. **写合约**：参考 SafeSwap 的 Solidity 骨架，在 Remix IDE 里先跑通 RiskGateway
5. **读论文**：搜索 "SADPonzi"、"X-SPIDE" 的原始论文，用于 Thesis Related Work 章节引用
