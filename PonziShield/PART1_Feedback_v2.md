【第一部分（基座）更新】

我完成了基座模块（Layer 1）的代码优化，已提交到本地仓库（commit: feat(Layer1): Innovations 1-3），大家重新 pull 即可获取最新代码。

主要做了以下几件事：

1. 增加了事件类型标注（EventType 枚举）：新建 EventType.java，定义了 STAKE / WITHDRAW / REFERRAL_REWARD / DIVIDEND / TRANSFER 五种类型。修改了 FundFlowEvent.java 的 toJson()，在原有六个字段基础上追加 "event_type" 字段（向后兼容，老调用方直接忽略新字段）。WorldState.java 的 transfer() 方法新增带类型的重载，保留原有无类型重载。Python 侧的 TransferEvent 新增可选字段 event_type，graph 的每条边也存上了这个属性。/api/v1/history 返回的每条记录现在带 event_type，Tx Stream 面板可以按类型标注每笔转账。

2. 实现了动态利率机制（EARLY / MATURE 双阶段）：PonziContract.java 不再使用写死的利率常数。部署时记录两套利率参数（早期高利率和成熟期降息后的利率），以及触发切换的投资人数阈值（默认 5 人）。每次新投资人加入后自动检查是否达到阈值，达到后合约进入 MATURE 阶段，推荐奖励比例降至原来的 50%，分红池比例降至 75%。新增三个只读消息处理器 phase / referralBpsNow / poolBpsNow，供后续模块查询当前阶段状态。这个机制模拟了真实庞氏骗局"早期高收益诱惑，后期悄然降息"的行为特征，Lifecycle 曲线在阶段切换块前后能观察到明显的收益率收窄信号。

3. 新增了两种更丰富的 Demo 场景（PonziDemoMain.java）：
   - network 场景：构建一个 1 个部署者 + 3 个 Hub + 6 个叶子节点的辐射状推荐网络，Hub 之间形成短环，每个 Hub 带两个下线。共 10 名投资人参与，阶段切换会在场景中间触发，两个 Hub 在锁定期结束后提款，Graph 面板会出现有明显聚类结构的中介节点。
   - whale 场景：一个鲸鱼钱包先大额质押 5 ETH，三个小额投资人跟入，鲸鱼在锁定期结束后立刻提走全部余额（WITHDRAW 类型事件），再有三个小额投资人跟入（模拟"后来者踩空"）。Tx Stream 面板可以看到一笔明显偏大的 WITHDRAW 事件夹在两批 STAKE 之间；Lifecycle 的 post-fundraising withdrawal 信号会被触发。
   - 原有默认 Demo（10 次 stake + 3 次 withdraw 的线性链）保持不变，新场景通过 -Ddemo.type=network 或 -Ddemo.type=whale 启动。

4. 兼容性：所有改动完全向后兼容。POST /api/v1/transfer 不带 event_type 字段照常工作（默认 TRANSFER）。第 2、3、4、5 部分的代码无需任何修改。

修改的文件清单：
- eth-whitepaper-java-main/…/ponzi/EventType.java（新增）
- eth-whitepaper-java-main/…/ponzi/FundFlowEvent.java（修改）
- eth-whitepaper-java-main/…/core/WorldState.java（修改）
- eth-whitepaper-java-main/…/sample/PonziContract.java（修改）
- eth-whitepaper-java-main/…/ponzi/PonziDemoMain.java（修改）
- ponzi-detector/api/main.py（修改）
- ponzi-detector/api/tools/transfer_graph.py（修改）

Report 和 PPT 整理好后会一起提交。
