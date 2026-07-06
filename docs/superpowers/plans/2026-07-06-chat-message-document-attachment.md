> 我正在使用 `writing-plans` skill 创建实现计划。

# 聊天消息文档附件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在前端聊天 composer 中支持 TXT / Markdown / CSV / JSON 文件附件，并把附件内容以 `ChatAttachment` 形式注入后端 Prompt，附件不入库。

**Architecture:** 前端用浏览器 `FileReader` 读取文本文件，生成 `ChatAttachment` 对象随 `ChatRequest` 一起发送；后端扩展 `ChatRequest` 和 `PromptBuilder`，在每个回答路径的 Prompt 中统一追加附件内容，与知识库片段共享 `maxContextCharacters` 预算。

**Tech Stack:** React 19 + TypeScript + Vite（前端），Spring Boot 3.5 + Java 17 + Maven（后端），Vitest（前端测试），JUnit 5 + Spring Boot Test（后端测试）。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `agent-service/src/main/java/com/example/ragagent/dto/ChatAttachment.java` | 创建 | 附件 DTO |
| `agent-service/src/main/java/com/example/ragagent/dto/ChatRequest.java` | 修改 | 增加 `attachments` 字段 |
| `agent-service/src/main/java/com/example/ragagent/service/PromptBuilder.java` | 修改 | 注入附件内容到 Prompt |
| `agent-service/src/test/java/com/example/ragagent/service/PromptBuilderAttachmentsTest.java` | 创建 | PromptBuilder 附件单元测试 |
| `agent-service/src/test/java/com/example/ragagent/controller/ChatControllerAttachmentsTest.java` | 创建 | 流式接口附件集成测试 |
| `frontend/src/attachments.ts` | 创建 | 前端附件读取、校验、转换逻辑 |
| `frontend/src/App.tsx` | 修改 | 状态、composer、发送消息 |
| `frontend/src/App.css` | 修改 | 附件卡片样式 |
| `frontend/src/attachments.test.ts` | 创建 | 前端附件逻辑测试 |

---

### Task 1: 后端附件 DTO

**Files:**
- Create: `agent-service/src/main/java/com/example/ragagent/dto/ChatAttachment.java`

- [ ] **Step 1: 创建 DTO**

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

- [ ] **Step 2: 编译检查**

```bash
cd agent-service
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-service/src/main/java/com/example/ragagent/dto/ChatAttachment.java
git commit -m "Add ChatAttachment DTO for message-level document attachments"
```

---

### Task 2: 扩展 ChatRequest

**Files:**
- Modify: `agent-service/src/main/java/com/example/ragagent/dto/ChatRequest.java`

- [ ] **Step 1: 修改 ChatRequest**

```java
package com.example.ragagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @NotBlank String query,
        String knowledgeBaseId,
        String conversationId,
        @Valid List<ChatMessage> history,
        ChatOptions options,
        @Valid List<ChatAttachment> attachments
) {
    public List<ChatMessage> normalizedHistory() {
        return history == null ? List.of() : history;
    }

    public List<ChatAttachment> normalizedAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
```

- [ ] **Step 2: 编译检查**

```bash
cd agent-service
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-service/src/main/java/com/example/ragagent/dto/ChatRequest.java
git commit -m "Extend ChatRequest with attachments list"
```

---

### Task 3: PromptBuilder 注入附件

**Files:**
- Modify: `agent-service/src/main/java/com/example/ragagent/service/PromptBuilder.java`

- [ ] **Step 1: 添加 import**

```java
import com.example.ragagent.dto.ChatAttachment;
```

- [ ] **Step 2: 添加 appendAttachments 私有方法**

在 `appendReflectionHint` 方法之后、或类末尾其他私有方法附近添加：

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

- [ ] **Step 3: 在 userPrompt 中注入附件**

修改 `userPrompt(ChatRequest, QueryAnalysisResponse, List<RetrievalHit>, String)`：

```java
StringBuilder prompt = new StringBuilder();
prompt.append("用户问题：").append(request.query()).append("\n");
prompt.append("改写后问题：").append(nullToEmpty(analysis.rewrittenQuery())).append("\n");
prompt.append("意图：").append(nullToEmpty(analysis.intent())).append("\n\n");

appendAttachments(prompt, request.normalizedAttachments());
appendHistory(prompt, request.normalizedHistory());
appendContext(prompt, hits);
```

- [ ] **Step 4: 在 webSearchPrompt 中注入附件**

在 `webSearchPrompt(ChatRequest, QueryAnalysisResponse, ToolDecision, List<WebSearchResult>, String)` 中，在 `appendHistory` 之前添加：

