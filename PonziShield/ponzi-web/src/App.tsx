import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Copy,
  Download,
  FileText,
  Gauge,
  GitBranch,
  Keyboard,
  Play,
  Radio,
  Search,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";
import {
  Line,
  LineChart,
  PolarAngleAxis,
  PolarGrid,
  Radar,
  RadarChart as ReRadarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

const API_BASE = import.meta.env.VITE_PONZI_API ?? "http://localhost:8000";

type RiskLevel = "LOW" | "MEDIUM" | "HIGH";
type Tab = "overview" | "graph" | "lifecycle" | "report";

type Dimension = {
  detected: boolean;
  score?: number;
  evidence: string;
};

type AnalysisReport = {
  contract_address: string;
  risk_score: number;
  risk_level: RiskLevel;
  lifecycle: {
    stage: "FUNDRAISING" | "PAYOUT" | "STAGNATION" | "COLLAPSE";
    age_blocks: number;
    score: number;
    dimensions: Record<string, Dimension>;
  };
  graph_analysis: {
    p_graph: number;
    node_count: number;
    edge_count: number;
  };
  intermediaries: IntermediaryNode[];
  weights: {
    w1: number;
    w2: number;
    w3: number;
  };
  analyzed_at: string;
};

type IntermediaryNode = {
  address: string;
  role: "RELAY" | "ACCUMULATOR" | "DISTRIBUTOR";
  betweenness: number;
  in_degree: number;
  out_degree: number;
  avg_holding_blocks: number;
};

type TxEvent = {
  from: string;
  to: string;
  value: string;
  block_number: number;
  tx_hash: string;
  timestamp: number;
};

type GraphNode = {
  id: string;
  label: string;
  kind: "contract" | "eoa" | "intermediary";
  x: number;
  y: number;
  degree: number;
};

type GraphEdge = {
  from: string;
  to: string;
  value: number;
  block_number: number;
};

type GraphResponse = {
  nodes: GraphNode[];
  edges: GraphEdge[];
};

const CONTRACTS = [
  { label: "PonziContract", address: "0xponzi0000000000000000000000000000000000a", level: "HIGH" as const },
  { label: "TokenContract", address: "0xtoken0000000000000000000000000000000000b", level: "LOW" as const },
];

export default function App() {
  const [selectedAddress, setSelectedAddress] = useState(CONTRACTS[0].address);
  const [activeTab, setActiveTab] = useState<Tab>("overview");
  const [addressInput, setAddressInput] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [focusedNode, setFocusedNode] = useState<string | null>(null);
  const [showShortcuts, setShowShortcuts] = useState(false);

  const health = useQuery({
    queryKey: ["health"],
    queryFn: () => getJson<{ status: string }>("/api/v1/health"),
    refetchInterval: 5_000,
  });

  const history = useQuery({
    queryKey: ["history"],
    queryFn: async () => getJson<TxEvent[]>("/api/v1/history").catch(() => mockHistory),
    refetchInterval: 1_000,
  });

  const report = useQuery({
    queryKey: ["analyze", selectedAddress],
    queryFn: async () => analyzeContract(selectedAddress),
  });

  const graph = useQuery({
    queryKey: ["graph", selectedAddress],
    queryFn: async () => getJson<GraphResponse>(`/api/v1/graph/${selectedAddress}`).catch(() => mockGraph(selectedAddress)),
  });

  const demoMutation = useMutation({
    mutationFn: () => postJson("/api/v1/demo", {}),
    onSuccess: () => setToast("Demo 已触发：10×stake + 3×withdraw"),
    onError: () => setToast("后端 Demo 端点暂未实现，当前使用演示数据"),
  });

  useEffect(() => {
    if (report.data?.risk_score && report.data.risk_score >= 70) {
      setToast(`${shortAddress(report.data.contract_address)} 风险 ${report.data.risk_score.toFixed(1)} HIGH`);
    }
  }, [report.data?.contract_address, report.data?.risk_score]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLInputElement) {
        return;
      }
      if (event.key === "?") {
        setShowShortcuts((value) => !value);
      }
      if (event.key.toLowerCase() === "d") {
        demoMutation.mutate();
      }
      if (event.key.toLowerCase() === "g") {
        setActiveTab("graph");
      }
      if (event.key.toLowerCase() === "r") {
        setActiveTab("report");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [demoMutation]);

  const selectedReport = report.data ?? mockReport(selectedAddress);
  const selectedGraph = graph.data ?? mockGraph(selectedAddress);
  const txs = history.data ?? mockHistory;
  const apiOnline = health.data?.status === "ok";
  const currentBlock = Math.max(...txs.map((tx) => tx.block_number), selectedReport.lifecycle.age_blocks);

  function analyzeCustomAddress() {
    const normalized = addressInput.trim();
    if (!/^0x[a-fA-F0-9]{3,64}$/.test(normalized)) {
      setToast("请输入 0x 开头的合约地址");
      return;
    }
    setSelectedAddress(normalized);
    setAddressInput("");
  }

  return (
    <div className="app-shell">
      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
      <Topbar
        apiOnline={apiOnline}
        currentBlock={currentBlock}
        runningDemo={demoMutation.isPending}
        onRunDemo={() => demoMutation.mutate()}
        onShortcuts={() => setShowShortcuts(true)}
      />

      <div className="layout">
        <aside className="sidebar">
          <section className="panel">
            <div className="section-title">合约列表</div>
            <div className="contract-list">
              {CONTRACTS.map((contract) => (
                <button
                  className={`contract-card ${selectedAddress === contract.address ? "active" : ""}`}
                  key={contract.address}
                  onClick={() => setSelectedAddress(contract.address)}
                >
                  <span>
                    <strong>{contract.label}</strong>
                    <small>{shortAddress(contract.address)}</small>
                  </span>
                  <span className={`risk-dot ${contract.level.toLowerCase()}`}>{contract.level}</span>
                </button>
              ))}
            </div>
            <div className="address-input">
              <input
                placeholder="+ 分析地址 0x..."
                value={addressInput}
                onChange={(event) => setAddressInput(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && analyzeCustomAddress()}
              />
              <button onClick={analyzeCustomAddress} aria-label="Analyze address">
                <Search size={16} />
              </button>
            </div>
          </section>

          <TxStream txs={txs} selectedAddress={selectedAddress} />
        </aside>

        <main className="workspace">
          <Tabs activeTab={activeTab} onChange={setActiveTab} />
          {report.isLoading ? (
            <Skeleton />
          ) : (
            <>
              {activeTab === "overview" && (
                <Overview report={selectedReport} onFocusNode={(address) => {
                  setFocusedNode(address);
                  setActiveTab("graph");
                }} />
              )}
              {activeTab === "graph" && (
                <GraphTab report={selectedReport} graph={selectedGraph} focusedNode={focusedNode} />
              )}
              {activeTab === "lifecycle" && <LifecycleTab report={selectedReport} txs={txs} />}
              {activeTab === "report" && <ReportTab report={selectedReport} />}
            </>
          )}
        </main>
      </div>

      <BottomDrawer report={selectedReport} selectedAddress={selectedAddress} />
      {showShortcuts && <ShortcutsModal onClose={() => setShowShortcuts(false)} />}
    </div>
  );
}

function Topbar({
  apiOnline,
  currentBlock,
  runningDemo,
  onRunDemo,
  onShortcuts,
}: {
  apiOnline: boolean;
  currentBlock: number;
  runningDemo: boolean;
  onRunDemo: () => void;
  onShortcuts: () => void;
}) {
  return (
    <header className="topbar">
      <div className="brand">
        <ShieldAlert size={26} />
        <div>
          <strong>PonziShield</strong>
          <span>Demo Web</span>
        </div>
      </div>
      <div className="status-strip">
        <StatusPill online={apiOnline} label="Node 状态" />
        <span className="metric">区块高度 #{currentBlock}</span>
        <StatusPill online={apiOnline} label="API 健康" />
      </div>
      <div className="topbar-actions">
        <button className="ghost-button" onClick={onShortcuts}>
          <Keyboard size={16} /> 快捷键
        </button>
        <button className="primary-button" onClick={onRunDemo} disabled={!apiOnline || runningDemo}>
          <Play size={16} /> {runningDemo ? "运行中" : "Demo 一键运行"}
        </button>
      </div>
    </header>
  );
}

function StatusPill({ online, label }: { online: boolean; label: string }) {
  return (
    <span className={`status-pill ${online ? "online" : "offline"}`}>
      <span className="pulse" /> {label}
    </span>
  );
}

function Tabs({ activeTab, onChange }: { activeTab: Tab; onChange: (tab: Tab) => void }) {
  const tabs: Array<{ id: Tab; label: string; icon: ReactNode }> = [
    { id: "overview", label: "Overview", icon: <Gauge size={16} /> },
    { id: "graph", label: "Graph", icon: <GitBranch size={16} /> },
    { id: "lifecycle", label: "Lifecycle", icon: <Activity size={16} /> },
    { id: "report", label: "Report", icon: <FileText size={16} /> },
  ];
  return (
    <nav className="tabs">
      {tabs.map((tab) => (
        <button className={activeTab === tab.id ? "active" : ""} key={tab.id} onClick={() => onChange(tab.id)}>
          {tab.icon} {tab.label}
        </button>
      ))}
    </nav>
  );
}

function Overview({ report, onFocusNode }: { report: AnalysisReport; onFocusNode: (address: string) => void }) {
  return (
    <div className="overview-grid">
      <RiskGaugeCard report={report} />
      <RadarCard report={report} />
      <IntermediaryTable report={report} onFocusNode={onFocusNode} />
    </div>
  );
}

function RiskGaugeCard({ report }: { report: AnalysisReport }) {
  return (
    <section className="panel gauge-panel">
      <div className="section-title">Risk Gauge</div>
      <RiskGauge score={report.risk_score} level={report.risk_level} />
      <div className="weight-bars">
        <WeightBar label="p_graph" value={report.graph_analysis.p_graph} weight={report.weights.w1} />
        <WeightBar label="lifecycle" value={report.lifecycle.score} weight={report.weights.w2} />
        <WeightBar label="intermediary" value={Math.min(1, report.intermediaries.length / 3)} weight={report.weights.w3} />
      </div>
    </section>
  );
}

function RiskGauge({ score, level }: { score: number; level: RiskLevel }) {
  const radius = 74;
  const circumference = Math.PI * radius;
  const progress = Math.max(0, Math.min(100, score)) / 100;
  return (
    <div className={`gauge ${level.toLowerCase()}`}>
      <svg viewBox="0 0 180 110">
        <path className="gauge-track" d="M16 92a74 74 0 0 1 148 0" pathLength={circumference} />
        <path
          className="gauge-value"
          d="M16 92a74 74 0 0 1 148 0"
          pathLength={circumference}
          style={{ strokeDasharray: `${progress * circumference} ${circumference}` }}
        />
      </svg>
      <div className="gauge-number">
        <strong>{score.toFixed(1)}</strong>
        <span>{level}</span>
      </div>
    </div>
  );
}

function WeightBar({ label, value, weight }: { label: string; value: number; weight: number }) {
  return (
    <div className="weight-row">
      <span>{label}</span>
      <div className="weight-track">
        <div style={{ width: `${Math.min(100, value * 100)}%` }} />
      </div>
      <small>{weight.toFixed(2)}</small>
    </div>
  );
}

function RadarCard({ report }: { report: AnalysisReport }) {
  const data = Object.entries(report.lifecycle.dimensions).map(([key, value]) => ({
    dimension: key.replace("_mechanism", ""),
    score: Math.round(((value.score ?? (value.detected ? 1 : 0.18)) * 100)),
    evidence: value.evidence || "未触发",
  }));
  return (
    <section className="panel radar-panel">
      <div className="section-title">五维雷达</div>
      <ResponsiveContainer width="100%" height={270}>
        <ReRadarChart data={data}>
          <PolarGrid stroke="#334155" />
          <PolarAngleAxis dataKey="dimension" tick={{ fill: "#cbd5e1", fontSize: 11 }} />
          <Radar dataKey="score" stroke="#38bdf8" fill="#0ea5e9" fillOpacity={0.35} />
          <Tooltip contentStyle={{ background: "#020617", border: "1px solid #334155" }} />
        </ReRadarChart>
      </ResponsiveContainer>
    </section>
  );
}

function IntermediaryTable({ report, onFocusNode }: { report: AnalysisReport; onFocusNode: (address: string) => void }) {
  const rows = [...report.intermediaries].sort((a, b) => b.betweenness - a.betweenness);
  return (
    <section className="panel table-panel">
      <div className="section-title">中介节点表</div>
      <table>
        <thead>
          <tr>
            <th>addr</th>
            <th>role</th>
            <th>betweenness</th>
            <th>holding</th>
            <th>evidence</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((node) => (
            <tr key={node.address} onClick={() => onFocusNode(node.address)}>
              <td>{shortAddress(node.address)}</td>
              <td><span className="tag danger">{node.role}</span></td>
              <td>{node.betweenness.toFixed(2)}</td>
              <td>{node.avg_holding_blocks} blocks</td>
              <td>{node.in_degree} in / {node.out_degree} out</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function GraphTab({ report, graph, focusedNode }: { report: AnalysisReport; graph: GraphResponse; focusedNode: string | null }) {
  const [hop, setHop] = useState<1 | 2>(1);
  const [block, setBlock] = useState(() => Math.max(...graph.edges.map((edge) => edge.block_number)));
  const visibleEdges = graph.edges.filter((edge) => edge.block_number <= block);
  return (
    <section className="panel graph-panel">
      <div className="panel-header">
        <div>
          <div className="section-title">实时交易图</div>
          <p>合约中心节点金色描边，中介节点红色描边；滑块按 blockNumber 回放。</p>
        </div>
        <div className="segmented">
          <button className={hop === 1 ? "active" : ""} onClick={() => setHop(1)}>1 跳</button>
          <button className={hop === 2 ? "active" : ""} onClick={() => setHop(2)}>2 跳</button>
        </div>
      </div>
      <svg className="tx-graph" viewBox="0 0 820 420">
        {visibleEdges.map((edge, index) => {
          const from = graph.nodes.find((node) => node.id === edge.from);
          const to = graph.nodes.find((node) => node.id === edge.to);
          if (!from || !to) return null;
          return <line key={`${edge.from}-${edge.to}-${index}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y} />;
        })}
        {graph.nodes.map((node) => {
          const isFocused = focusedNode === node.id;
          const isContract = node.id === report.contract_address || node.kind === "contract";
          return (
            <g className={`graph-node ${node.kind} ${isFocused ? "focused" : ""}`} key={node.id}>
              <circle cx={node.x} cy={node.y} r={12 + node.degree * 2} />
              {isContract && <circle className="contract-ring" cx={node.x} cy={node.y} r={22 + node.degree * 2} />}
              <text x={node.x} y={node.y + 42}>{node.label}</text>
            </g>
          );
        })}
      </svg>
      <div className="timeline-control">
        <span>block #{block}</span>
        <input
          type="range"
          min={Math.min(...graph.edges.map((edge) => edge.block_number))}
          max={Math.max(...graph.edges.map((edge) => edge.block_number))}
          value={block}
          onChange={(event) => setBlock(Number(event.target.value))}
        />
      </div>
    </section>
  );
}

function LifecycleTab({ report, txs }: { report: AnalysisReport; txs: TxEvent[] }) {
  const stages = ["FUNDRAISING", "PAYOUT", "STAGNATION", "COLLAPSE"];
  const flowData = txs.slice(-12).map((tx) => ({
    block: tx.block_number,
    inflow: Number(tx.value) / 100,
    outflow: tx.to.toLowerCase() === report.contract_address.toLowerCase() ? 0 : Number(tx.value) / 140,
  }));
  return (
    <div className="lifecycle-grid">
      <section className="panel">
        <div className="section-title">生命周期阶段</div>
        <div className="stage-track">
          {stages.map((stage, index) => (
            <div className={`stage-card ${report.lifecycle.stage === stage ? "active" : ""}`} key={stage}>
              <span>0{index + 1}</span>
              <strong>{stage}</strong>
              <small>{stageCopy(stage)}</small>
            </div>
          ))}
        </div>
      </section>
      <section className="panel">
        <div className="section-title">资金流入/流出时间序列</div>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={flowData}>
            <XAxis dataKey="block" stroke="#94a3b8" />
            <YAxis stroke="#94a3b8" />
            <Tooltip contentStyle={{ background: "#020617", border: "1px solid #334155" }} />
            <Line type="monotone" dataKey="inflow" stroke="#22c55e" strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="outflow" stroke="#ef4444" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </section>
    </div>
  );
}

function ReportTab({ report }: { report: AnalysisReport }) {
  const tokenReport = mockReport(CONTRACTS[1].address);
  return (
    <div className="report-grid">
      <VisualReportDocument report={report} />
      <section className="panel compare-panel">
        <div className="section-title">对比模式</div>
        <p className="panel-note">当前选中合约与正常 Token 对照组的风险差异。</p>
        <div className="compare-cards">
          <CompareCard title="当前合约" report={report} />
          <CompareCard title="TokenContract" report={tokenReport} />
        </div>
      </section>
    </div>
  );
}

function VisualReportDocument({ report }: { report: AnalysisReport }) {
  const dimensions = Object.entries(report.lifecycle.dimensions);
  const intermediaryFactor = Math.min(1, report.intermediaries.length / 3);
  const channels = [
    {
      key: "p_graph",
      label: "图分类通道",
      weight: report.weights.w1,
      value: report.graph_analysis.p_graph,
      detail: `子图 ${report.graph_analysis.node_count} 节点 · ${report.graph_analysis.edge_count} 边 · p_graph ${report.graph_analysis.p_graph.toFixed(3)}`,
    },
    {
      key: "lifecycle",
      label: "生命周期通道",
      weight: report.weights.w2,
      value: report.lifecycle.score,
      detail: `${stageLabel(report.lifecycle.stage)} · 存续 ${report.lifecycle.age_blocks} blocks`,
    },
    {
      key: "intermediary",
      label: "中介节点通道",
      weight: report.weights.w3,
      value: intermediaryFactor,
      detail: `${report.intermediaries.length} 个中介节点 · 因子 ${intermediaryFactor.toFixed(2)}`,
    },
  ];

  return (
    <section className="panel report-document">
      <div className="report-doc-header">
        <div>
          <div className="report-doc-kicker">PonziShield Analysis Report</div>
          <h2 className="report-doc-title">合约风险分析报告</h2>
          <div className="report-doc-meta">
            <span>{shortAddress(report.contract_address)}</span>
            <span>{report.analyzed_at.replace("T", " ").replace("Z", " UTC")}</span>
            <span>区块高度 #{report.lifecycle.age_blocks}</span>
          </div>
        </div>
        <div className="report-doc-actions">
          <button onClick={() => navigator.clipboard.writeText(JSON.stringify(report, null, 2))}>
            <Copy size={16} /> 复制 JSON
          </button>
          <button onClick={() => downloadJson(report)}>
            <Download size={16} /> 下载 JSON
          </button>
        </div>
      </div>

      <div className={`report-verdict ${report.risk_level.toLowerCase()}`}>
        <div className="report-verdict-score">
          <strong>{report.risk_score.toFixed(1)}</strong>
          <span>{report.risk_level}</span>
        </div>
        <div className="report-verdict-copy">
          <strong>{verdictTitle(report)}</strong>
          <p>{verdictSummary(report)}</p>
        </div>
        <RiskGauge score={report.risk_score} level={report.risk_level} />
      </div>

      <div className="report-section">
        <div className="report-section-title">三通道融合</div>
        <div className="report-channel-grid">
          {channels.map((channel) => (
            <article className="report-channel-card" key={channel.key}>
              <header>
                <strong>{channel.label}</strong>
                <small>权重 {channel.weight.toFixed(2)}</small>
              </header>
              <div className="report-channel-bar">
                <div style={{ width: `${Math.min(100, channel.value * 100)}%` }} />
              </div>
              <span className="report-channel-value">{(channel.value * 100).toFixed(1)}%</span>
              <p>{channel.detail}</p>
            </article>
          ))}
        </div>
      </div>

      <div className="report-section report-two-col">
        <div className="report-block">
          <div className="report-section-title">生命周期阶段</div>
          <div className={`report-stage-badge ${report.lifecycle.stage.toLowerCase()}`}>
            {stageLabel(report.lifecycle.stage)}
          </div>
          <p>{stageCopy(report.lifecycle.stage)}</p>
          <div className="report-metric-row">
            <span>阶段得分</span>
            <strong>{(report.lifecycle.score * 100).toFixed(1)}%</strong>
          </div>
          <div className="report-metric-row">
            <span>存续区块</span>
            <strong>{report.lifecycle.age_blocks}</strong>
          </div>
        </div>

        <div className="report-block">
          <div className="report-section-title">五维特征证据</div>
          <div className="report-dimension-list">
            {dimensions.map(([key, dimension]) => (
              <article className={`report-dimension-item ${dimension.detected ? "detected" : ""}`} key={key}>
                <div className="report-dimension-head">
                  <strong>{dimensionLabel(key)}</strong>
                  <span>{dimension.detected ? "已检出" : "未检出"}</span>
                  <em>{Math.round(((dimension.score ?? (dimension.detected ? 1 : 0.18)) * 100))}%</em>
                </div>
                <p>{dimension.evidence}</p>
              </article>
            ))}
          </div>
        </div>
      </div>

      <div className="report-section">
        <div className="report-section-title">中介节点摘要</div>
        {report.intermediaries.length === 0 ? (
          <div className="report-empty">未检测到显著中介节点，资金流以合约直连为主。</div>
        ) : (
          <div className="report-intermediary-grid">
            {report.intermediaries.map((node) => (
              <article className="report-intermediary-card" key={node.address}>
                <strong>{shortAddress(node.address)}</strong>
                <span className="tag danger">{roleLabel(node.role)}</span>
                <div className="report-metric-row">
                  <span>介数中心性</span>
                  <strong>{node.betweenness.toFixed(3)}</strong>
                </div>
                <div className="report-metric-row">
                  <span>入度 / 出度</span>
                  <strong>{node.in_degree} / {node.out_degree}</strong>
                </div>
                <div className="report-metric-row">
                  <span>平均停留</span>
                  <strong>{node.avg_holding_blocks} blocks</strong>
                </div>
              </article>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function CompareCard({ title, report }: { title: string; report: AnalysisReport }) {
  return (
    <div className={`compare-card ${report.risk_level.toLowerCase()}`}>
      <strong>{title}</strong>
      <span>{report.risk_score.toFixed(1)}</span>
      <small>{report.risk_level}</small>
    </div>
  );
}

function TxStream({ txs, selectedAddress }: { txs: TxEvent[]; selectedAddress: string }) {
  return (
    <section className="panel tx-stream">
      <div className="section-title"><Radio size={15} /> Tx Stream</div>
      <div className="tx-list">
        {txs.slice(-200).reverse().map((tx) => {
          const hot = tx.from.toLowerCase() === selectedAddress.toLowerCase() || tx.to.toLowerCase() === selectedAddress.toLowerCase();
          return (
            <div className={`tx-item ${hot ? "hot" : ""}`} key={tx.tx_hash}>
              <span>{shortAddress(tx.from)} → {shortAddress(tx.to)}</span>
              <small>{tx.value} wei · block {tx.block_number}</small>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function BottomDrawer({ report, selectedAddress }: { report: AnalysisReport; selectedAddress: string }) {
  const curl = `curl -X POST ${API_BASE}/api/v1/analyze -H 'Content-Type: application/json' -d '{"contract_address":"${selectedAddress}","current_block":${report.lifecycle.age_blocks}}'`;
  return (
    <footer className="bottom-drawer">
      <span>分析报告 · cURL</span>
      <code>{curl}</code>
      <button onClick={() => navigator.clipboard.writeText(curl)}><Copy size={14} /> 复制</button>
    </footer>
  );
}

function Toast({ message, onClose }: { message: string; onClose: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onClose, 4_000);
    return () => window.clearTimeout(timer);
  }, [onClose]);
  return (
    <div className="toast">
      <AlertTriangle size={18} />
      <span>{message}</span>
      <button onClick={onClose}>查看详情</button>
    </div>
  );
}

function ShortcutsModal({ onClose }: { onClose: () => void }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="section-title">快捷键</div>
        <p><kbd>?</kbd> 打开/关闭面板</p>
        <p><kbd>d</kbd> 运行 Demo</p>
        <p><kbd>g</kbd> 切换 Graph Tab</p>
        <p><kbd>r</kbd> 打开 Report</p>
      </div>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="skeleton-grid">
      <div />
      <div />
      <div />
    </div>
  );
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`);
  if (!response.ok) {
    throw new Error(`GET ${path} failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`POST ${path} failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

async function analyzeContract(address: string): Promise<AnalysisReport> {
  return postJson<AnalysisReport>("/api/v1/analyze", {
    contract_address: address,
    current_block: 150,
  }).catch(() => mockReport(address));
}

function shortAddress(address: string) {
  if (address.length <= 12) {
    return address;
  }
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}

function stageCopy(stage: string) {
  return {
    FUNDRAISING: "推荐增长、入度上升",
    PAYOUT: "新资金支付旧投资者",
    STAGNATION: "提款限制、流出放缓",
    COLLAPSE: "余额下降、中介消失",
  }[stage] ?? "";
}

function stageLabel(stage: AnalysisReport["lifecycle"]["stage"]) {
  return {
    FUNDRAISING: "募资期",
    PAYOUT: "分红期",
    STAGNATION: "停滞期",
    COLLAPSE: "崩溃期",
  }[stage];
}

function dimensionLabel(key: string) {
  return {
    fund_flow: "资金流向",
    profit_logic: "利润逻辑",
    referral_mechanism: "推荐机制",
    withdrawal_control: "提款控制",
    camouflage: "伪装命名",
  }[key] ?? key;
}

function roleLabel(role: IntermediaryNode["role"]) {
  return {
    RELAY: "中继节点",
    ACCUMULATOR: "沉淀节点",
    DISTRIBUTOR: "分发节点",
  }[role];
}

function verdictTitle(report: AnalysisReport) {
  if (report.risk_level === "HIGH") {
    return "高风险：疑似庞氏结构";
  }
  if (report.risk_level === "MEDIUM") {
    return "中等风险：存在可疑模式";
  }
  return "低风险：未发现典型庞氏特征";
}

function verdictSummary(report: AnalysisReport) {
  const hits = Object.values(report.lifecycle.dimensions).filter((item) => item.detected).length;
  const stage = stageLabel(report.lifecycle.stage);
  if (report.risk_level === "HIGH") {
    return `合约处于${stage}，五维特征命中 ${hits}/5，图分类 p_graph=${report.graph_analysis.p_graph.toFixed(2)}，并识别 ${report.intermediaries.length} 个中介节点。`;
  }
  if (report.risk_level === "MEDIUM") {
    return `合约处于${stage}，部分维度已触发（${hits}/5），建议结合 Graph 与 Tx Stream 继续观察。`;
  }
  return `合约处于${stage}，五维特征命中 ${hits}/5，整体更接近正常 Token 行为。`;
}

function downloadJson(report: AnalysisReport) {
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `ponzi-report-${shortAddress(report.contract_address)}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function mockReport(address: string): AnalysisReport {
  const isToken = address.toLowerCase().includes("token");
  return {
    contract_address: address,
    risk_score: isToken ? 24.5 : 82.5,
    risk_level: isToken ? "LOW" : "HIGH",
    lifecycle: {
      stage: isToken ? "FUNDRAISING" : "PAYOUT",
      age_blocks: isToken ? 42 : 250,
      score: isToken ? 0.21 : 0.78,
      dimensions: {
        fund_flow: { detected: !isToken, score: isToken ? 0.18 : 0.9, evidence: isToken ? "未发现新→旧资金链" : "3 条新投资者 → 合约 → 旧投资者链" },
        profit_logic: { detected: !isToken, score: isToken ? 0.14 : 0.82, evidence: isToken ? "无分红相关转账" : "payout 与新 stake 金额正相关" },
        referral_mechanism: { detected: !isToken, score: isToken ? 0.08 : 0.74, evidence: isToken ? "无 referrer 关系" : "2 级 referral transfer 被触发" },
        withdrawal_control: { detected: !isToken, score: isToken ? 0.1 : 0.66, evidence: isToken ? "无提款冷却" : "withdraw 需等待 lockBlocks" },
        camouflage: { detected: !isToken, score: isToken ? 0.18 : 0.88, evidence: isToken ? "ERC-like transfer 命名透明" : "投资入口命名为 stake" },
      },
    },
    graph_analysis: {
      p_graph: isToken ? 0.18 : 0.91,
      node_count: isToken ? 7 : 15,
      edge_count: isToken ? 8 : 28,
    },
    intermediaries: isToken ? [] : [
      {
        address: "0xrelay000000000000000000000000000000000001",
        role: "RELAY",
        betweenness: 0.32,
        in_degree: 8,
        out_degree: 5,
        avg_holding_blocks: 3,
      },
      {
        address: "0xaccum000000000000000000000000000000000002",
        role: "ACCUMULATOR",
        betweenness: 0.18,
        in_degree: 6,
        out_degree: 2,
        avg_holding_blocks: 8,
      },
    ],
    weights: { w1: 0.45, w2: 0.35, w3: 0.2 },
    analyzed_at: new Date().toISOString(),
  };
}

const mockHistory: TxEvent[] = Array.from({ length: 18 }, (_, index) => ({
  from: index % 3 === 0 ? "0xrelay000000000000000000000000000000000001" : `0xuser${String(index).padStart(36, "0")}`,
  to: index % 4 === 0 ? "0xponzi0000000000000000000000000000000000a" : `0xold${String(index).padStart(37, "0")}`,
  value: String(100 + index * 17),
  block_number: 36 + index,
  tx_hash: `0xtx${String(index).padStart(62, "0")}`,
  timestamp: 1_700_000_000 + index * 10,
}));

function mockGraph(address: string): GraphResponse {
  return {
    nodes: [
      { id: address, label: "Contract", kind: "contract", x: 410, y: 205, degree: 8 },
      { id: "0xuser0000000000000000000000000000000000001", label: "User A", kind: "eoa", x: 140, y: 90, degree: 2 },
      { id: "0xuser0000000000000000000000000000000000002", label: "User B", kind: "eoa", x: 140, y: 320, degree: 2 },
      { id: "0xrelay000000000000000000000000000000000001", label: "Relay", kind: "intermediary", x: 410, y: 70, degree: 5 },
      { id: "0xaccum000000000000000000000000000000000002", label: "Accum", kind: "intermediary", x: 630, y: 150, degree: 4 },
      { id: "0xold0000000000000000000000000000000000001", label: "Old A", kind: "eoa", x: 650, y: 300, degree: 2 },
    ],
    edges: [
      { from: "0xuser0000000000000000000000000000000000001", to: address, value: 100, block_number: 36 },
      { from: "0xuser0000000000000000000000000000000000002", to: address, value: 120, block_number: 38 },
      { from: address, to: "0xrelay000000000000000000000000000000000001", value: 60, block_number: 40 },
      { from: "0xrelay000000000000000000000000000000000001", to: "0xold0000000000000000000000000000000000001", value: 55, block_number: 42 },
      { from: address, to: "0xaccum000000000000000000000000000000000002", value: 30, block_number: 44 },
      { from: "0xaccum000000000000000000000000000000000002", to: "0xold0000000000000000000000000000000000001", value: 25, block_number: 48 },
    ],
  };
}