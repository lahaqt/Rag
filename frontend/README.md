# RAG Agent Frontend

基于 React、TypeScript、Vite 的知识问答前端界面，当前实现了参考 ChatGPT / Kimi 风格的 RAG Agent 工作台。

## 技术栈

- React 19
- TypeScript
- Vite
- lucide-react
- ESLint

## 本地启动

```bash
npm install
npm run dev
```

默认访问地址：

```txt
http://127.0.0.1:5173
```

## 后端代理

前端通过 Vite 代理访问知识库后端，和根目录 `MODULES.md` 当前端口保持一致：

```txt
/api/chat/* -> http://127.0.0.1:28083
/api/*      -> http://127.0.0.1:28081
```

配置文件：

```txt
frontend/vite.config.ts
```

## 当前页面

- 左侧会话列表、新建对话入口、知识库中心和资料上传入口。
- 中间聊天窗口、消息气泡、引用来源、Agent 问答请求状态。
- 底部输入框、快捷提问、文件上传入口。
- 右侧模型和 RAG 检索参数面板。
- 响应式布局，窄屏下隐藏右侧参数栏。

## 已验证接口

```txt
GET /api/knowledge-bases
GET /api/vector/status
```

聊天请求已接入 `agent-service` 的 `POST /api/chat`，知识库管理请求继续接入 `storage-layer`。