```java
appendAttachments(prompt, request.normalizedAttachments());
appendHistory(prompt, request.normalizedHistory());
```

- [ ] **Step 5: 在 mcpToolPrompt 中注入附件**

在 `mcpToolPrompt(ChatRequest, QueryAnalysisResponse, AgentToolResult, String)` 中，在 `appendHistory` 之前添加：

```java
appendAttachments(prompt, request.normalizedAttachments());
appendHistory(prompt, request.normalizedHistory());
```

- [ ] **Step 6: 在 multiAgentPrompt 中注入附件**

在 `multiAgentPrompt(ChatRequest, QueryAnalysisResponse, AgentToolResult, String)` 中，在 `appendHistory` 之前添加：

```java
appendAttachments(prompt, request.normalizedAttachments());
appendHistory(prompt, request.normalizedHistory());
```

- [ ] **Step 7: 编译检查**

```bash
cd agent-service
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add agent-service/src/main/java/com/example/ragagent/service/PromptBuilder.java
git commit -m "Inject chat attachments into all prompt paths"
```

---

### Task 4: PromptBuilder 附件单元测试

**Files:**
- Create: `agent-service/src/test/java/com/example/ragagent/service/PromptBuilderAttachmentsTest.java`

- [ ] **Step 1: 创建测试类**

```java
package com.example.ragagent.service;

import com.example.ragagent.config.RagProperties;
import com.example.ragagent.dto.ChatAttachment;
import com.example.ragagent.dto.ChatRequest;
import com.example.ragagent.dto.QueryAnalysisResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderAttachmentsTest {

    private final PromptBuilder promptBuilder = new PromptBuilder(
            new RagProperties(null, null, null, null, null, null, null, null, null)
    );

    private QueryAnalysisResponse directAnalysis(String query) {
        return new QueryAnalysisResponse(
                "session",
                null,
                query,
                query,
                query,
                "direct",
                0.9,
                "DIRECT",
                false,
                false,
                0,
                List.of(),
                List.of()
        );
    }

    @Test
    void noAttachmentsProducesNoAttachmentSection() {
        ChatRequest request = new ChatRequest("hello", null, null, List.of(), null, List.of());

        String prompt = promptBuilder.userPrompt(request, directAnalysis("hello"), List.of());

        assertThat(prompt).doesNotContain("附件内容");
    }

    @Test
    void singleAttachmentIsIncluded() {
        ChatAttachment attachment = new ChatAttachment("notes.md", "text/markdown", 12, "# Hello");
        ChatRequest request = new ChatRequest("summary", null, null, List.of(), null, List.of(attachment));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("summary"), List.of());

        assertThat(prompt)
                .contains("附件内容")
                .contains("[1] 文件名：notes.md")
                .contains("# Hello");
    }

    @Test
    void multipleAttachmentsAreNumbered() {
        ChatAttachment a1 = new ChatAttachment("a.txt", "text/plain", 5, "first");
        ChatAttachment a2 = new ChatAttachment("b.txt", "text/plain", 6, "second");
        ChatRequest request = new ChatRequest("compare", null, null, List.of(), null, List.of(a1, a2));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("compare"), List.of());

        assertThat(prompt)
                .contains("[1] 文件名：a.txt")
                .contains("[2] 文件名：b.txt");
    }

    @Test
    void emptyAttachmentContentIsSkipped() {
        ChatAttachment empty = new ChatAttachment("empty.txt", "text/plain", 0, "");
        ChatAttachment valid = new ChatAttachment("valid.txt", "text/plain", 5, "data");
        ChatRequest request = new ChatRequest("query", null, null, List.of(), null, List.of(empty, valid));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("query"), List.of());

        assertThat(prompt).contains("[1] 文件名：valid.txt");
        assertThat(prompt).doesNotContain("empty.txt");
    }

    @Test
    void longAttachmentContentIsTrimmed() {
        String content = "x".repeat(50_000);
        ChatAttachment attachment = new ChatAttachment("big.txt", "text/plain", 50_000, content);
        ChatRequest request = new ChatRequest("query", null, null, List.of(), null, List.of(attachment));

        String prompt = promptBuilder.userPrompt(request, directAnalysis("query"), List.of());

        assertThat(prompt)
                .contains("附件内容")
                .endsWith("...");
        assertThat(prompt.length()).isLessThan(content.length() + 1_000);
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd agent-service
./mvnw test -Dtest=PromptBuilderAttachmentsTest -q
```

Expected: BUILD SUCCESS, tests pass

