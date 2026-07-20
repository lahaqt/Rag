import type { ChatAttachment } from './attachments'

export type Message = {
  id: number
  role: 'user' | 'assistant'
  content: string
  sources?: string[]
  citations?: ChatCitation[]
  retrievalStatus?: RetrievalStatus
  agentMode?: 'default' | 'multi-agent'
  command?: SlashCommandName
  agentTrace?: AgentTraceStep[]
  requestType?: string
  executionMode?: string
  requiredCapabilities?: string[]
  clarificationQuestion?: string
  traceId?: string
  spanId?: string
  feedbackRating?: FeedbackRating
  feedbackStatus?: 'submitting' | 'submitted' | 'error'
  feedbackError?: string
  feedbackQuestion?: string
  feedbackKnowledgeBaseId?: string
  attachments?: ChatAttachment[]
}

export type Conversation = {
  id: string
  title: string
  summary: string
  time: string
  messages: Message[]
  knowledgeBaseId?: string
  pinned?: boolean
  archived?: boolean
  deleted?: boolean
  messageCount?: number
}

export type ViewMode = 'chat' | 'search' | 'knowledge' | 'mcp'
export type ThemeMode = 'light' | 'dark'
export type SlashCommandName = 'multi-agent' | 'plan' | 'status' | 'feedback'
export type FeedbackRating = 'up' | 'down'

export type ParsedSlashCommand = { name?: SlashCommandName; query: string }
export type FeedbackEntry = { id: number; conversationId: string; content: string; createdAt: string }
export type FeedbackRecord = {
  id: number
  conversationId: string
  messageId: number
  traceId: string
  rating: FeedbackRating
  comment: string
  question: string
  answer: string
  knowledgeBaseId: string
  sourcesJson: string
  createdAt: string
}

export type KnowledgeBase = {
  id: string
  name: string
  description: string
  documentCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export type KnowledgeDocument = {
  id: string
  knowledgeBaseId: string
  fileName: string
  contentType: string
  size: number
  status: string
  chunkCount: number
  uploadedAt: string
  parsedAt?: string | null
}

export type VectorStatus = {
  storeProvider: string
  collection: string
  connectionUrl: string
  embeddingProvider: string
  vectorCount: number
  documentCount: number
  lastIndexedAt?: string | null
}

export type ChatCitation = {
  index: number
  knowledgeBaseId?: string
  documentId?: string
  chunkId?: string
  documentName: string
  chunkIndex: number
  score: number
  excerpt: string
  content?: string
  claim?: string
}

export type RetrievalHit = {
  index: number
  knowledgeBaseId?: string
  documentId?: string
  chunkId?: string
  documentName: string
  chunkIndex: number
  content?: string
  score: number
}

export type RetrievalStatusState = 'idle' | 'retrieving' | 'generating' | 'done' | 'error'
export type RetrievalStatus = {
  state: RetrievalStatusState
  query?: string
  rewrittenQuery?: string
  toolName?: string
  finishReason?: string
  hitCount: number
  averageScore?: number
  error?: string
}

export type AgentChatResponse = {
  conversationId: string
  answer: string
  requestType?: string
  executionMode?: string
  requiredCapabilities?: string[]
  clarificationQuestion?: string
  traceId?: string
  spanId?: string
  rewrittenQuery: string
  citations: ChatCitation[]
  retrievalHits?: RetrievalHit[]
  agentTrace?: AgentTraceStep[]
  toolName?: string
  webSearchResults?: Array<{ index: number; title: string; url: string; snippet: string }>
  finishReason: string
}

export type AgentTraceStep = {
  step: number
  phase: string
  route: string
  toolName: string
  action: string
  observation: string
  status?: string
  durationMs?: number
  error?: string
  traceId?: string
  spanId?: string
  attributes?: Record<string, unknown>
}

export type AgentTraceRecord = {
  id: number
  traceId: string
  spanId: string
  conversationId: string
  query: string
  intent: string
  route: string
  requestType: string
  executionMode: string
  toolName: string
  finishReason: string
  llmUsed: boolean
  agentTrace: AgentTraceStep[]
  createdAt: string
}

export type TraceReplayState = { traceId: string; loading: boolean; error: string; record?: AgentTraceRecord }
export type ChatStreamEvent = { event: string; data: unknown }
export type McpTool = { name: string; title?: string; description?: string; inputSchema?: unknown }
export type McpServer = {
  id: string
  name: string
  transport: 'stdio' | 'streamable_http'
  endpoint: string
  command: string
  args: string[]
  environment: Record<string, string>
  workingDirectory: string
  enabled: boolean
  readOnly: boolean
  status: string
  lastError: string
  updatedAt: string
  tools: McpTool[]
}
export type McpForm = {
  id: string
  name: string
  transport: 'stdio' | 'streamable_http'
  endpoint: string
  command: string
  args: string[]
  envVars: Array<{ key: string; value: string }>
  workingDirectory: string
  bearerToken: string
  enabled: boolean
}
export type McpCallResult = { serverId: string; toolName: string; success: boolean; content: string; rawResult: string }
export type JsonObject = Record<string, unknown>
export type ConversationRecord = {
  id: string
  userId: string
  title: string
  summary: string
  knowledgeBaseId: string
  pinned: boolean
  archived: boolean
  deleted: boolean
  messageCount: number
  createdAt: string
  updatedAt: string
  lastMessageAt: string
}
export type ConversationMessageRecord = {
  id: number
  conversationId: string
  seq: number
  role: 'user' | 'assistant'
  content: string
  llmUsed: boolean
  finishReason: string
  toolName: string
  traceId: string
  citationsJson: string
  createdAt: string
}
