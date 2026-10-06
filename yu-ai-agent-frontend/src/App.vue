<script setup>
import { computed, onMounted, ref, watch } from "vue";

const homeUrl = import.meta.env.BASE_URL;
const tab = ref("chat");
const demo = ref(true);
const busy = ref(false);
const notice = ref("");
const question = ref("");
const provider = ref("local");
const useMemory = ref(true);
const grounded = ref(true);
const embeddingView = ref(null);
const embeddingForm = ref({ baseUrl: "https://www.dmxapi.cn/v1", model: "bge-m3", apiKey: "", minSimilarity: 0.55 });
const embeddingBusy = ref(false);
const embeddingFeedback = ref("");
const memoryBusy = ref(false);
function applyEmbedding(view) {
  embeddingView.value = view;
  embeddingForm.value = { baseUrl: view.baseUrl || "https://www.dmxapi.cn/v1", model: view.model || "bge-m3", apiKey: "", minSimilarity: view.minSimilarity ?? 0.55 };
}
async function saveEmbedding(remove = false) {
  if (demo.value || embeddingBusy.value) return;
  embeddingBusy.value = true;
  embeddingFeedback.value = "正在检查向量接口…";
  try {
    applyEmbedding(await api("/retrieval-settings", { method: remove ? "DELETE" : "PUT", ...(remove ? {} : { body: JSON.stringify(embeddingForm.value) }) }));
    embeddingFeedback.value = remove ? "已停用向量检索，保留关键词检索。" : "向量接口已验证并保存，下一次检索自动融合关键词和语义结果。";
  } catch (e) { embeddingFeedback.value = e.message; }
  finally { embeddingBusy.value = false; }
}
async function memoryAction(path, body, method = "POST") {
  if (demo.value || memoryBusy.value) return;
  memoryBusy.value = true;
  try { state.value = await api(path, { method, body: JSON.stringify(body) }); }
  catch (e) { notice.value = e.message; }
  finally { memoryBusy.value = false; }
}
const accessToken = ref(sessionStorage.getItem("recall-token") || "");
const account = ref(null);
const authMode = ref("login");
const authBusy = ref(false);
const authForm = ref({
  username: "",
  password: "",
  confirm: "",
  importLegacy: false,
});
async function authenticate() {
  if (authBusy.value) return;
  if (
    authMode.value === "register" &&
    authForm.value.password !== authForm.value.confirm
  ) {
    notice.value = "两次输入的密码不一致。";
    return;
  }
  authBusy.value = true;
  notice.value = "";
  try {
    const body = {
      username: authForm.value.username,
      password: authForm.value.password,
    };
    if (authMode.value === "register" && authForm.value.importLegacy) {
      body.legacyWorkspace = workspace;
      body.legacyToken = accessToken.value;
    }
    account.value = await api(`/auth/${authMode.value}`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    authForm.value.password = "";
    authForm.value.confirm = "";
    accessToken.value = "";
    sessionStorage.removeItem("recall-token");
    await connect();
  } catch (e) {
    notice.value = e.message;
  } finally {
    authBusy.value = false;
  }
}
async function logout() {
  authBusy.value = true;
  try {
    await api("/auth/logout", { method: "POST" });
    account.value = null;
    accessToken.value = "";
    sessionStorage.removeItem("recall-token");
    authForm.value.password = "";
    authForm.value.confirm = "";
    enterDemo();
    notice.value = "已退出账号，服务端数据仍保留。";
  } catch (e) {
    notice.value = e.message;
  } finally {
    authBusy.value = false;
  }
}
function setProviders(items) {
  providers.value = items.map((p) =>
    account.value && !["local", "none"].includes(p.id)
      ? {
          ...p,
          ready: false,
          description: "账号知识库目前使用独立的 Local 检索",
        }
      : p,
  );
  if (!providers.value.find((p) => p.id === provider.value)?.ready)
    provider.value = "local";
}
let workspace = localStorage.getItem("recall-workspace");
if (!workspace) {
  workspace = Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  localStorage.setItem("recall-workspace", workspace);
}
const providers = ref([
  {
    id: "none",
    name: "不使用知识库",
    ready: true,
    description: "仅对话与长期记忆",
  },
  {
    id: "local",
    name: "Local · 本地检索",
    ready: true,
    description: "中文与英文关键词检索，无需额外服务",
  },
  {
    id: "dify",
    name: "Dify",
    ready: false,
    description: "通过检索 API 连接已有知识库",
  },
  {
    id: "ragflow",
    name: "RAGFlow",
    ready: false,
    description: "通过检索 API 连接已有数据集",
  },
]);
const config = ref({ modelReady: false, accessReady: false });
const modelView = ref(null);
const modelBusy = ref(false);
const modelFeedback = ref("");
const modelForm = ref({
  baseUrl: "https://www.dmxapi.cn/v1",
  model: "gpt-4.1-mini",
  apiKey: "",
});
const modelPreset = ref("dmx");
const insecureConnection = !window.isSecureContext;
const modelPresets = [
  {
    id: "dmx",
    name: "DMXAPI",
    baseUrl: "https://www.dmxapi.cn/v1",
    model: "gpt-4.1-mini",
  },
  {
    id: "openai",
    name: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    model: "gpt-4.1-mini",
  },
  {
    id: "deepseek",
    name: "DeepSeek",
    baseUrl: "https://api.deepseek.com/v1",
    model: "deepseek-chat",
  },
  {
    id: "dashscope",
    name: "阿里云百炼",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: "qwen-plus",
  },
  { id: "custom", name: "其他兼容接口" },
];
watch(
  modelForm,
  () => {
    modelFeedback.value = "";
  },
  { deep: true, flush: "sync" },
);
function applyModelView(view) {
  modelView.value = view;
  modelForm.value = { baseUrl: view.baseUrl, model: view.model, apiKey: "" };
  modelPreset.value =
    modelPresets.find((p) => p.baseUrl === view.baseUrl)?.id || "custom";
}
function changeModelPreset() {
  const preset = modelPresets.find((p) => p.id === modelPreset.value);
  if (preset?.baseUrl)
    modelForm.value = {
      baseUrl: preset.baseUrl,
      model: preset.model,
      apiKey: "",
    };
  modelFeedback.value = "";
}
async function saveModel() {
  if (demo.value || modelBusy.value) return;
  modelBusy.value = true;
  try {
    applyModelView(
      await api("/model-settings", {
        method: "PUT",
        body: JSON.stringify(modelForm.value),
      }),
    );
    modelFeedback.value =
      "已保存个人模型。下一条消息使用你的 API；密钥输入已清空。";
  } catch (e) {
    modelFeedback.value = e.message;
  } finally {
    modelBusy.value = false;
  }
}
async function testModel() {
  if (demo.value || modelBusy.value) return;
  modelBusy.value = true;
  modelFeedback.value = "正在发送一条简短测试请求…";
  try {
    const result = await api("/model-settings/test", {
      method: "POST",
      body: JSON.stringify(modelForm.value),
    });
    modelFeedback.value = result.message;
  } catch (e) {
    modelFeedback.value = e.message;
  } finally {
    modelBusy.value = false;
  }
}
async function deleteModel() {
  if (
    demo.value ||
    modelBusy.value ||
    !confirm(
      account.value
        ? "删除个人模型密钥？删除后需重新配置才能对话。"
        : "删除个人模型密钥，并恢复站点默认模型？",
    )
  )
    return;
  modelBusy.value = true;
  try {
    applyModelView(await api("/model-settings", { method: "DELETE" }));
    modelFeedback.value = account.value
      ? "个人密钥已删除。填写新的 API 后即可继续对话。"
      : "个人模型密钥已删除，已恢复站点默认配置。";
  } catch (e) {
    modelFeedback.value = e.message;
  } finally {
    modelBusy.value = false;
  }
}
const seed = {
  memories: [
    {
      id: "sample-1",
      title: "我的表达偏好",
      content: "先给结论，再解释原因。技术方案附上具体的实施步骤。",
      kind: "preference",
      updatedAt: "2026-10-04",
    },
    {
      id: "sample-2",
      title: "正在做的产品",
      content:
        "我在构建个人 AI 小产品集。Recall Agent 关注长期记忆和可插拔 RAG。",
      kind: "project",
      updatedAt: "2026-10-04",
    },
    {
      id: "sample-3",
      title: "技术背景",
      content:
        "熟悉 Java、Spring Boot 和 Vue，希望深入探索 AI Agent 的工程实践。",
      kind: "fact",
      updatedAt: "2026-10-04",
    },
  ],
  documents: [
    {
      id: "sample-doc",
      title: "Recall 产品笔记",
      content:
        "Recall Agent 将长期记忆、短期会话和知识检索分开管理。用户可以显式保存、编辑和删除记忆，选择 Local、Dify 或 RAGFlow 检索知识，回答附带来源。Local 使用关键词检索，不是向量搜索。",
      kind: "document",
      updatedAt: "2026-10-04",
    },
  ],
  messages: [],
};
const state = ref(structuredClone(seed));
const messages = ref([]);
const evidence = ref(null);
const editor = ref(null);
const search = ref("");
const kindLabels = {
  preference: "偏好",
  fact: "事实",
  project: "项目",
  document: "知识",
};
const currentItems = computed(() =>
  (tab.value === "knowledge"
    ? state.value.documents
    : state.value.memories
  ).filter((x) =>
    `${x.title} ${x.content}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
const titles = {
  chat: "工作台",
  memories: "记忆空间",
  knowledge: "知识连接",
  settings: "设置",
};

async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    ...options,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      "X-Recall-Client": "web",
      ...(!account.value && accessToken.value && !path.startsWith("/auth/")
        ? { Authorization: `Bearer ${accessToken.value}` }
        : {}),
      "X-Workspace-Key": workspace,
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (
      response.status === 401 &&
      account.value &&
      !path.startsWith("/auth/")
    ) {
      account.value = null;
      enterDemo();
      tab.value = "settings";
    }
    throw new Error(data.error || `请求失败（${response.status}）`);
  }
  return data;
}
async function connect() {
  notice.value = "";
  try {
    const cfg = await fetch("/api/config").then((r) => {
      if (!r.ok) throw new Error("后端暂不可用");
      return r.json();
    });
    config.value = cfg;
    setProviders(cfg.providers);
    state.value = await api("/workspace");
    applyModelView(await api("/model-settings"));
    applyEmbedding(await api("/retrieval-settings"));
    messages.value = [...state.value.messages];
    demo.value = false;
    evidence.value = null;
    if (!account.value)
      sessionStorage.setItem("recall-token", accessToken.value);
    sessionStorage.setItem("recall-live", "true");
    notice.value = modelView.value.keyConfigured
      ? "已连接，记忆将保存在服务端。"
      : "已连接存储；在下方填写自己的模型 API 即可开始对话。";
  } catch (e) {
    notice.value = e.message;
  }
}
function enterDemo() {
  sessionStorage.removeItem("recall-live");
  demo.value = true;
  modelView.value = null;
  embeddingView.value = null;
  embeddingForm.value = { baseUrl: "https://www.dmxapi.cn/v1", model: "bge-m3", apiKey: "", minSimilarity: 0.55 };
  embeddingFeedback.value = "";
  modelForm.value = {
    baseUrl: "https://www.dmxapi.cn/v1",
    model: "gpt-4.1-mini",
    apiKey: "",
  };
  modelPreset.value = "dmx";
  modelFeedback.value = "";
  question.value = "";
  search.value = "";
  editor.value = null;
  state.value = structuredClone(seed);
  messages.value = [];
  evidence.value = null;
  notice.value = "已进入独立演示空间，修改仅在本页内生效。";
}
function openEditor(item = null) {
  editor.value = item
    ? { ...item }
    : {
        title: "",
        content: "",
        kind: tab.value === "knowledge" ? "document" : "preference",
      };
}
async function saveItem() {
  if (!editor.value.title.trim() || !editor.value.content.trim()) {
    notice.value = "请填写标题和内容。";
    return;
  }
  const collection = tab.value === "knowledge" ? "documents" : "memories";
  try {
    if (demo.value) {
      const item = {
        ...editor.value,
        id:
          editor.value.id ||
          Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) =>
            b.toString(16).padStart(2, "0"),
          ).join(""),
        updatedAt: new Date().toISOString(),
      };
      state.value[collection] = [
        ...state.value[collection].filter((x) => x.id !== item.id),
        item,
      ];
    } else {
      const { id, title, content, kind } = editor.value;
      state.value = await api(`/${collection}`, {
        method: "POST",
        body: JSON.stringify({ id: id || null, title, content, kind }),
      });
    }
    editor.value = null;
    notice.value = demo.value
      ? "已保存到演示空间。"
      : "已保存。下一轮对话将使用更新后的内容。";
  } catch (e) {
    notice.value = e.message;
  }
}
async function removeItem(item) {
  if (!confirm(`删除「${item.title}」？`)) return;
  const collection = tab.value === "knowledge" ? "documents" : "memories";
  try {
    if (demo.value)
      state.value[collection] = state.value[collection].filter(
        (x) => x.id !== item.id,
      );
    else
      state.value = await api(`/${collection}/${item.id}`, {
        method: "DELETE",
      });
    notice.value = "已删除。已有会话可能仍含相关内容，需要时请同时清空会话。";
  } catch (e) {
    notice.value = e.message;
  }
}
async function clearChat() {
  if (busy.value || !confirm("清空当前会话？已保存的长期记忆会保留。")) return;
  try {
    if (!demo.value) state.value = await api("/chat", { method: "DELETE" });
    messages.value = [];
    evidence.value = null;
  } catch (e) {
    notice.value = e.message;
  }
}
async function send() {
  if (!question.value.trim() || busy.value) return;
  if (demo.value) {
    notice.value =
      "这是交互演示。请在设置中连接后端，再发送真实问题；也可以点击「查看示例回答」。";
    return;
  }
  const text = question.value.trim();
  busy.value = true;
  notice.value = "";
  messages.value.push({ role: "user", content: text });
  question.value = "";
  try {
    const data = await api("/chat", {
      method: "POST",
      body: JSON.stringify({
        message: text,
        provider: provider.value,
        useMemory: useMemory.value,
        grounded: grounded.value,
      }),
    });
    messages.value.push({ role: "assistant", content: data.answer });
    evidence.value = data;
    try { state.value = await api("/workspace"); }
    catch { notice.value = "回答已完成；工作区刷新失败，请重新连接以查看候选记忆。"; }
  } catch (e) {
    messages.value.pop();
    question.value = text;
    notice.value = e.message;
  } finally {
    busy.value = false;
  }
}
function showExample() {
  if (!demo.value) return;
  messages.value = [
    { role: "user", content: "怎么为我的 AI 产品集规划下一个版本？" },
    {
      role: "assistant",
      content:
        "【静态示例，未调用模型】\n建议先把 Recall 的记忆管理做完整，再扩展更多知识连接器。\n\n1. 把偏好、事实和项目背景分开保存，让每条记忆都可编辑、可遗忘。[M1]\n2. 先用 Local 验证检索流程，再连接 Dify 或 RAGFlow。[K1]\n3. 为个人产品集准备清晰的功能演示和可复现的部署说明。\n\n每次回答都展示使用了哪些上下文，方便判断记忆是否真的有帮助。",
    },
  ];
  evidence.value = {
    memories: seed.memories.slice(0, 1),
    sources: [
      {
        id: "K1",
        title: seed.documents[0].title,
        content: seed.documents[0].content,
        provider: "local",
      },
    ],
    trace: [
      "示例：召回 1 条长期记忆",
      "示例：检索 1 个知识片段",
      "展示预设回答，未调用真实模型",
    ],
  };
}
function exportData() {
  const blob = new Blob([JSON.stringify(state.value, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "recall-workspace.json";
  link.click();
  URL.revokeObjectURL(url);
}
onMounted(async () => {
  try {
    const response = await fetch("/api/config");
    if (response.ok) {
      config.value = await response.json();
      try {
        account.value = await api("/auth/me");
      } catch {}
      setProviders(config.value.providers);
      if (account.value || sessionStorage.getItem("recall-live") === "true")
        await connect();
    }
  } catch {}
});
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" :href="homeUrl" aria-label="Recall Agent 首页"
        ><span class="brand-mark">r<span>·</span></span>
        <div>recall<span class="brand-sub">THE MEMORY AGENT</span></div></a
      >
      <div class="workspace-label">
        <span class="workspace-avatar">B</span>
        <div>
          {{ account?.username || "BStronger1"
          }}<span>{{
            account ? "我的记忆空间" : "Personal AI Collection"
          }}</span>
        </div>
        <span class="workspace-dot"></span>
      </div>
      <p class="nav-label">WORKSPACE</p>
      <nav>
        <button
          v-for="(label, id) in titles"
          :key="id"
          :class="['nav-item', { active: tab === id }]"
          @click="
            tab = id;
            search = '';
            editor = null;
          "
        >
          <span class="nav-icon">{{
            { chat: "◈", memories: "◎", knowledge: "▧", settings: "⚙" }[id]
          }}</span
          >{{ label
          }}<span v-if="id === 'memories'" class="count">{{
            state.memories.length
          }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="memory-orbit">◎</div>
        <h3>越了解，越有用。</h3>
        <p>让每一次对话，<br />从你的上下文开始。</p>
        <a
          href="https://github.com/BStronger1"
          target="_blank"
          rel="noopener noreferrer"
          >GitHub · BStronger1 ↗</a
        ><span class="version">RECALL AGENT / 0.1</span>
      </div>
    </aside>
    <main>
      <header class="topbar">
        <div class="breadcrumb">
          个人产品集 <span>/</span> <strong>{{ titles[tab] }}</strong>
        </div>
        <div class="top-actions">
          <span :class="['status-dot', { live: !demo }]"></span
          >{{ demo ? "交互演示" : "已连接服务端"
          }}<button class="small-button" @click="tab = 'settings'">
            {{ demo ? "连接我的 Agent ↗" : "连接设置" }}
          </button>
        </div>
      </header>
      <div v-if="notice" class="notice" role="status">
        {{ notice
        }}<button aria-label="关闭提示" @click="notice = ''">×</button>
      </div>
      <section v-if="tab === 'chat'" class="workbench">
        <div class="chat-column">
          <div class="section-eyebrow">
            MEMORY-FIRST INTELLIGENCE <span>01 / WORKSPACE</span>
          </div>
          <div v-if="!messages.length" class="welcome">
            <div class="welcome-symbol">✳</div>
            <h1>不止回答。<br /><span>记得你，再向前。</span></h1>
            <p>
              连接你的记忆、知识与当下的想法。<br />一个持续理解你的个人 AI
              工作伙伴。
            </p>
            <div class="prompt-grid">
              <button
                @click="question = '根据我的技术背景，制定一个两周的学习计划。'"
              >
                ↗ <strong>接着上次的目标</strong
                ><span>结合我的背景，制定学习计划</span></button
              ><button
                @click="
                  question =
                    '根据知识库，总结 Recall 的记忆管理方式并注明来源。'
                "
              >
                ▧ <strong>从知识中找答案</strong
                ><span>检索我的资料，并给出依据</span>
              </button>
            </div>
            <button v-if="demo" class="text-button" @click="showExample">
              查看示例回答 <span>→</span>
            </button>
          </div>
          <div v-else class="messages" aria-live="polite">
            <div class="conversation-heading">
              <span>{{ demo ? "示例会话 · 未调用模型" : "当前会话" }}</span
              ><button :disabled="busy" class="text-button" @click="clearChat">
                清空会话
              </button>
            </div>
            <article
              v-for="(message, index) in messages"
              :key="index"
              :class="['message', message.role]"
            >
              <span class="message-avatar">{{
                message.role === "user" ? "你" : "r·"
              }}</span>
              <div>
                <strong>{{
                  message.role === "user" ? "You" : "Recall Agent"
                }}</strong>
                <p>{{ message.content }}</p>
              </div>
            </article>
            <div v-if="busy" class="thinking">
              正在召回记忆、检索知识并生成回答…
            </div>
          </div>
          <form class="composer" @submit.prevent="send">
            <textarea
              v-model="question"
              aria-label="发送给 Recall 的问题"
              placeholder="带着你的上下文，开始一段对话…"
              maxlength="6000"
              :disabled="busy"
              @keydown.ctrl.enter.prevent="send"
            ></textarea>
            <div class="composer-toolbar">
              <select v-model="grounded" aria-label="回答模式" :disabled="busy">
                <option :value="true">资料问答 · 核验证据</option>
                <option :value="false">通用对话 · 可用常识</option>
              </select>
              <label class="memory-toggle"
                ><input v-model="useMemory" type="checkbox" /> 长期记忆
                <span>{{ useMemory ? "ON" : "OFF" }}</span></label
              ><select v-model="provider" aria-label="选择知识检索服务">
                <option
                  v-for="p in providers"
                  :key="p.id"
                  :value="p.id"
                  :disabled="!p.ready"
                >
                  {{ p.name }}{{ !p.ready ? " · 未配置" : "" }}
                </option></select
              ><button
                class="send-button"
                :disabled="busy || !question.trim()"
                type="submit"
                aria-label="发送问题"
              >
                {{ busy ? "…" : "↑" }}
              </button>
            </div>
          </form>
          <p class="composer-note">
            {{
              demo
                ? "演示空间与真实数据独立 · 示例不消耗模型额度"
                : grounded ? "资料不足时先澄清 · 核验可能增加耗时与模型用量 · 开放问题可切换通用对话" : "通用对话仅检查引用编号 · 新记忆需到记忆空间确认保存"
            }}
          </p>
        </div>
        <aside class="context-panel">
          <div class="panel-heading">
            <h2>上下文透镜</h2>
            <span>CONTEXT</span>
          </div>
          <p class="panel-description">看见回答背后的记忆与依据。</p>
          <p v-if="evidence?.verification" class="verification-badge" role="status">
            {{ { no_evidence: "证据不足 · 已澄清", blocked: "核验未通过 · 已拦截", unverified: "核验不可用 · 已澄清", model_checked: "已做模型核验 · 请核对来源", citations_only: "仅引用编号校验" }[evidence.verification] }}
          </p>
          <div class="context-stat">
            <span>长期记忆</span
            ><strong>{{
              state.memories.length.toString().padStart(2, "0")
            }}</strong>
            <div class="stat-bars">
              <i
                v-for="n in 18"
                :key="n"
                :class="{
                  filled: n <= Math.min(state.memories.length * 3, 18),
                }"
              ></i>
            </div>
          </div>
          <div class="context-section">
            <h3>
              ◎ {{ evidence ? "本次召回" : "记忆预览"
              }}<span>{{ (evidence?.memories || state.memories).length }}</span>
            </h3>
            <div
              v-for="(memory, index) in (
                evidence?.memories || state.memories
              ).slice(0, 6)"
              :key="memory.id"
              class="memory-preview"
            >
              <span class="tag">{{
                evidence ? `M${index + 1}` : kindLabels[memory.kind]
              }}</span>
              <h4>{{ memory.title }}</h4>
              <p>{{ memory.content }}</p>
            </div>
            <p
              v-if="!(evidence?.memories || state.memories).length"
              class="muted"
            >
              暂无记忆。到记忆空间保存你的偏好。
            </p>
          </div>
          <div v-if="evidence" class="context-section">
            <h3>
              ▧ 知识引用<span>{{ evidence.sources.length }}</span>
            </h3>
            <details
              v-for="source in evidence.sources"
              :key="source.id"
              class="source"
            >
              <summary>[{{ source.id }}] {{ source.title }}</summary>
              <p>{{ source.content }}</p>
            </details>
            <h3 class="trace-title">执行记录</h3>
            <ol class="trace">
              <li v-for="step in evidence.trace" :key="step">{{ step }}</li>
            </ol>
          </div>
          <button class="panel-link" @click="tab = 'memories'">
            管理我的记忆 <span>↗</span>
          </button>
        </aside>
      </section>
      <section
        v-else-if="tab === 'memories' || tab === 'knowledge'"
        class="collection-page"
      >
        <div class="section-eyebrow">
          {{
            tab === "memories"
              ? "YOUR CONTEXT, YOUR CONTROL"
              : "KNOWLEDGE WITHOUT LOCK-IN"
          }}
        </div>
        <div class="page-heading">
          <div>
            <h1>
              {{
                tab === "memories"
                  ? "值得记住的，都在这里。"
                  : "让知识，随时接入。"
              }}
            </h1>
            <p>
              {{
                tab === "memories"
                  ? "显式保存，随时修订。你决定 Agent 应该记得什么。"
                  : "切换检索服务，保留同一套记忆与对话。连接密钥仅保存在服务端。"
              }}
            </p>
          </div>
          <button class="primary-button" @click="openEditor()">
            ＋ {{ tab === "memories" ? "添加记忆" : "添加文本" }}
          </button>
        </div>
        <div v-if="tab === 'memories'" class="memory-review">
          <label class="memory-toggle"><input type="checkbox" :checked="state.autoExtract" :disabled="demo || busy || memoryBusy" @change="memoryAction('/memory-policy', { autoExtract: $event.target.checked }, 'PUT')" />对话后提取候选记忆（可选）</label>
          <p class="muted">开启后会增加一次模型调用。候选只来自用户原话，可能提取不准；确认后才会保存。请勿把密钥等敏感信息发送到对话。</p>
          <h2>待审核 <span>{{ state.proposals?.length || 0 }}</span></h2>
          <article v-for="proposal in state.proposals || []" :key="proposal.id" class="review-card">
            <strong>{{ proposal.before ? "更新建议" : "新增建议" }} · {{ proposal.title }}</strong>
            <p v-if="proposal.before">修改前：{{ proposal.before.content }}</p>
            <p>建议内容：{{ proposal.content }}</p>
            <blockquote>原话：{{ proposal.sourceQuote }}</blockquote>
            <div class="button-row">
              <button class="small-button" :disabled="memoryBusy || busy" @click="memoryAction(`/memory-proposals/${proposal.id}`, { accept: true })">确认保存</button>
              <button class="small-button" :disabled="memoryBusy || busy" @click="memoryAction(`/memory-proposals/${proposal.id}`, { accept: false })">丢弃</button>
            </div>
          </article>
          <p v-if="!state.proposals?.length" class="muted">暂无待审核候选。已保存的记忆见下方。</p>
          <details class="revision-list">
            <summary>版本与来源（{{ state.revisions?.length || 0 }} 条，最多保留最近 100 条）</summary>
            <article v-for="revision in [...(state.revisions || [])].reverse()" :key="revision.id" class="review-card">
              <strong>{{ revision.after.title }} · {{ revision.undone ? "已撤销" : "已保存" }}</strong>
              <p>保存时间：{{ revision.changedAt }}</p>
              <p v-if="revision.before">此前版本：{{ revision.before.content }}（{{ revision.before.updatedAt }}）</p>
              <p>本次内容：{{ revision.after.content }}</p>
              <blockquote>来源：{{ revision.sourceQuote }}</blockquote>
              <button v-if="!revision.undone" class="small-button" :disabled="memoryBusy || busy || !state.memories.some(m => m.id === revision.after.id && m.updatedAt === revision.after.updatedAt)" @click="memoryAction(`/memory-revisions/${revision.id}/undo`, {})">撤销此变更</button>
            </article>
          </details>
        </div>
        <div v-if="tab === 'knowledge'" class="provider-grid">
          <button
            v-for="p in providers.filter((p) => p.id !== 'none')"
            :key="p.id"
            :class="['provider-card', { selected: provider === p.id }]"
            :disabled="!p.ready"
            @click="provider = p.id"
          >
            <span class="provider-icon">{{
              p.id === "local" ? "▧" : p.id === "dify" ? "D" : "R"
            }}</span
            ><strong>{{ p.name }}</strong>
            <p>{{ p.description }}</p>
            <span class="provider-state">{{
              !p.ready
                ? "待服务端配置"
                : provider === p.id
                  ? "● 已选用"
                  : "点击切换"
            }}</span>
          </button>
        </div>
        <div class="collection-toolbar">
          <h2>
            {{ tab === "knowledge" ? "本地知识文档" : "全部记忆" }}
            <span>{{ currentItems.length }}</span>
          </h2>
          <input
            v-model="search"
            type="search"
            placeholder="搜索标题或内容"
            aria-label="搜索条目"
          />
        </div>
        <p v-if="tab === 'knowledge'" class="muted">
          这里的文本只用于 Local。Dify / RAGFlow
          使用各自服务中已经建立的知识库。
        </p>
        <div class="item-grid">
          <article
            v-for="item in currentItems"
            :key="item.id"
            class="item-card"
          >
            <div class="item-top">
              <span class="tag">{{ kindLabels[item.kind] }}</span
              ><span>{{ item.updatedAt.slice(0, 10) }}</span>
            </div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.content }}</p>
            <div class="item-actions">
              <button @click="openEditor(item)">编辑</button
              ><button @click="removeItem(item)">删除</button>
            </div>
          </article>
          <div v-if="!currentItems.length" class="empty-state">
            还没有内容。添加第一条，让下次对话更了解你。
          </div>
        </div>
      </section>
      <section v-else class="settings-page">
        <div class="section-eyebrow">MAKE IT YOURS</div>
        <h1>连接你的 Recall。</h1>
        <p class="page-description">
          注册账号，接入自己的模型 API。记忆与会话跟随账号，换设备也能继续。
        </p>
        <div class="settings-card model-fields">
          <h2>{{ account ? "我的账号" : "登录 / 注册" }}</h2>
          <template v-if="account">
            <p>
              已登录：<strong>{{ account.username }}</strong
              >。记忆、会话和模型配置在你的设备之间同步。
            </p>
            <div class="button-row">
              <button v-if="demo" class="primary-button" @click="connect">
                进入我的工作区
              </button>
              <button
                class="small-button"
                :disabled="authBusy || busy || modelBusy"
                @click="logout"
              >
                退出登录
              </button>
              <button
                class="small-button"
                :disabled="busy || modelBusy"
                @click="enterDemo"
              >
                查看演示
              </button>
            </div>
            <p class="muted">
              登录最多保留 30 天。在共用电脑使用完毕后请退出登录。
            </p>
          </template>
          <form v-else @submit.prevent="authenticate">
            <div class="button-row">
              <button
                type="button"
                :class="
                  authMode === 'login' ? 'primary-button' : 'small-button'
                "
                :disabled="authBusy"
                @click="authMode = 'login'"
              >
                登录
              </button>
              <button
                type="button"
                :class="
                  authMode === 'register' ? 'primary-button' : 'small-button'
                "
                :disabled="authBusy"
                @click="authMode = 'register'"
              >
                创建账号
              </button>
            </div>
            <p>无需向维护者索取口令。注册后填写自己的模型 API 即可开始使用。</p>
            <p v-if="insecureConnection" class="model-transport">
              当前是 HTTP 连接。填写密码或 API 密钥建议使用 SSH 加密入口；HTTP
              本身不保护传输内容。
            </p>
            <fieldset :disabled="authBusy">
              <label
                >用户名<input
                  v-model="authForm.username"
                  required
                  pattern="[A-Za-z0-9_]{3,32}"
                  minlength="3"
                  maxlength="32"
                  autocomplete="username"
                  placeholder="3–32 位字母、数字或下划线（不区分大小写）"
              /></label>
              <label
                >密码<input
                  v-model="authForm.password"
                  required
                  type="password"
                  :minlength="authMode === 'register' ? 12 : 1"
                  maxlength="128"
                  :autocomplete="
                    authMode === 'register'
                      ? 'new-password'
                      : 'current-password'
                  "
                  placeholder="至少 12 位，请使用独立密码"
              /></label>
              <template v-if="authMode === 'register'">
                <label
                  >确认密码<input
                    v-model="authForm.confirm"
                    required
                    type="password"
                    minlength="12"
                    maxlength="128"
                    autocomplete="new-password"
                    placeholder="再次输入密码"
                /></label>
                <label class="account-import"
                  ><input
                    v-model="authForm.importLegacy"
                    type="checkbox"
                  />绑定当前浏览器的旧口令工作区</label
                >
                <template v-if="authForm.importLegacy">
                  <label
                    >旧网站访问口令<input
                      v-model="accessToken"
                      type="password"
                      required
                      autocomplete="off"
                      placeholder="仅迁移旧数据时需要"
                  /></label>
                  <p class="muted">
                    把旧记忆、知识、会话和个人 API
                    绑定到新账号；绑定后旧口令不能再访问此工作区。站点默认密钥不会转入账号。
                  </p>
                </template>
              </template>
              <button type="submit" class="primary-button">
                {{
                  authBusy
                    ? "正在处理…"
                    : authMode === "register"
                      ? "注册并进入工作区"
                      : "登录我的工作区"
                }}
              </button>
            </fieldset>
            <p class="muted">请保存好用户名和密码。目前不提供邮件找回。</p>
          </form>
          <details v-if="!account" class="model-hosts">
            <summary>旧口令工作区入口</summary>
            <label
              >旧访问口令<input
                v-model="accessToken"
                type="password"
                autocomplete="off"
            /></label>
            <button
              class="small-button"
              :disabled="authBusy || busy || modelBusy"
              @click="connect"
            >
              访问旧工作区
            </button>
            <p class="muted">
              新用户直接创建账号；已有数据可在创建账号时绑定。
            </p>
          </details>
        </div>
        <form class="settings-card model-fields" @submit.prevent="saveModel">
          <div class="model-card-heading">
            <h2>我的大模型</h2>
            <span class="tag">{{
              demo
                ? "请先连接工作区"
                : modelView?.custom
                  ? "个人 API"
                  : account
                    ? "待配置 API"
                    : "站点默认"
            }}</span>
          </div>
          <p>
            配置只对当前工作区生效。你自己的模型调用使用你填写的 API 账户额度。
          </p>
          <p v-if="!demo && modelView" class="model-current">
            当前使用：{{ modelView.model || "未配置" }} ·
            {{
              modelView.custom
                ? "个人配置"
                : account
                  ? "请填写自己的密钥"
                  : "站点配置"
            }}
          </p>
          <p v-if="insecureConnection" class="model-transport">
            当前连接为 HTTP，输入密钥不会获得 HTTPS 传输保护。请在可信校园/VPN
            网络使用，或改用 SSH 加密入口。
          </p>
          <fieldset :disabled="demo || modelBusy || busy">
            <label
              >服务商<select v-model="modelPreset" @change="changeModelPreset">
                <option
                  v-for="preset in modelPresets"
                  :key="preset.id"
                  :value="preset.id"
                >
                  {{ preset.name }}
                </option>
              </select></label
            >
            <label
              >API 地址<input
                v-model="modelForm.baseUrl"
                type="url"
                maxlength="500"
                required
                placeholder="https://api.example.com/v1"
                autocomplete="off"
            /></label>
            <label
              >模型名称<input
                v-model="modelForm.model"
                maxlength="160"
                required
                placeholder="填写服务商提供的准确模型名"
                autocomplete="off"
            /></label>
            <label
              >API 密钥<input
                v-model="modelForm.apiKey"
                type="password"
                maxlength="4096"
                autocomplete="off"
                spellcheck="false"
                :placeholder="
                  modelView?.custom
                    ? '已保存；留空保留原密钥，更换地址需重新填写'
                    : '输入你自己的 API 密钥'
                "
            /></label>
            <p class="muted">
              密钥加密保存在服务端，不回显、不写入浏览器存储、不包含在导出数据中。
            </p>
            <div class="button-row">
              <button type="button" class="small-button" @click="testModel">
                测试连接</button
              ><button type="submit" class="primary-button">保存并使用</button
              ><button
                v-if="modelView?.custom"
                type="button"
                class="small-button"
                @click="deleteModel"
              >
                删除个人配置
              </button>
            </div>
          </fieldset>
          <p v-if="modelBusy" class="muted" role="status">正在处理，请稍候…</p>
          <p v-if="modelFeedback" class="model-feedback" role="status">
            {{ modelFeedback }}
          </p>
          <p v-if="demo" class="muted">
            先在上方注册或登录账号，再设置个人模型。
          </p>
          <p class="muted">
            测试会发送一条简短请求，可能消耗少量 API 额度。仅支持 Chat
            Completions 兼容接口。
          </p>
          <details v-if="modelView" class="model-hosts">
            <summary>可用接口域名</summary>
            <p>{{ modelView.allowedHosts.join("、") }}</p>
            <p>其他域名需要站点维护者添加后才能使用。</p>
          </details>
        </form>
        <form class="settings-card model-fields" @submit.prevent="saveEmbedding(false)">
          <h2>语义检索</h2>
          <p>当前：{{ embeddingView?.custom ? '关键词 + 向量混合检索' : '关键词检索' }}。向量模型与聊天模型独立配置，仅对你的工作区生效。</p>
          <p class="muted">启用后，检索问题及相关记忆、知识文本会发送到此接口。保存时发送一条检查请求，首次检索还会生成资料向量，可能消耗额度。</p>
          <fieldset :disabled="demo || embeddingBusy || busy">
            <label>向量 API 地址<input v-model="embeddingForm.baseUrl" type="url" required maxlength="500" /></label>
            <label>Embedding 模型<input v-model="embeddingForm.model" required maxlength="160" placeholder="使用服务商支持的向量模型名" /></label>
            <label>语义相似度阈值<input v-model.number="embeddingForm.minSimilarity" type="number" min="0" max="1" step="0.01" required /></label>
            <p class="muted">阈值越低召回越多，也可能引入无关内容；不同向量模型需分别验证。初始 0.55 可作为 bge-m3 的测试起点。</p>
            <label>向量 API 密钥<input v-model="embeddingForm.apiKey" type="password" autocomplete="off" maxlength="4096" :placeholder="embeddingView?.custom ? '留空保留；更换地址需重新填写' : '输入向量服务密钥'" /></label>
            <div class="button-row">
              <button class="primary-button" type="submit">验证并启用混合检索</button>
              <button v-if="embeddingView?.custom" class="small-button" type="button" @click="saveEmbedding(true)">停用并删除密钥</button>
            </div>
          </fieldset>
          <p v-if="embeddingFeedback" role="status">{{ embeddingFeedback }}</p>
          <p class="muted">向量服务不可用时会回退关键词检索，并在执行记录中提示。只支持站点允许域名的兼容 embeddings 接口。</p>
        </form>
        <div class="settings-card">
          <h2>数据与记忆</h2>
          <p>
            长期记忆由你手动保存或审核候选后保存，当前会话保留最近 20 条消息。
            删除记忆会清除该条的版本记录与更新候选；不会自动清除历史对话或你已导出的文件。
          </p>
          <div class="button-row">
            <button class="small-button" @click="exportData">
              导出工作区 JSON</button
            ><button class="small-button" :disabled="busy" @click="clearChat">
              清空当前会话
            </button>
          </div>
        </div>
        <div class="settings-card">
          <h2>知识库连接</h2>
          <p>
            账号的 Local 知识库独立保存。站点级 Dify / RAGFlow
            连接器暂不向自助注册账号开放。
          </p>
          <a
            class="text-button"
            href="https://github.com/BStronger1"
            target="_blank"
            rel="noopener noreferrer"
            >查看个人 GitHub ↗</a
          >
        </div>
      </section>
      <footer class="page-footer">
        <span>Recall Agent</span><span>Built around your context.</span
        ><span>BStronger1 / AI COLLECTION</span>
      </footer>
    </main>
    <div v-if="editor" class="modal-backdrop" @click.self="editor = null">
      <form
        class="editor-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="editor-title"
        @submit.prevent="saveItem"
      >
        <div class="modal-heading">
          <h2 id="editor-title">{{ editor.id ? "编辑内容" : "添加内容" }}</h2>
          <button type="button" aria-label="关闭编辑" @click="editor = null">
            ×
          </button>
        </div>
        <label
          >标题<input
            v-model="editor.title"
            maxlength="100"
            required
            autofocus /></label
        ><label v-if="tab === 'memories'"
          >类型<select v-model="editor.kind">
            <option value="preference">偏好 · 每轮可用</option>
            <option value="fact">事实 · 按相关性召回</option>
            <option value="project">项目 · 按相关性召回</option>
          </select></label
        ><label
          >内容<textarea
            v-model="editor.content"
            maxlength="16000"
            required
            rows="7"
          ></textarea>
        </label>
        <p class="muted">
          {{
            demo
              ? "演示模式：不会写入服务端。"
              : "保存后会写入当前工作区，可随时删除。"
          }}
        </p>
        <button class="primary-button" type="submit">保存内容</button>
      </form>
    </div>
  </div>
</template>