- [ ] **Step 3: Commit**

```bash
git add agent-service/src/test/java/com/example/ragagent/service/PromptBuilderAttachmentsTest.java
git commit -m "Add PromptBuilder attachment unit tests"
```

---

### Task 5: ChatController 附件集成测试

**Files:**
- Create: `agent-service/src/test/java/com/example/ragagent/controller/ChatControllerAttachmentsTest.java`

- [ ] **Step 1: 创建集成测试**

```java
package com.example.ragagent.controller;

import com.example.ragagent.dto.ChatAttachment;
import com.example.ragagent.dto.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerAttachmentsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void streamWithAttachmentsReturnsSseEvents() throws Exception {
        ChatAttachment attachment = new ChatAttachment("notes.md", "text/markdown", 12, "# Hello");
        ChatRequest request = new ChatRequest(
                "summary",
                null,
                "conv-test",
                List.of(),
                null,
                List.of(attachment)
        );

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(response)
                .contains("event:metadata")
                .contains("event:done");
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd agent-service
./mvnw test -Dtest=ChatControllerAttachmentsTest -q
```

Expected: BUILD SUCCESS, test passes

- [ ] **Step 3: Commit**

```bash
git add agent-service/src/test/java/com/example/ragagent/controller/ChatControllerAttachmentsTest.java
git commit -m "Add ChatController stream integration test with attachments"
```

---

### Task 6: 前端 attachments 模块

**Files:**
- Create: `frontend/src/attachments.ts`

- [ ] **Step 1: 创建模块**

```typescript
export type ChatAttachment = {
  fileName: string
  contentType: string
  size: number
  content: string
}

export const ALLOWED_ATTACHMENT_TYPES = [
  'text/plain',
  'text/markdown',
  'text/csv',
  'application/json',
]

export const MAX_ATTACHMENT_SIZE = 1024 * 1024 // 1 MB
export const MAX_ATTACHMENT_COUNT = 5

export function isAllowedAttachmentType(file: File): boolean {
  return ALLOWED_ATTACHMENT_TYPES.includes(file.type)
}

export function formatAttachmentSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

export function readAttachment(file: File): Promise<ChatAttachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      resolve({
        fileName: file.name,
        contentType: file.type,
        size: file.size,
        content: String(reader.result ?? ''),
      })
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/attachments.ts
git commit -m "Add frontend attachment utilities module"
```

---

### Task 7: 前端 attachments 模块测试

**Files:**
- Create: `frontend/src/attachments.test.ts`

- [ ] **Step 1: 创建测试**

```typescript
import { describe, expect, it } from 'vitest'
import {
  ALLOWED_ATTACHMENT_TYPES,
  formatAttachmentSize,
  isAllowedAttachmentType,
  MAX_ATTACHMENT_SIZE,
} from './attachments'

describe('attachment utilities', () => {
  it('allows plain text, markdown, csv, json', () => {
    ALLOWED_ATTACHMENT_TYPES.forEach((type) => {
      expect(isAllowedAttachmentType(new File([''], 'x', { type }))).toBe(true)
    })
  })

  it('rejects unsupported types', () => {
    expect(isAllowedAttachmentType(new File([''], 'x.pdf', { type: 'application/pdf' }))).toBe(false)
    expect(isAllowedAttachmentType(new File([''], 'x.png', { type: 'image/png' }))).toBe(false)
  })

  it('formats sizes', () => {
    expect(formatAttachmentSize(512)).toBe('512 B')
    expect(formatAttachmentSize(1536)).toBe('1.5 KB')
    expect(formatAttachmentSize(2 * 1024 * 1024)).toBe('2.0 MB')
  })

  it('defines 1 MB max size', () => {
    expect(MAX_ATTACHMENT_SIZE).toBe(1024 * 1024)
  })
})
```

- [ ] **Step 2: 运行测试**

```bash
cd frontend
npm test
```

Expected: tests pass

- [ ] **Step 3: Commit**

```bash
git add frontend/src/attachments.test.ts
git commit -m "Add frontend attachment utility tests"
```

---

### Task 8: App.tsx 状态与类型

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 添加 import**

```typescript
import type { ChatAttachment } from './attachments'
import { formatAttachmentSize, isAllowedAttachmentType, MAX_ATTACHMENT_COUNT, MAX_ATTACHMENT_SIZE, readAttachment } from './attachments'
```

- [ ] **Step 2: 扩展 Message 类型**

找到 `export type Message = { ... }` 定义，在 `feedbackKnowledgeBaseId?: string` 之后添加：

```typescript
attachments?: ChatAttachment[]
```

