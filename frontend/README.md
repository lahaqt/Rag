# RAG Agent Frontend

基于 React、TypeScript、Vite 的 RAG Agent 工作台。它是展示和交互层，不直接访问数据库、Redis、对象存储或模型 Provider。

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

前端通过 Vite 代理访问后端；聊天相关请求必须进入 `agent-service`，知识库管理请求进入 `knowledge-service`：

```txt
/api/chat/* -> http://127.0.0.1:28083
/api/mcp/* -> http://127.0.0.1:28083
/api/conversations/* -> http://127.0.0.1:28083
/api/feedback/* -> http://127.0.0.1:28083
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

聊天请求已接入 `agent-service` 的 `POST /api/chat`，知识库管理请求继续接入 `knowledge-service`。服务职责与端口见根目录 [MODULES.md](../MODULES.md)。

## 维护约定

`src/App.tsx` 当前仍是页面组合入口。新增功能应优先把纯解析/格式化逻辑放在独立 TypeScript 模块并补充 Vitest 测试；当某个页面域（chat、knowledge、mcp 或 trace）继续增长时，再以该领域为单位抽取组件和 API client，避免无边界地继续扩大 `App.tsx`。
