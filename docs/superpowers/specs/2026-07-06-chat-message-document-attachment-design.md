# 聊天消息文档附件设计文档

**日期：** 2026-07-06  
**主题：** 前端聊天输入支持文档附件，后端以附件对象形式注入 Prompt  
**范围：** `frontend/src/App.tsx`、`frontend/src/App.css`、`agent-service/src/main/java/com/example/ragagent/dto/*`、`agent-service/src/main/java/com/example/ragagent/service/PromptBuilder.java`

## 1. 目标

让聊天输入框的 Paperclip 按钮支持“消息级文档附件”：

- 附件只作为当前问题的一部分，不入库、不进入知识库。
- 前端在浏览器读取文本文件内容，随 `ChatRequest` 一起发送。
- 后端把附件内容注入到 LLM Prompt 中，与知识库片段共享上下文预算。
- 先支持纯文本类格式：TXT、Markdown、CSV、JSON。

## 2. 设计原则

1. **最小侵入**：复用现有 `/api/chat` 和 `/api/chat/stream` JSON 接口，不改为 multipart。
2. **不入库**：附件只在当前请求生命周期内使用，不调用 `knowledge-service` 的文档上传接口。
3. **统一注入**：所有回答路径（RAG、web search、MCP tool、multi-agent）的 Prompt 都统一包含附件内容。
4. **可扩展**：DTO 设计成列表和独立对象，后续切换到 base64 或 multipart 时改动面小。

## 3. 数据结构与 API

### 3.1 前端类型

```ts
type ChatAttachment = {
  fileName: string
  contentType: string
  size: number
  content: string
}
```

`Message` 增加：

```ts
attachments?: ChatAttachment[]
```

`AgentChatResponse` 当前不需要返回附件信息，因此不变。

### 3.2 后端 DTO

新增 `agent-service/src/main/java/com/example/ragagent/dto/ChatAttachment.java`：

```java
package com.example.ragagent.dto;

public record ChatAttachment(
        String fileName,
        String contentType,
        Integer size,
        String content
) {
}
```

扩展 `ChatRequest`：

```java
public record ChatRequest(
        @NotBlank String query,
        String knowledgeBaseId,
        String conversationId,
        @Valid List<ChatMessage> history,
        ChatOptions options,
        @Valid List<ChatAttachment> attachments
) {
    public List<ChatAttachment> normalizedAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
```

## 4. 前端交互与状态

### 4.1 状态管理

新增：

```ts
const [pendingAttachments, setPendingAttachments] = useState<ChatAttachment[]>([])
```

### 4.2 文件选择

复用 `composerUploadInputRef`，点击 Paperclip 后：

1. 读取 `event.target.files`。
2. 校验 MIME 类型白名单：
   - `text/plain`
   - `text/markdown`
   - `text/csv`
   - `application/json`
3. 校验单文件大小不超过 1 MB。
4. 使用 `FileReader.readAsText()` 读取内容。
5. 生成 `ChatAttachment` 并追加到 `pendingAttachments`。

### 4.3 附件卡片 UI

在 composer 文本框上方展示附件卡片列表：

- 显示文件名、格式化后的大小。
- 提供删除按钮，从 `pendingAttachments` 中移除。
- 点击卡片可展开/折叠查看内容前 500 字符摘要。

### 4.4 发送消息

`sendMessage` 发送前：

1. 把 `pendingAttachments` 放入 `ChatRequest.attachments`。
2. 把附件信息合并进当前 `user` 消息的 `attachments` 字段，用于聊天历史展示。
3. 清空 `pendingAttachments`。

示例请求体：

```json
{
  "query": "总结一下这个文件",
  "knowledgeBaseId": "kb-1",
  "conversationId": "conv-1",
  "history": [...],
  "options": {...},
  "attachments": [
    {
      "fileName": "notes.md",
      "contentType": "text/markdown",
      "size": 1024,
      "content": "# 会议记录\n..."
    }
  ]
}
```

## 5. 后端 Prompt 注入

### 5.1 PromptBuilder 扩展

在 `PromptBuilder` 中新增私有方法：

```java
private void appendAttachments(StringBuilder prompt, List<ChatAttachment> attachments) {
    if (attachments == null || attachments.isEmpty()) {
        return;
    }

    prompt.append("附件内容：\n");
    int remaining = properties.prompt().maxContextCharacters();
    int index = 1;
    for (ChatAttachment attachment : attachments) {
        if (attachment.content() == null || attachment.content().isBlank()) {
            continue;
        }
        String block = "[%d] 文件名：%s\n%s\n\n".formatted(
                index++,
                nullToEmpty(attachment.fileName()),
                nullToEmpty(attachment.content())
        );
        if (block.length() > remaining) {
            prompt.append(trim(block, remaining));
            break;
        }
        prompt.append(block);
        remaining -= block.length();
        if (remaining <= 0) {
            break;
        }
    }
    prompt.append("\n");
}
```

### 5.2 注入位置

