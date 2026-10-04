<script setup>
import { computed, onMounted, ref } from "vue";

const homeUrl = import.meta.env.BASE_URL;
const tab = ref("chat");
const demo = ref(true);
const busy = ref(false);
const notice = ref("");
const question = ref("");
const provider = ref("local");
const useMemory = ref(true);
const accessToken = ref(sessionStorage.getItem("recall-token") || "");
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
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken.value}`,
      "X-Workspace-Key": workspace,
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new Error(data.error || `请求失败（${response.status}）`);
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
    providers.value = cfg.providers;
    state.value = await api("/workspace");
    messages.value = [...state.value.messages];
    demo.value = false;
    evidence.value = null;
    sessionStorage.setItem("recall-token", accessToken.value);
    notice.value = cfg.modelReady
      ? "已连接，记忆将保存在服务端。"
      : "已连接存储；配置 MODEL_API_KEY 后可开始对话。";
  } catch (e) {
    notice.value = e.message;
  }
}
function enterDemo() {
  demo.value = true;
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
        id: editor.value.id || crypto.randomUUID(),
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
      }),
    });
    messages.value.push({ role: "assistant", content: data.answer });
    evidence.value = data;
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
      providers.value = config.value.providers;
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
        <div>BStronger1<span>Personal AI Collection</span></div>
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
                : "只有显式保存的内容会成为长期记忆 · 回答请结合来源核对"
            }}
          </p>
        </div>
        <aside class="context-panel">
          <div class="panel-heading">
            <h2>上下文透镜</h2>
            <span>CONTEXT</span>
          </div>
          <p class="panel-description">看见回答背后的记忆与依据。</p>
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
          演示无需密钥。真实对话使用服务端配置的模型与持久存储。
        </p>
        <div class="settings-card">
          <h2>服务连接</h2>
          <p>访问口令由站点维护者设置，与模型 API 密钥不同。</p>
          <label
            >访问口令<input
              v-model="accessToken"
              type="password"
              autocomplete="off"
              placeholder="输入 RECALL_ACCESS_TOKEN"
          /></label>
          <div class="button-row">
            <button class="primary-button" @click="connect">连接服务端</button
            ><button class="small-button" @click="enterDemo">返回演示</button>
          </div>
          <p class="muted">
            口令仅保存在本次浏览器会话；工作区凭据保存在本地浏览器，清理浏览器数据后无法自动找回原工作区。
          </p>
        </div>
        <div class="settings-card">
          <h2>数据与记忆</h2>
          <p>
            长期记忆由你手动管理，当前会话保留最近 20
            条消息。删除记忆不会自动清除历史对话。
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
          <h2>模型与 RAG</h2>
          <p>
            模型兼容 Chat Completions 接口。Dify 与 RAGFlow
            的连接地址、密钥和数据集 ID 由环境变量配置。未配置的连接器不可选用。
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