- [ ] **Step 3: 添加 pendingAttachments 状态**

在 `App` 组件内部，在 `const [knowledgeDocuments, setKnowledgeDocuments] = useState<KnowledgeDocument[]>([])` 附近或其他合适状态声明区域添加：

```typescript
const [pendingAttachments, setPendingAttachments] = useState<ChatAttachment[]>([])
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "Add attachment type and pending state to App"
```

---

### Task 9: App.tsx 文件选择与处理

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 修改 handleComposerUploadChange**

替换现有 `handleComposerUploadChange` 函数为：

```typescript
async function handleComposerUploadChange(event: ChangeEvent<HTMLInputElement>) {
  const file = event.target.files?.[0]
  if (event.target) {
    event.target.value = ''
  }
  if (!file) return

  if (!isAllowedAttachmentType(file)) {
    window.alert(
      `无法读取「${file.name}」：当前仅支持 TXT、Markdown、CSV、JSON 文件。`,
    )
    return
  }

  if (file.size > MAX_ATTACHMENT_SIZE) {
    window.alert(`文件「${file.name}」超过 1 MB 限制。`)
    return
  }

  if (pendingAttachments.length >= MAX_ATTACHMENT_COUNT) {
    window.alert('最多支持 5 个附件。')
    return
  }

  setKnowledgeError('')
  try {
    const attachment = await readAttachment(file)
    setPendingAttachments((current) => [...current, attachment])
  } catch (error) {
    window.alert(`读取文件「${file.name}」失败：${error instanceof Error ? error.message : '未知错误'}`)
  }
}
```

- [ ] **Step 2: 添加移除附件函数**

在 `handleComposerUploadChange` 之后添加：

```typescript
function removePendingAttachment(index: number) {
  setPendingAttachments((current) => current.filter((_, i) => i !== index))
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "Implement composer file selection and pending attachment handling"
```

---

### Task 10: App.tsx 附件卡片 UI

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 在 composer 中渲染附件列表**

找到 `<form className="composer" onSubmit={sendMessage}>`，在其内部 `<textarea>` 之前添加：

```tsx
{pendingAttachments.length > 0 && (
  <div className="composer-attachments">
    {pendingAttachments.map((attachment, index) => (
      <details key={`${attachment.fileName}-${index}`} className="attachment-card">
        <summary>
          <span className="attachment-name">{attachment.fileName}</span>
          <span className="attachment-size">{formatAttachmentSize(attachment.size)}</span>
          <button
            type="button"
            className="attachment-remove"
            onClick={() => removePendingAttachment(index)}
            title="移除附件"
          >
            ×
          </button>
        </summary>
        <pre className="attachment-preview">{attachment.content.slice(0, 500)}{attachment.content.length > 500 ? '...' : ''}</pre>
      </details>
    ))}
  </div>
)}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "Render pending attachment cards in composer"
```

---

### Task 11: App.tsx 发送附件

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 修改 nextUserMessage 构造**

在 `sendMessage` 函数中，找到 `const nextUserMessage: Message = { ... }`，添加 `attachments` 字段：

```typescript
const nextUserMessage: Message = {
  id: nextMessageId.current++,
  role: 'user',
  content: text,
  agentMode: isMultiAgent ? 'multi-agent' : 'default',
  command: commandName,
  attachments: pendingAttachments.length > 0 ? [...pendingAttachments] : undefined,
}
```

- [ ] **Step 2: 在 streamChat 调用中携带 attachments**

找到 `streamChat(isMultiAgent ? '/api/chat/multi-agent/stream' : '/api/chat/stream', { ... })`，在 `options` 之后添加：

```typescript
attachments: pendingAttachments.length > 0 ? pendingAttachments : undefined,
```

- [ ] **Step 3: 发送后清空 pendingAttachments**

在 `setIsStreaming(true)` 之前添加：

```typescript
setPendingAttachments([])
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "Send pending attachments with chat request"
```

---

### Task 12: App.tsx 历史消息展示附件

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 在用户消息气泡中渲染附件**

找到渲染用户消息的区域（通常在 `message.role === 'user'` 分支中），在消息内容之后添加：

```tsx
{message.attachments && message.attachments.length > 0 && (
  <div className="message-attachments">
    {message.attachments.map((attachment, index) => (
      <details key={`${attachment.fileName}-${index}`} className="attachment-card readonly">
        <summary>
          <span className="attachment-name">{attachment.fileName}</span>
          <span className="attachment-size">{formatAttachmentSize(attachment.size)}</span>
        </summary>
        <pre className="attachment-preview">{attachment.content.slice(0, 500)}{attachment.content.length > 500 ? '...' : ''}</pre>
      </details>
    ))}
  </div>
)}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "Display attachment cards in historical user messages"
```