在 `PromptBuilder.userPrompt` 中，先于 `appendHistory` 调用 `appendAttachments`：

```java
appendAttachments(prompt, request.normalizedAttachments());
appendHistory(prompt, request.normalizedHistory());
appendContext(prompt, hits);
```

最终 prompt 结构：

```
附件内容：
[1] 文件名：notes.md
...

最近会话：
...

知识库片段：
...

用户问题：...
```

### 5.3 所有路径统一

以下方法均需要调用 `appendAttachments`：

- `userPrompt`
- `webSearchPrompt`
- `mcpToolPrompt`
- `multiAgentPrompt`

## 6. 历史记录说明

附件属于“临时上下文”，只在当前请求生效：

- `ChatMessage` 不扩展附件字段，后续轮次的 Prompt 不会自动包含上一轮附件。
- 前端聊天界面仍可在历史消息里展示附件卡片（只读展示），但不会再发送到后端。
- 如果用户需要跨轮次引用附件，需要重新上传。这符合“不入库、仅作为当前问题一部分”的需求。

## 7. 错误处理与限制

| 场景 | 处理方 | 行为 |
|---|---|---|
| 文件类型不在白名单 | 前端 | 提示用户，拒绝加入 `pendingAttachments` |
| 单文件超过 1 MB | 前端 | 提示文件过大，拒绝加入 |
| 数量超过 5 个 | 前端 | 提示最多 5 个附件 |
| `FileReader` 读取失败 | 前端 | 显示具体错误，允许移除重试 |
| 附件总内容超过 prompt 预算 | 后端 | 按顺序截断并追加省略号 |
| 后端收到非法 attachment 字段 | 后端 | 现有 `ApiExceptionHandler` 返回 400 |

## 8. 测试策略

### 8.1 前端测试

新增 `frontend/src/attachments.test.ts`：

- 校验 MIME 类型白名单通过/拒绝。
- 校验大小限制。
- 校验 `FileReader` 成功/失败后的状态。
- 校验 `pendingAttachments` 到 `ChatRequest.attachments` 的转换。

### 8.2 后端测试

新增 `agent-service/src/test/java/com/example/ragagent/service/PromptBuilderAttachmentsTest.java`：

- 空附件不产生 prompt 片段。
- 单附件正确输出文件名和内容。
- 多附件按顺序编号。
- 超长内容被截断并带省略号。

新增 `agent-service/src/test/java/com/example/ragagent/controller/ChatControllerAttachmentsTest.java`：

- 带附件的 `/api/chat/stream` 请求能正常返回 SSE 事件。

### 8.3 端到端验证

1. 启动 `agent-service` 和前端。
2. 在 composer 上传一个 Markdown 文件。
3. 提问“总结文件内容”。
4. 确认回答引用了文件中的具体信息。

## 9. 改动文件清单

1. `frontend/src/App.tsx`
   - 新增 `ChatAttachment` 类型与 `Message.attachments` 字段。
   - 新增 `pendingAttachments` 状态和相关增删方法。
   - 修改 `handleComposerUploadChange`，改为读取文本并加入 pending 列表。
   - 修改 `sendMessage`，发送时携带附件并清空 pending 列表。
   - 在 composer 中渲染附件卡片。

2. `frontend/src/App.css`
   - 新增 `.attachment-card`、`.attachment-preview` 等样式。

3. `agent-service/src/main/java/com/example/ragagent/dto/ChatAttachment.java`
   - 新增附件 DTO。

4. `agent-service/src/main/java/com/example/ragagent/dto/ChatRequest.java`
   - 增加 `attachments` 字段与 `normalizedAttachments()` 方法。

5. `agent-service/src/main/java/com/example/ragagent/service/PromptBuilder.java`
   - 新增 `appendAttachments` 方法。
   - 在四个 prompt 方法中调用。

6. `agent-service/src/test/java/com/example/ragagent/service/PromptBuilderAttachmentsTest.java`
   - 新增 PromptBuilder 附件单元测试。

7. `agent-service/src/test/java/com/example/ragagent/controller/ChatControllerAttachmentsTest.java`
   - 新增流式接口附件集成测试。

8. `frontend/src/attachments.test.ts`
   - 新增前端附件逻辑测试。

## 10. 验收标准

- [ ] 聊天 composer 的 Paperclip 按钮可以选择 TXT / Markdown / CSV / JSON 文件。
- [ ] 选中文件后在输入框上方显示可删除、可展开的附件卡片。
- [ ] 发送消息后，附件内容出现在后端 Prompt 中，LLM 能基于附件内容回答。
- [ ] 附件不会进入知识库，也不会调用 `/api/knowledge-bases/*/documents`。
- [ ] 单文件超过 1 MB 或非白名单类型时前端给出明确提示。
- [ ] 超过 5 个附件时前端给出明确提示。
- [ ] 后端对超长附件内容做截断处理，不导致 prompt 超限。
- [ ] 所有新增代码通过单元测试和本地端到端验证。