---

### Task 13: 附件卡片样式

**Files:**
- Modify: `frontend/src/App.css`

- [ ] **Step 1: 添加样式**

在 `App.css` 末尾追加：

```css
.composer-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 12px 0;
}

.message-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.attachment-card {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 13px;
}

.attachment-card.readonly {
  background: rgba(15, 23, 42, 0.04);
}

.attachment-card summary {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  list-style: none;
}

.attachment-card summary::-webkit-details-marker {
  display: none;
}

.attachment-name {
  font-weight: 500;
  color: #0f172a;
}

.attachment-size {
  color: #64748b;
  font-size: 12px;
}

.attachment-remove {
  margin-left: auto;
  background: transparent;
  border: none;
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  padding: 0 4px;
}

.attachment-remove:hover {
  color: #dc2626;
}

.attachment-preview {
  margin: 8px 0 0;
  padding: 8px;
  background: rgba(15, 23, 42, 0.04);
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/App.css
git commit -m "Add attachment card styles"
```

---

### Task 14: 类型检查与前端构建

**Files:**
- Modify: 无（验证步骤）

- [ ] **Step 1: 类型检查**

```bash
cd frontend
npx tsc -b
```

Expected: no errors

- [ ] **Step 2: 运行前端测试**

```bash
cd frontend
npm test
```

Expected: all tests pass

- [ ] **Step 3: Commit（如测试通过）**

此步骤无新文件需要提交，仅作为验证检查点。

---

### Task 15: 后端全量测试

**Files:**
- Modify: 无（验证步骤）

- [ ] **Step 1: 运行 PromptBuilder 测试**

```bash
cd agent-service
./mvnw test -Dtest=PromptBuilderAttachmentsTest -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行 ChatController 集成测试**

```bash
cd agent-service
./mvnw test -Dtest=ChatControllerAttachmentsTest -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 运行 agent-service 全量测试**

```bash
cd agent-service
./mvnw test -q
```

Expected: BUILD SUCCESS

---

### Task 16: 端到端验证

**Files:**
- Modify: 无（手动验证）

- [ ] **Step 1: 启动后端**

```bash
cd agent-service
./mvnw spring-boot:run
```

- [ ] **Step 2: 启动前端**

在另一个终端：

```bash
cd frontend
npm run dev
```

- [ ] **Step 3: 浏览器验证**

1. 打开前端页面（通常是 http://localhost:5173）。
2. 在 composer 点击 Paperclip，选择一个 Markdown 文件。
3. 确认附件卡片出现在输入框上方，可展开/折叠。
4. 输入“总结这个文件”并发送。
5. 确认回答引用了文件中的具体内容。
6. 检查 Network 面板，确认请求体包含 `attachments` 数组，且没有调用 `/api/knowledge-bases/*/documents`。

- [ ] **Step 4: 清理验证环境**

停止前后端进程。

---

## 自我审查

### Spec 覆盖检查

| Spec 要求 | 对应任务 |
|---|---|
| 前端新增 `ChatAttachment` 类型 | Task 6、Task 8 |
| `Message.attachments` 字段 | Task 8 |
| 后端新增 `ChatAttachment` DTO | Task 1 |
| `ChatRequest.attachments` | Task 2 |
| PromptBuilder 注入附件 | Task 3 |
| 四个 prompt 路径统一注入 | Task 3 |
| 附件共享 maxContextCharacters 预算 | Task 3 |
| 前端读取文本文件 | Task 9 |
| 前端类型/大小/数量校验 | Task 9 |
| composer 附件卡片 UI | Task 10 |
| 发送时携带 attachments | Task 11 |
| 历史消息展示附件 | Task 12 |
| 样式 | Task 13 |
| 测试 | Task 4、Task 5、Task 7、Task 14、Task 15 |
| 端到端验证 | Task 16 |

### Placeholder 检查

- 无 TBD / TODO。
- 所有代码块包含完整代码。
- 所有命令包含预期输出。

### 类型一致性检查

- `ChatAttachment` 字段名在前端（`fileName`, `contentType`, `size`, `content`）与后端一致。
- `ChatRequest` 中 `attachments` 为 `List<ChatAttachment>`，前端对应数组。
- `Message.attachments` 类型为 `ChatAttachment[]`。

---

## 执行交接

**Plan complete and saved to `docs/superpowers/plans/2026-07-06-chat-message-document-attachment.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
