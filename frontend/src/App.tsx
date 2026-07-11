import {
  Activity,
  Archive,
  ArrowDown,
  ArrowUp,
  BookOpen,
  Bot,
  Brain,
  CheckCircle2,
  Copy,
  Database,
  ExternalLink,
  FileText,
  FolderKanban,
  Grid2X2,
  Image as ImageIcon,
  List,
  MessageSquarePlus,
  Moon,
  PanelRight,
  Paperclip,
  Pin,
  Plug,
  Plus,
  RefreshCw,
  Search,
  Server,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Sun,
  TerminalSquare,
  ThumbsDown,
  ThumbsUp,
  Trash2,
  Upload,
  UserRound,
  Wrench,
  X,
} from 'lucide-react'
import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import { searchConversations } from './search'
import type { SearchResult } from './search'
import { shouldSkipFeedback } from './feedback'
import type { ChatAttachment } from './attachments'
import {
  formatAttachmentSize,
  isAllowedAttachmentType,
  MAX_ATTACHMENT_COUNT,
  MAX_ATTACHMENT_SIZE,
  readAttachment,
} from './attachments'
import './App.css'

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

type ViewMode = 'chat' | 'search' | 'knowledge' | 'mcp'

type ThemeMode = 'light' | 'dark'

type SlashCommandName = 'multi-agent' | 'plan' | 'status' | 'feedback'

type FeedbackRating = 'up' | 'down'

type ParsedSlashCommand = {
  name?: SlashCommandName
  query: string
}

type FeedbackEntry = {
  id: number
  conversationId: string
  content: string
  createdAt: string
}

type FeedbackRecord = {
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

type KnowledgeBase = {
  id: string
  name: string
  description: string
  documentCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

type KnowledgeDocument = {
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

type VectorStatus = {
  storeProvider: string
  collection: string
  connectionUrl: string
  embeddingProvider: string
  vectorCount: number
  documentCount: number
  lastIndexedAt?: string | null
}

type ChatCitation = {
  index: number
  knowledgeBaseId?: string
  documentId?: string
  chunkId?: string
  documentName: string
  chunkIndex: number
  score: number
  excerpt: string
  content?: string
}

type RetrievalHit = {
  index: number
  knowledgeBaseId?: string
  documentId?: string
  chunkId?: string
  documentName: string
  chunkIndex: number
  content?: string
  score: number
}

type RetrievalStatusState = 'idle' | 'retrieving' | 'generating' | 'done' | 'error'

type RetrievalStatus = {
  state: RetrievalStatusState
  query?: string
  rewrittenQuery?: string
  toolName?: string
  finishReason?: string
  hitCount: number
  averageScore?: number
  error?: string
}

type AgentChatResponse = {
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
  webSearchResults?: Array<{
    index: number
    title: string
    url: string
    snippet: string
  }>
  finishReason: string
}

type AgentTraceStep = {
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

type AgentTraceRecord = {
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

type TraceReplayState = {
  traceId: string
  loading: boolean
  error: string
  record?: AgentTraceRecord
}

type ChatStreamEvent = {
  event: string
  data: unknown
}

type McpTool = {
  name: string
  title?: string
  description?: string
  inputSchema?: unknown
}

type McpServer = {
  id: string
  name: string
  transport: 'stdio' | 'streamable_http'
  endpoint: string
  command: string
  args: string[]
  environment: Record<string, string>
  workingDirectory: string
  enabled: boolean
  status: string
  lastError: string
  updatedAt: string
  tools: McpTool[]
}

type McpForm = {
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

type McpCallResult = {
  serverId: string
  toolName: string
  success: boolean
  content: string
  rawResult: string
}

type JsonObject = Record<string, unknown>

type ConversationRecord = {
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

type ConversationMessageRecord = {
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

const USER_ID = 'local-user'
const JAEGER_UI_BASE_URL = (import.meta.env.VITE_JAEGER_UI_BASE_URL ?? 'http://localhost:16686').replace(/\/$/, '')
const DEFAULT_CONVERSATION_TITLE = 'New conversation'
const MAX_CONVERSATION_TITLE_LENGTH = 44
const TITLE_LEADING_FILLERS = [
  '请帮我',
  '请你帮我',
  '帮我',
  '帮忙',
  '请问',
  '请',
  '能不能',
  '可以帮我',
  '可以',
  '如何',
  '怎么',
  '怎样',
  'please help me',
  'help me',
  'please',
  'can you',
  'could you',
  'how to',
  'how do i',
]

function stripTitleTrailingPunctuation(value: string) {
  return value.trim().replace(/[\s，。！？、；：,.!?;:]+$/g, '')
}

function conversationTitleFromQuery(value: string) {
  let title = value
    .trim()
    .replace(/^\/(multi-agent|plan|feedback)\s+/i, '')
    .replace(/[`*_#>[\]{}]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()

  if (!title) {
    return DEFAULT_CONVERSATION_TITLE
  }

  const lowerTitle = title.toLowerCase()
  const filler = TITLE_LEADING_FILLERS.find((item) => lowerTitle.startsWith(item))
  if (filler) {
    title = title.slice(filler.length).trim()
  }

  title = stripTitleTrailingPunctuation(title)
  const firstSegment = stripTitleTrailingPunctuation(title.split(/[。！？!?；;\n]|[，,、：:]/)[0]?.trim() ?? '')
  if (firstSegment.length >= 8 || title.length > MAX_CONVERSATION_TITLE_LENGTH) {
    title = firstSegment
  }

  title = stripTitleTrailingPunctuation(title)
  if (!title) {
    return DEFAULT_CONVERSATION_TITLE
  }
  if (title.length <= MAX_CONVERSATION_TITLE_LENGTH) {
    return title
  }
  let prefix = title.slice(0, MAX_CONVERSATION_TITLE_LENGTH - 3)
  if (endsInsideAsciiToken(title, MAX_CONVERSATION_TITLE_LENGTH - 3)) {
    const boundary = Math.max(prefix.lastIndexOf(' '), prefix.lastIndexOf('/'), prefix.lastIndexOf(':'))
    if (boundary >= 8) {
      prefix = prefix.slice(0, boundary)
    }
  }
  return `${stripTitleTrailingPunctuation(prefix)}...`
}

function endsInsideAsciiToken(value: string, endExclusive: number) {
  if (endExclusive <= 0 || endExclusive >= value.length) {
    return false
  }
  return isAsciiTokenChar(value[endExclusive - 1]) && isAsciiTokenChar(value[endExclusive])
}

function isAsciiTokenChar(value: string) {
  return /^[A-Za-z0-9_.-]$/.test(value)
}

const initialConversations: Conversation[] = [
  {
    id: 'conversation-seed-1',
    title: 'RAG 知识问答设计',
    summary: '检索策略、引用、模型参数',
    time: '刚刚',
    messages: [
      {
        id: 1,
        role: 'user',
        content: '我想做一个基于 RAG 的企业知识问答，前端应该先怎么设计？',
      },
      {
        id: 2,
        role: 'assistant',
        content:
          '建议把体验收束在一条主路径：选择知识库、提出问题、查看答案、核对引用、继续追问。主界面保持低干扰的问答体验，知识库和文件上下文放在左侧，检索参数放到可收起的右侧面板。',
        sources: ['产品需求文档.pdf', 'RAG 服务接口草案.md', '企业制度库 / 入职流程'],
      },
    ],
  },
  {
    id: 'conversation-seed-2',
    title: '知识库接入',
    summary: '上传、切片、向量化状态',
    time: '12:48',
    messages: [
      {
        id: 1,
        role: 'assistant',
        content:
          '知识库模块建议展示文件解析状态、切片数量、向量化进度和失败重试。聊天页只暴露当前知识库选择，避免把用户从提问流程里拽出去。',
      },
    ],
  },
  {
    id: 'conversation-seed-3',
    title: '模型参数调试',
    summary: 'Temperature、Top K、引用数量',
    time: '昨天',
    messages: [
      {
        id: 1,
        role: 'assistant',
        content:
          '调试参数适合放在右侧面板。普通用户只看知识库和模型，高级模式再展开 temperature、Top K、相似度阈值和最大引用数。',
      },
    ],
  },
]

const sourcePreview = [
  { title: '产品需求文档.pdf', meta: '第 4 页 · 命中 0.83' },
  { title: 'RAG 服务接口草案.md', meta: '接口定义 · 命中 0.79' },
  { title: '入职流程.docx', meta: '制度章节 · 命中 0.73' },
]

function citationTitle(citation: ChatCitation) {
  return citation.documentName || `Chunk ${citation.chunkIndex}`
}

function citationMeta(citation: ChatCitation) {
  return `chunk ${citation.chunkIndex} / ${citation.score.toFixed(3)}`
}

function citationText(citation: ChatCitation) {
  return (citation.content || citation.excerpt || '').trim()
}

function citationKey(citation: ChatCitation) {
  return citation.chunkId || `${citation.documentId || citation.documentName}-${citation.chunkIndex}-${citation.index}`
}

function scoreAverage(items: Array<{ score: number }>) {
  if (items.length === 0) {
    return undefined
  }
  return items.reduce((total, item) => total + item.score, 0) / items.length
}

function statusFromCitations(
  current: RetrievalStatus | undefined,
  citations: ChatCitation[] = [],
  retrievalHits: RetrievalHit[] = [],
): RetrievalStatus {
  const scoreItems = citations.length > 0 ? citations : retrievalHits
  return {
    state: 'done',
    query: current?.query,
    rewrittenQuery: current?.rewrittenQuery,
    toolName: current?.toolName,
    finishReason: current?.finishReason,
    hitCount: Math.max(citations.length, retrievalHits.length, current?.hitCount ?? 0),
    averageScore: scoreAverage(scoreItems) ?? current?.averageScore,
  }
}

function statusFromStoredMessage(
  record: ConversationMessageRecord,
  parsedCitations: { citations?: ChatCitation[]; sources?: string[] },
): RetrievalStatus | undefined {
  if (record.role !== 'assistant') {
    return undefined
  }
  const citations = parsedCitations.citations ?? []
  const sources = parsedCitations.sources ?? []
  if (citations.length === 0 && sources.length === 0 && !record.finishReason && !record.toolName) {
    return undefined
  }
  return {
    state: record.finishReason === 'error' ? 'error' : 'done',
    toolName: record.toolName,
    finishReason: record.finishReason,
    hitCount: citations.length || sources.length,
    averageScore: scoreAverage(citations),
  }
}

function retrievalStatusLabel(status?: RetrievalStatus) {
  if (!status) {
    return '暂无检索'
  }
  if (status.state === 'retrieving') {
    return '检索中'
  }
  if (status.state === 'generating') {
    return '生成中'
  }
  if (status.state === 'error') {
    return '失败'
  }
  return status.hitCount > 0 ? '已完成' : '无命中'
}

function retrievalStatusMeta(status?: RetrievalStatus) {
  if (!status) {
    return '等待新的对话检索'
  }
  const items = [status.toolName, status.finishReason].filter(Boolean)
  if (items.length > 0) {
    return items.join(' · ')
  }
  return status.rewrittenQuery || status.query || '本轮对话'
}

function retrievalFlowClass(status: RetrievalStatus | undefined, stage: 'query' | 'retrieve' | 'generate') {
  if (!status || status.state === 'idle') {
    return ''
  }
  if (status.state === 'error') {
    return stage === 'query' ? 'done' : 'error'
  }
  if (stage === 'query') {
    return 'done'
  }
  if (stage === 'retrieve') {
    return ['retrieving', 'generating', 'done'].includes(status.state) ? 'done' : ''
  }
  return ['generating', 'done'].includes(status.state) ? 'done' : ''
}

function formatDate(value?: string | null) {
  if (!value) {
    return '-'
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

type AnswerBlock =
  | { kind: 'paragraph'; text: string }
  | { kind: 'table'; rows: string[][]; aligns: ('left' | 'center' | 'right')[] }

function splitAnswerBlocks(text: string): string[] {
  return text
    .split(/\r?\n{1,}/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

function cleanAnswerLine(line: string): string {
  let result = line
  result = result.replace(/^#{1,6}\s*/, '')
  result = result.replace(/^\s*[-*+]\s+/, '')
  result = result.replace(/^\s*(?:\d+)[.、)]\s*/, '')
  result = result.replace(/\*\*(.+?)\*\*/g, '$1')
  result = result.replace(/__(.+?)__/g, '$1')
  result = result.replace(/\*(.+?)\*/g, '$1')
  result = result.replace(/_(.+?)_/g, '$1')
  result = result.replace(/`([^`]+)`/g, '$1')
  result = result.replace(/\s*\[\d+(?:[,，\s\d]+)?\]\s*/g, ' ')
  result = result.replace(/\s{2,}/g, ' ').trim()
  return result
}

function isTableRow(line: string) {
  return line.startsWith('|') && line.endsWith('|') && line.includes('|', 1)
}

function isTableSeparator(line: string) {
  if (!isTableRow(line)) return false
  const inner = line.slice(1, -1)
  return inner.split('|').every((cell) => /^:?\s*-{2,}\s*:?$/.test(cell.trim()))
}

function parseCells(row: string) {
  return row
    .slice(1, -1)
    .split('|')
    .map((cell) => cleanAnswerLine(cell.trim()))
}

function parseAligns(separator: string): ('left' | 'center' | 'right')[] {
  return separator
    .slice(1, -1)
    .split('|')
    .map((cell) => {
      const trimmed = cell.trim()
      const left = trimmed.startsWith(':')
      const right = trimmed.endsWith(':')
      if (left && right) return 'center' as const
      if (right) return 'right' as const
      if (left) return 'left' as const
      return 'left' as const
    })
}

function parseAnswerBlocks(text: string): AnswerBlock[] {
  const lines = splitAnswerBlocks(text)
  const blocks: AnswerBlock[] = []
  let i = 0
  while (i < lines.length) {
    if (
      i + 1 < lines.length
      && isTableRow(lines[i])
      && isTableSeparator(lines[i + 1])
    ) {
      const header = parseCells(lines[i])
      const aligns = parseAligns(lines[i + 1])
      const rows: string[][] = [header]
      let j = i + 2
      while (j < lines.length && isTableRow(lines[j])) {
        rows.push(parseCells(lines[j]))
        j += 1
      }
      blocks.push({ kind: 'table', rows, aligns })
      i = j
    } else {
      blocks.push({ kind: 'paragraph', text: cleanAnswerLine(lines[i]) })
      i += 1
    }
  }
  return blocks
}

function mapConversationRecord(record: ConversationRecord): Conversation {
  return {
    id: record.id,
    title: record.title || DEFAULT_CONVERSATION_TITLE,
    summary: record.summary || `${record.messageCount} messages`,
    time: formatDate(record.lastMessageAt || record.updatedAt),
    messages: [],
    knowledgeBaseId: record.knowledgeBaseId,
    pinned: record.pinned,
    archived: record.archived,
    deleted: record.deleted,
    messageCount: record.messageCount,
  }
}

function mapMessageRecord(record: ConversationMessageRecord): Message {
  const parsedCitations = parseStoredCitations(record.citationsJson)
  return {
    id: Number(record.id),
    role: record.role,
    content: record.content,
    citations: parsedCitations.citations,
    sources: parsedCitations.sources,
    retrievalStatus: statusFromStoredMessage(record, parsedCitations),
    traceId: record.traceId || undefined,
  }
}

function parseStoredCitations(value?: string | null): { citations?: ChatCitation[]; sources?: string[] } {
  if (!value) {
    return {}
  }
  try {
    const parsed = JSON.parse(value) as unknown
    if (!Array.isArray(parsed)) {
      return {}
    }
    if (parsed.every((item) => typeof item === 'string')) {
      return { sources: parsed as string[] }
    }
    const citations = parsed.filter(isChatCitation)
    return citations.length > 0 ? { citations } : {}
  } catch {
    return {}
  }
}

function isChatCitation(value: unknown): value is ChatCitation {
  if (!value || typeof value !== 'object') {
    return false
  }
  const citation = value as Partial<ChatCitation>
  return typeof citation.index === 'number'
    && typeof citation.chunkIndex === 'number'
    && typeof citation.score === 'number'
}

function App() {
  const [conversationList, setConversationList] = useState<Conversation[]>(initialConversations)
  const [activeId, setActiveId] = useState(initialConversations[0].id)
  const [activeView, setActiveView] = useState<ViewMode>('chat')
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => {
    if (typeof window === 'undefined') {
      return 'light'
    }
    return window.localStorage.getItem('rag-theme') === 'dark' ? 'dark' : 'light'
  })
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [activeKnowledgeBaseId, setActiveKnowledgeBaseId] = useState('')
  const [knowledgeDocuments, setKnowledgeDocuments] = useState<KnowledgeDocument[]>([])
  const [pendingAttachments, setPendingAttachments] = useState<ChatAttachment[]>([])
  const [vectorStatus, setVectorStatus] = useState<VectorStatus | null>(null)
  const [knowledgeLoading, setKnowledgeLoading] = useState(false)
  const [knowledgeError, setKnowledgeError] = useState('')
  const [knowledgeSearch, setKnowledgeSearch] = useState('')
  const [deletingDocumentId, setDeletingDocumentId] = useState('')
  const [mcpServers, setMcpServers] = useState<McpServer[]>([])
  const [mcpLoading, setMcpLoading] = useState(false)
  const [mcpError, setMcpError] = useState('')
  const [mcpForm, setMcpForm] = useState<McpForm>({
    id: '',
    name: '',
    transport: 'streamable_http',
    endpoint: '',
    command: '',
    args: [''],
    envVars: [{ key: '', value: '' }],
    workingDirectory: '',
    bearerToken: '',
    enabled: true,
  })
  const [selectedMcpServerId, setSelectedMcpServerId] = useState('')
  const [refreshingMcpId, setRefreshingMcpId] = useState('')
  const [callingMcpTool, setCallingMcpTool] = useState('')
  const [mcpToolArguments, setMcpToolArguments] = useState<Record<string, string>>({})
  const [mcpCallResult, setMcpCallResult] = useState<McpCallResult | null>(null)
  const [feedbackEntries, setFeedbackEntries] = useState<FeedbackEntry[]>([])
  const [draft, setDraft] = useState('')
  const [inspectorOpen, setInspectorOpen] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [showArchivedConversations, setShowArchivedConversations] = useState(false)
  const [conversationLoading, setConversationLoading] = useState(false)
  const [conversationError, setConversationError] = useState('')
  const [scrollToMessageId, setScrollToMessageId] = useState<number | null>(null)
  const [messagesByConversation, setMessagesByConversation] = useState(() =>
    Object.fromEntries(initialConversations.map((item) => [item.id, item.messages])) as Record<string, Message[]>,
  )
  const [isStreaming, setIsStreaming] = useState(false)
  const [copiedMessageId, setCopiedMessageId] = useState<number | null>(null)
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const chatStageRef = useRef<HTMLDivElement | null>(null)
  const copiedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isPinnedRef = useRef(true)
  const nextMessageId = useRef(100)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)
  const composerUploadInputRef = useRef<HTMLInputElement | null>(null)
  const [libraryTypeFilter, setLibraryTypeFilter] = useState<'all' | 'image' | 'file'>('all')
  const [libraryViewMode, setLibraryViewMode] = useState<'list' | 'grid'>('list')
  const [libraryFilterOpen, setLibraryFilterOpen] = useState(false)
  const [previewDocument, setPreviewDocument] = useState<KnowledgeDocument | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewChunks, setPreviewChunks] = useState<{ id: string; content: string }[]>([])
  const [previewError, setPreviewError] = useState('')
  const [traceReplay, setTraceReplay] = useState<TraceReplayState | null>(null)
  const [traceReplaySort, setTraceReplaySort] = useState<'sequence' | 'duration'>('sequence')

  const activeConversation = useMemo(
    () => conversationList.find((item) => item.id === activeId) ?? conversationList[0] ?? initialConversations[0],
    [activeId, conversationList],
  )

  const messages = useMemo(
    () => messagesByConversation[activeId] ?? [],
    [activeId, messagesByConversation],
  )

  const activeCitations = useMemo(
    () => [...messages].reverse().find((message) => message.role === 'assistant' && message.citations?.length)?.citations ?? [],
    [messages],
  )

  const latestRetrievalStatus = useMemo(
    () => [...messages].reverse().find((message) => message.role === 'assistant' && message.retrievalStatus)?.retrievalStatus,
    [messages],
  )

  const replaySteps = useMemo(() => {
    const steps = [...(traceReplay?.record?.agentTrace ?? [])]
    return traceReplaySort === 'duration'
      ? steps.sort((left, right) => (right.durationMs ?? -1) - (left.durationMs ?? -1))
      : steps.sort((left, right) => left.step - right.step)
  }, [traceReplay, traceReplaySort])

  useEffect(() => {
    document.documentElement.dataset.theme = themeMode
    window.localStorage.setItem('rag-theme', themeMode)
  }, [themeMode])

  useEffect(() => {
    if (!scrollToMessageId) {
      return
    }

    const element = document.querySelector<HTMLElement>(`[data-message-id="${scrollToMessageId}"]`)
    if (element) {
      const scrollContainer = element.closest<HTMLElement>('.chat-stage')
      if (scrollContainer) {
        const targetTop = element.offsetTop - scrollContainer.clientHeight / 3
        scrollContainer.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' })
      }
      element.classList.add('highlight-message')
      const timer = setTimeout(() => {
        element.classList.remove('highlight-message')
      }, 2000)
      return () => clearTimeout(timer)
    }
  }, [activeView, activeId, scrollToMessageId])

  function handleChatStageScroll() {
    const stage = chatStageRef.current
    if (!stage) return
    const remaining = stage.scrollHeight - stage.scrollTop - stage.clientHeight
    const pinned = remaining <= 120
    isPinnedRef.current = pinned
    setShowScrollToBottom(remaining > 120)
  }

  function scrollChatToBottom() {
    const stage = chatStageRef.current
    if (!stage) return
    stage.scrollTo({ top: stage.scrollHeight, behavior: 'smooth' })
    isPinnedRef.current = true
    setShowScrollToBottom(false)
  }

  async function handleCopyMessage(message: Message) {
    try {
      await navigator.clipboard.writeText(message.content)
      setCopiedMessageId(message.id)
      if (copiedTimerRef.current) {
        clearTimeout(copiedTimerRef.current)
      }
      copiedTimerRef.current = setTimeout(() => setCopiedMessageId(null), 1600)
    } catch {
      /* clipboard unavailable — silently ignore */
    }
  }

  // Auto-follow to bottom while streaming / on new messages, if user is pinned.
  useEffect(() => {
    if (!isPinnedRef.current) return
    const stage = chatStageRef.current
    if (!stage) return
    stage.scrollTop = stage.scrollHeight
  }, [messages, isStreaming])

  // Reset pinned state on conversation switch.
  useEffect(() => {
    isPinnedRef.current = true
    setShowScrollToBottom(false)
  }, [activeId])

  // Clear the copied-feedback timer on unmount.
  useEffect(() => () => {
    if (copiedTimerRef.current) {
      clearTimeout(copiedTimerRef.current)
    }
  }, [])

  useEffect(() => {
    let ignore = false

    async function loadConversations() {
      setConversationLoading(true)
      setConversationError('')
      try {
        const params = new URLSearchParams({
          userId: USER_ID,
          archived: String(showArchivedConversations),
        })
        const response = await fetch(`/api/conversations?${params}`)
        if (!response.ok) {
          throw new Error(`GET /api/conversations ${response.status}`)
        }
        const records = (await response.json()) as ConversationRecord[]
        if (ignore) {
          return
        }
        const nextConversations = records.map(mapConversationRecord)
        if (nextConversations.length > 0) {
          setConversationList(nextConversations)
          setActiveId((current) =>
            nextConversations.some((item) => item.id === current) ? current : nextConversations[0].id,
          )
        }
      } catch (error) {
        if (!ignore) {
          setConversationError(error instanceof Error ? error.message : '会话加载失败')
        }
      } finally {
        if (!ignore) {
          setConversationLoading(false)
        }
      }
    }

    loadConversations()

    return () => {
      ignore = true
    }
  }, [showArchivedConversations])

  useEffect(() => {
    if (!activeId || activeId.startsWith('conversation-seed-')) {
      return
    }
    let ignore = false

    async function loadMessages() {
      try {
        const response = await fetch(`/api/conversations/${encodeURIComponent(activeId)}/messages?userId=${USER_ID}`)
        if (!response.ok) {
          throw new Error(`GET /api/conversations/${activeId}/messages ${response.status}`)
        }
        const records = (await response.json()) as ConversationMessageRecord[]
        if (!ignore) {
          setMessagesByConversation((current) => ({
            ...current,
            [activeId]: records.map(mapMessageRecord),
          }))
        }
      } catch (error) {
        if (!ignore) {
          setConversationError(error instanceof Error ? error.message : '消息加载失败')
        }
      }
    }

    loadMessages()

    return () => {
      ignore = true
    }
  }, [activeId])

  async function createConversation() {
    setConversationError('')
    try {
      const response = await fetch('/api/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: USER_ID,
          title: DEFAULT_CONVERSATION_TITLE,
          knowledgeBaseId: activeKnowledgeBaseId,
        }),
      })
      if (!response.ok) {
        throw new Error(`POST /api/conversations ${response.status}`)
      }
      const conversation = mapConversationRecord((await response.json()) as ConversationRecord)
      setConversationList((current) => [conversation, ...current])
      setMessagesByConversation((current) => ({ ...current, [conversation.id]: [] }))
      setActiveId(conversation.id)
      setActiveView('chat')
    } catch (error) {
      setConversationError(error instanceof Error ? error.message : '新建会话失败')
    }
  }

  async function updateConversation(id: string, patch: Partial<ConversationRecord>) {
    setConversationError('')
    try {
      const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: USER_ID, ...patch }),
      })
      if (!response.ok) {
        throw new Error(`PATCH /api/conversations/${id} ${response.status}`)
      }
      const conversation = mapConversationRecord((await response.json()) as ConversationRecord)
      setConversationList((current) =>
        patch.deleted
          ? current.filter((item) => item.id !== id)
          : current.map((item) => (item.id === id ? { ...item, ...conversation } : item)),
      )
      if (patch.deleted && activeId === id) {
        const next = conversationList.find((item) => item.id !== id)
        if (next) {
          setActiveId(next.id)
        }
      }
    } catch (error) {
      setConversationError(error instanceof Error ? error.message : '会话更新失败')
    }
  }

  const searchResults = useMemo<SearchResult[]>(
    () => searchConversations(searchQuery, conversationList, messagesByConversation),
    [searchQuery, conversationList, messagesByConversation],
  )
  const filteredSearchResults = useMemo(
    () => searchResults.filter(({ conversation }) => Boolean(conversation.archived) === showArchivedConversations),
    [searchResults, showArchivedConversations],
  )
  const visibleConversations = useMemo(() => {
    return [...conversationList]
      .filter((item) => Boolean(item.archived) === showArchivedConversations)
      .sort((left, right) => Number(Boolean(right.pinned)) - Number(Boolean(left.pinned)))
  }, [conversationList, showArchivedConversations])
  const activeKnowledgeBase = knowledgeBases.find((item) => item.id === activeKnowledgeBaseId)
  const uploadKnowledgeBaseId = activeKnowledgeBaseId || knowledgeBases[0]?.id || ''
  const totalDocumentCount = knowledgeBases.reduce((total, item) => total + item.documentCount, 0)
  const totalChunkCount = knowledgeBases.reduce((total, item) => total + item.chunkCount, 0)
  const activeMcpServer = mcpServers.find((server) => server.id === selectedMcpServerId) ?? mcpServers[0]
  const mcpToolCount = mcpServers.reduce((total, server) => total + server.tools.length, 0)
  const onlineMcpCount = mcpServers.filter((server) => server.status === 'online').length
  const filteredKnowledgeDocuments = useMemo(() => {
    const keyword = knowledgeSearch.trim().toLowerCase()
    return knowledgeDocuments.filter((document) => {
      if (libraryTypeFilter !== 'all') {
        const isImage = document.contentType.toLowerCase().startsWith('image/')
        if (libraryTypeFilter === 'image' && !isImage) return false
        if (libraryTypeFilter === 'file' && isImage) return false
      }
      if (!keyword) return true
      return [document.fileName, document.contentType, document.status, document.knowledgeBaseId]
        .join(' ')
        .toLowerCase()
        .includes(keyword)
    })
  }, [knowledgeDocuments, knowledgeSearch, libraryTypeFilter])

  useEffect(() => {
    let ignore = false

    async function loadKnowledgeBases() {
      setKnowledgeLoading(true)
      setKnowledgeError('')

      try {
        const [knowledgeBaseResponse, vectorStatusResponse] = await Promise.all([
          fetch('/api/knowledge-bases'),
          fetch('/api/vector/status'),
        ])

        if (!knowledgeBaseResponse.ok) {
          throw new Error(`GET /api/knowledge-bases ${knowledgeBaseResponse.status}`)
        }
        if (!vectorStatusResponse.ok) {
          throw new Error(`GET /api/vector/status ${vectorStatusResponse.status}`)
        }

        const nextKnowledgeBases = (await knowledgeBaseResponse.json()) as KnowledgeBase[]
        const nextVectorStatus = (await vectorStatusResponse.json()) as VectorStatus
        const documentLists = await Promise.all(
          nextKnowledgeBases.map(async (knowledgeBase) => {
            const response = await fetch(`/api/knowledge-bases/${knowledgeBase.id}/documents`)
            if (!response.ok) {
              throw new Error(`GET /api/knowledge-bases/${knowledgeBase.id}/documents ${response.status}`)
            }
            return (await response.json()) as KnowledgeDocument[]
          }),
        )

        if (ignore) {
          return
        }

        setKnowledgeBases(nextKnowledgeBases)
        setKnowledgeDocuments(documentLists.flat())
        setVectorStatus(nextVectorStatus)
        setActiveKnowledgeBaseId((current) => current || nextKnowledgeBases[0]?.id || '')
      } catch (error) {
        if (!ignore) {
          setKnowledgeError(error instanceof Error ? error.message : '知识库接口请求失败')
        }
      } finally {
        if (!ignore) {
          setKnowledgeLoading(false)
        }
      }
    }

    loadKnowledgeBases()

    return () => {
      ignore = true
    }
  }, [])

  useEffect(() => {
    let ignore = false

    async function loadMcpServers() {
      setMcpLoading(true)
      setMcpError('')

      try {
        const response = await fetch('/api/mcp/servers')
        if (!response.ok) {
          throw new Error(`GET /api/mcp/servers ${response.status}`)
        }
        const nextServers = (await response.json()) as McpServer[]
        if (ignore) {
          return
        }
        setMcpServers(nextServers)
        setSelectedMcpServerId((current) => current || nextServers[0]?.id || '')
      } catch (error) {
        if (!ignore) {
          setMcpError(error instanceof Error ? error.message : 'MCP 服务列表加载失败')
        }
      } finally {
        if (!ignore) {
          setMcpLoading(false)
        }
      }
    }

    loadMcpServers()

    return () => {
      ignore = true
    }
  }, [])

  function updateMessage(conversationId: string, messageId: number, updater: (message: Message) => Message) {
    setMessagesByConversation((current) => ({
      ...current,
      [conversationId]: (current[conversationId] ?? []).map((message) =>
        message.id === messageId ? updater(message) : message,
      ),
    }))
  }

  function mergeTrace(current: AgentTraceStep[] | undefined, incoming: AgentTraceStep[] | undefined) {
    const merged = [...(current ?? [])]
    for (const step of incoming ?? []) {
      const key = `${step.step}:${step.phase}:${step.action}:${step.observation}`
      const existingIndex = merged.findIndex((item) => `${item.step}:${item.phase}:${item.action}:${item.observation}` === key)
      if (existingIndex >= 0) {
        merged[existingIndex] = {
          ...merged[existingIndex],
          ...step,
          attributes: {
            ...(merged[existingIndex].attributes ?? {}),
            ...(step.attributes ?? {}),
          },
        }
      } else {
        merged.push(step)
      }
    }
    return merged
  }

  function feedbackQuestionFor(message: Message) {
    const explicitQuestion = message.feedbackQuestion?.trim()
    if (explicitQuestion) {
      return explicitQuestion
    }

    const conversationMessages = messagesByConversation[activeId] ?? messages
    const messageIndex = conversationMessages.findIndex((item) => item.id === message.id)
    for (let index = messageIndex - 1; index >= 0; index -= 1) {
      const candidate = conversationMessages[index]
      if (candidate.role === 'user' && candidate.content.trim()) {
        return candidate.content
      }
    }
    return ''
  }

  function feedbackKnowledgeBaseFor(message: Message) {
    return message.feedbackKnowledgeBaseId || activeKnowledgeBaseId || activeConversation.knowledgeBaseId || ''
  }

  async function submitMessageFeedback(message: Message, rating: FeedbackRating) {
    if (shouldSkipFeedback(message, rating)) {
      return
    }

    updateMessage(activeId, message.id, (current) => ({
      ...current,
      feedbackRating: rating,
      feedbackStatus: 'submitting',
      feedbackError: '',
    }))

    try {
      const response = await fetch('/api/feedback', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          conversationId: activeId,
          messageId: message.id,
          traceId: message.traceId ?? '',
          rating,
          comment: '',
          question: feedbackQuestionFor(message),
          answer: message.content,
          knowledgeBaseId: feedbackKnowledgeBaseFor(message),
          sourcesJson: JSON.stringify(message.citations?.length ? message.citations : message.sources ?? []),
        }),
      })
      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `POST /api/feedback ${response.status}`))
      }
      const record = (await response.json()) as FeedbackRecord
      updateMessage(activeId, message.id, (current) => ({
        ...current,
        feedbackRating: record.rating,
        feedbackStatus: 'submitted',
        feedbackError: '',
      }))
    } catch (error) {
      updateMessage(activeId, message.id, (current) => ({
        ...current,
        feedbackRating: rating,
        feedbackStatus: 'error',
        feedbackError: error instanceof Error ? error.message : '反馈提交失败',
      }))
    }
  }

  async function streamChat(
    endpoint: string,
    body: unknown,
    conversationId: string,
    assistantMessageId: number,
  ) {
    const completedResponses: AgentChatResponse[] = []
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    })
    if (!response.ok) {
      throw new Error(`POST ${endpoint} ${response.status}`)
    }

    await readChatStream(response, ({ event, data }) => {
      if (event === 'answer_delta' && typeof data === 'object' && data && 'content' in data) {
        const content = String((data as { content?: unknown }).content ?? '')
        updateMessage(conversationId, assistantMessageId, (message) => ({
          ...message,
          content: message.content + content,
          retrievalStatus: message.retrievalStatus
            ? { ...message.retrievalStatus, state: 'generating' }
            : message.retrievalStatus,
        }))
      } else if (event === 'answer_reset') {
        updateMessage(conversationId, assistantMessageId, (message) => ({
          ...message,
          content: '',
        }))
      } else if (event === 'trace_delta') {
        const step = data as AgentTraceStep
        updateMessage(conversationId, assistantMessageId, (message) => ({
          ...message,
          agentTrace: mergeTrace(message.agentTrace, [step]),
          retrievalStatus: message.retrievalStatus
            ? {
                ...message.retrievalStatus,
                state: message.retrievalStatus.state === 'generating' ? 'generating' : 'retrieving',
                toolName: step.toolName || message.retrievalStatus.toolName,
              }
            : message.retrievalStatus,
        }))
      } else if (event === 'metadata' && typeof data === 'object' && data) {
        const metadata = data as Partial<AgentChatResponse>
        updateMessage(conversationId, assistantMessageId, (message) => ({
          ...message,
          traceId: metadata.traceId,
          spanId: metadata.spanId,
          agentTrace: mergeTrace(message.agentTrace, metadata.agentTrace),
          retrievalStatus: message.retrievalStatus
            ? {
                ...message.retrievalStatus,
                state: 'generating',
                rewrittenQuery: metadata.rewrittenQuery || message.retrievalStatus.rewrittenQuery,
                toolName: metadata.toolName || message.retrievalStatus.toolName,
                finishReason: metadata.finishReason || message.retrievalStatus.finishReason,
              }
            : message.retrievalStatus,
        }))
      } else if (event === 'citations' && Array.isArray(data)) {
        const citations = data.filter(isChatCitation)
        updateMessage(conversationId, assistantMessageId, (message) => ({
          ...message,
          citations,
          retrievalStatus: statusFromCitations(message.retrievalStatus, citations),
        }))
      } else if (event === 'done') {
        completedResponses.push(data as AgentChatResponse)
      } else if (event === 'error') {
        const message =
          typeof data === 'object' && data && 'message' in data
            ? String((data as { message?: unknown }).message)
            : 'stream failed'
        throw new Error(message)
      }
    })

    const finalResponse = completedResponses[completedResponses.length - 1]
    if (!finalResponse) {
      throw new Error('Chat stream ended without a final response')
    }
    return finalResponse
  }

  async function readChatStream(response: Response, onEvent: (event: ChatStreamEvent) => void) {
    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('Streaming response body is empty')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const rawEvent = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const parsed = parseSseEvent(rawEvent)
        if (parsed) {
          onEvent(parsed)
        }
        boundary = buffer.indexOf('\n\n')
      }
    }
  }

  function parseSseEvent(rawEvent: string): ChatStreamEvent | null {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of rawEvent.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trimStart())
      }
    }
    if (dataLines.length === 0) {
      return null
    }
    const dataText = dataLines.join('\n')
    try {
      return { event, data: JSON.parse(dataText) }
    } catch {
      return { event, data: dataText }
    }
  }

  function sendMessage(event?: FormEvent<HTMLFormElement>, preset?: string) {
    event?.preventDefault()

    const text = (preset ?? draft).trim()
    if (!text || isStreaming) {
      return
    }
    const parsedCommand = parseSlashCommand(text)
    const commandName = parsedCommand.name
    const isMultiAgent = commandName === 'multi-agent'
    const isPlan = commandName === 'plan'
    const query = parsedCommand.query
    if (commandName !== 'status' && !query) {
      return
    }

    const nextUserMessage: Message = {
      id: nextMessageId.current++,
      role: 'user',
      content: text,
      agentMode: isMultiAgent ? 'multi-agent' : 'default',
      command: commandName,
      attachments: pendingAttachments.length > 0 ? [...pendingAttachments] : undefined,
    }

    const assistantMessageId = nextMessageId.current++
    let nextAssistantMessage: Message = {
      id: assistantMessageId,
      role: 'assistant',
      content: '正在处理请求...',
    }

    const attachmentsForRequest = pendingAttachments.length > 0 ? [...pendingAttachments] : undefined
    setDraft('')
    setPendingAttachments([])
    setMessagesByConversation((current) => ({
      ...current,
      [activeId]: [...(current[activeId] ?? []), nextUserMessage],
    }))

    if (commandName === 'status') {
      nextAssistantMessage = {
        id: assistantMessageId,
        role: 'assistant',
        content: statusMessage(),
        command: 'status',
      }
      setMessagesByConversation((current) => ({
        ...current,
        [activeId]: [...(current[activeId] ?? []), nextAssistantMessage],
      }))
      return
    }

    if (commandName === 'feedback') {
      const createdAt = new Date().toISOString()
      setFeedbackEntries((current) => [
        ...current,
        {
          id: Date.now(),
          conversationId: activeId,
          content: query,
          createdAt,
        },
      ])
      nextAssistantMessage = {
        id: assistantMessageId,
        role: 'assistant',
        content: `已记录反馈。\n\n${query}`,
        command: 'feedback',
      }
      setMessagesByConversation((current) => ({
        ...current,
        [activeId]: [...(current[activeId] ?? []), nextAssistantMessage],
      }))
      return
    }

    const streamingAssistantMessage: Message = {
      id: assistantMessageId,
      role: 'assistant',
      content: '',
      agentMode: isMultiAgent ? 'multi-agent' : 'default',
      command: commandName,
      agentTrace: [],
      retrievalStatus: {
        state: 'retrieving',
        query,
        hitCount: 0,
      },
      feedbackQuestion: query,
      feedbackKnowledgeBaseId: activeKnowledgeBaseId,
    }
    setMessagesByConversation((current) => ({
      ...current,
      [activeId]: [...(current[activeId] ?? []), streamingAssistantMessage],
    }))

    setIsStreaming(true)
    streamChat(
      isMultiAgent ? '/api/chat/multi-agent/stream' : '/api/chat/stream',
      {
        query: isPlan ? planQuery(query) : query,
        knowledgeBaseId: activeKnowledgeBaseId || undefined,
        conversationId: String(activeId),
        history: messages.map((message) => ({
          role: message.role,
          content: message.content,
        })),
        options: {
          topK: 6,
          retrievalMode: 'hybrid',
          queryExpansionEnabled: true,
          queryExpansionCount: 4,
          userId: USER_ID,
        },
        attachments: attachmentsForRequest,
      },
      activeId,
      assistantMessageId,
    )
      .then((chatResponse) => {
        const serverConversationId = chatResponse.conversationId || activeId
        const finalAssistantMessage: Message = {
          id: assistantMessageId,
          role: 'assistant',
          content: chatResponse.answer,
          agentMode: isMultiAgent ? 'multi-agent' : 'default',
          command: commandName,
          agentTrace: chatResponse.agentTrace,
          requestType: chatResponse.requestType,
          executionMode: chatResponse.executionMode,
          requiredCapabilities: chatResponse.requiredCapabilities,
          clarificationQuestion: chatResponse.clarificationQuestion,
          traceId: chatResponse.traceId,
          spanId: chatResponse.spanId,
          feedbackQuestion: query,
          feedbackKnowledgeBaseId: activeKnowledgeBaseId,
          citations: chatResponse.citations,
          retrievalStatus: statusFromCitations(
            {
              state: 'done',
              query,
              rewrittenQuery: chatResponse.rewrittenQuery,
              toolName: chatResponse.toolName,
              finishReason: chatResponse.finishReason,
              hitCount: 0,
            },
            chatResponse.citations,
            chatResponse.retrievalHits,
          ),
          sources:
            chatResponse.citations.length > 0
              ? chatResponse.citations.map(
                  (citation) =>
                    `${citation.documentName || `片段 ${citation.index}`} / chunk ${citation.chunkIndex} / ${citation.score.toFixed(3)}`,
                )
              : chatResponse.webSearchResults?.map((result) => `${result.title} / ${result.url}`),
        }
        updateMessage(activeId, assistantMessageId, () => finalAssistantMessage)
        setConversationList((current) =>
          current.map((item) =>
            item.id === activeId || item.id === serverConversationId
              ? {
                  ...item,
                  id: serverConversationId,
                  title:
                    item.title === DEFAULT_CONVERSATION_TITLE
                      ? conversationTitleFromQuery(query)
                      : item.title,
                  summary: chatResponse.answer.slice(0, 140),
                  time: '刚刚',
                  knowledgeBaseId: activeKnowledgeBaseId || item.knowledgeBaseId,
                  messageCount: (item.messageCount ?? messages.length) + 2,
                }
              : item,
          ),
        )
      })
      .catch((error) => {
        const errorAssistantMessage: Message = {
          id: assistantMessageId,
          role: 'assistant',
          content: error instanceof Error ? `Agent 问答失败：${error.message}` : 'Agent 问答失败',
          agentMode: isMultiAgent ? 'multi-agent' : 'default',
          command: commandName,
          retrievalStatus: {
            state: 'error',
            query,
            hitCount: 0,
            error: error instanceof Error ? error.message : 'Agent 问答失败',
          },
        }
        updateMessage(activeId, assistantMessageId, () => errorAssistantMessage)
      })
      .finally(() => {
        setIsStreaming(false)
      })
  }

  async function uploadDocument(file: File) {
    if (!uploadKnowledgeBaseId) {
      setKnowledgeError('后端还没有可用知识库，无法上传文档')
      return
    }

    const formData = new FormData()
    formData.append('file', file)
    setKnowledgeLoading(true)
    setKnowledgeError('')

    try {
      const response = await fetch(`/api/knowledge-bases/${uploadKnowledgeBaseId}/documents`, {
        method: 'POST',
        body: formData,
      })

      if (!response.ok) {
        throw new Error(`POST /api/knowledge-bases/${uploadKnowledgeBaseId}/documents ${response.status}`)
      }

      const nextDocument = (await response.json()) as KnowledgeDocument
      setKnowledgeDocuments((current) => [nextDocument, ...current])
      setKnowledgeBases((current) =>
        current.map((item) =>
          item.id === uploadKnowledgeBaseId
            ? { ...item, documentCount: item.documentCount + 1, updatedAt: nextDocument.uploadedAt }
            : item,
        ),
      )
    } catch (error) {
      setKnowledgeError(error instanceof Error ? error.message : '上传文件失败')
    } finally {
      setKnowledgeLoading(false)
      if (uploadInputRef.current) {
        uploadInputRef.current.value = ''
      }
    }
  }

  async function deleteDocument(document: KnowledgeDocument) {
    const confirmed = window.confirm(`确定删除「${document.fileName}」吗？`)
    if (!confirmed) {
      return
    }

    setDeletingDocumentId(document.id)
    setKnowledgeError('')

    try {
      const response = await fetch(`/api/knowledge-bases/${document.knowledgeBaseId}/documents/${document.id}`, {
        method: 'DELETE',
      })

      if (!response.ok) {
        throw new Error(`DELETE /api/knowledge-bases/${document.knowledgeBaseId}/documents/${document.id} ${response.status}`)
      }

      setKnowledgeDocuments((current) => current.filter((item) => item.id !== document.id))
      setKnowledgeBases((current) =>
        current.map((item) =>
          item.id === document.knowledgeBaseId
            ? {
                ...item,
                documentCount: Math.max(0, item.documentCount - 1),
                chunkCount: Math.max(0, item.chunkCount - document.chunkCount),
              }
            : item,
        ),
      )
    } catch (error) {
      setKnowledgeError(error instanceof Error ? error.message : '删除文档失败')
    } finally {
      setDeletingDocumentId('')
    }
  }

  function formatSize(size: number) {
    if (size < 1024) {
      return `${size} B`
    }
    if (size < 1024 * 1024) {
      return `${(size / 1024).toFixed(1)} KB`
    }

    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }

  function isImageDocument(document: KnowledgeDocument) {
    return document.contentType.toLowerCase().startsWith('image/')
  }

  async function openDocumentPreview(document: KnowledgeDocument) {
    if (previewDocument?.id === document.id) {
      closeDocumentPreview()
      return
    }
    setPreviewDocument(document)
    setPreviewChunks([])
    setPreviewError('')
    setPreviewLoading(true)
    try {
      const response = await fetch(
        `/api/knowledge-bases/${document.knowledgeBaseId}/documents/${document.id}/chunks`,
      )
      if (!response.ok) {
        throw new Error(`GET /chunks ${response.status}`)
      }
      const chunks = (await response.json()) as { id: string; content: string }[]
      setPreviewChunks(chunks)
    } catch (error) {
      setPreviewError(error instanceof Error ? error.message : '加载分块失败')
    } finally {
      setPreviewLoading(false)
    }
  }

  function closeDocumentPreview() {
    setPreviewDocument(null)
    setPreviewChunks([])
    setPreviewError('')
    setPreviewLoading(false)
  }

  async function handleComposerUploadChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (event.target) {
      event.target.value = ''
    }
    if (!file) return

    if (!isAllowedAttachmentType(file)) {
      window.alert(`Cannot attach ${file.name}. Only TXT, Markdown, CSV, and JSON files are supported.`)
      return
    }

    if (file.size > MAX_ATTACHMENT_SIZE) {
      window.alert(`${file.name} exceeds the 1 MB attachment limit.`)
      return
    }

    if (pendingAttachments.length >= MAX_ATTACHMENT_COUNT) {
      window.alert(`A message can include at most ${MAX_ATTACHMENT_COUNT} attachments.`)
      return
    }

    setKnowledgeError('')
    try {
      const attachment = await readAttachment(file)
      setPendingAttachments((current) => [...current, attachment])
    } catch (error) {
      window.alert(`Failed to read ${file.name}: ${error instanceof Error ? error.message : 'unknown error'}`)
    }
  }

  function removePendingAttachment(index: number) {
    setPendingAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))
  }

  async function apiErrorMessage(response: Response, fallback: string) {
    try {
      const body = (await response.json()) as { message?: string }
      return body.message || fallback
    } catch {
      return fallback
    }
  }

  const appClassName = [
    'app-shell',
    activeView === 'knowledge' || activeView === 'mcp' || activeView === 'search' ? 'library-mode' : '',
    inspectorOpen ? '' : 'inspector-collapsed',
  ]
    .filter(Boolean)
    .join(' ')

  async function saveMcpServer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setMcpLoading(true)
    setMcpError('')

    try {
      const response = await fetch(mcpForm.id ? `/api/mcp/servers/${mcpForm.id}` : '/api/mcp/servers', {
        method: mcpForm.id ? 'PUT' : 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          id: mcpForm.id || undefined,
          name: mcpForm.name,
          transport: mcpForm.transport,
          endpoint: mcpForm.endpoint,
          command: mcpForm.command,
          args: cleanMcpArgs(mcpForm.args),
          environment: mcpEnvironmentRecord(mcpForm.envVars),
          workingDirectory: mcpForm.workingDirectory,
          bearerToken: mcpForm.bearerToken || undefined,
          enabled: mcpForm.enabled,
        }),
      })

      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `${mcpForm.id ? 'PUT' : 'POST'} /api/mcp/servers ${response.status}`))
      }

      const savedServer = (await response.json()) as McpServer
      setMcpServers((current) => {
        const withoutSaved = current.filter((server) => server.id !== savedServer.id)
        return [...withoutSaved, savedServer].sort((left, right) => left.name.localeCompare(right.name))
      })
      setSelectedMcpServerId(savedServer.id)
      setMcpForm(emptyMcpForm())
    } catch (error) {
      setMcpError(error instanceof Error ? error.message : 'MCP 服务保存失败')
    } finally {
      setMcpLoading(false)
    }
  }

  async function deleteMcpServer(server: McpServer) {
    const confirmed = window.confirm(`确定删除 MCP 服务「${server.name}」吗？`)
    if (!confirmed) {
      return
    }
    setMcpLoading(true)
    setMcpError('')

    try {
      const response = await fetch(`/api/mcp/servers/${server.id}`, {
        method: 'DELETE',
      })
      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `DELETE /api/mcp/servers/${server.id} ${response.status}`))
      }
      setMcpServers((current) => current.filter((item) => item.id !== server.id))
      setSelectedMcpServerId((current) => (current === server.id ? '' : current))
    } catch (error) {
      setMcpError(error instanceof Error ? error.message : 'MCP 服务删除失败')
    } finally {
      setMcpLoading(false)
    }
  }

  async function refreshMcpServer(server: McpServer) {
    setRefreshingMcpId(server.id)
    setMcpError('')

    try {
      const response = await fetch(`/api/mcp/servers/${server.id}/refresh`, {
        method: 'POST',
      })
      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `POST /api/mcp/servers/${server.id}/refresh ${response.status}`))
      }
      const refreshedServer = (await response.json()) as McpServer
      setMcpServers((current) => current.map((item) => (item.id === refreshedServer.id ? refreshedServer : item)))
      setSelectedMcpServerId(refreshedServer.id)
    } catch (error) {
      setMcpError(error instanceof Error ? error.message : 'MCP 工具刷新失败')
    } finally {
      setRefreshingMcpId('')
    }
  }

  async function callMcpTool(server: McpServer, tool: McpTool) {
    const key = `${server.id}.${tool.name}`
    setCallingMcpTool(key)
    setMcpError('')
    setMcpCallResult(null)

    try {
      const rawArguments = mcpToolArguments[key]?.trim() || '{}'
      const parsedArguments = JSON.parse(rawArguments) as Record<string, unknown>
      const normalizedArguments = normalizeToolArguments(tool, parsedArguments)
      validateToolArguments(tool, normalizedArguments)
      const normalizedArgumentsText = JSON.stringify(normalizedArguments, null, 2)
      if (normalizedArgumentsText !== rawArguments) {
        setMcpToolArguments((current) => ({ ...current, [key]: normalizedArgumentsText }))
      }
      const response = await fetch(`/api/mcp/servers/${server.id}/tools/${encodeURIComponent(tool.name)}/call`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ arguments: normalizedArguments }),
      })

      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `POST /api/mcp/servers/${server.id}/tools/${tool.name}/call ${response.status}`))
      }

      setMcpCallResult((await response.json()) as McpCallResult)
    } catch (error) {
      setMcpError(error instanceof Error ? error.message : 'MCP 工具调用失败')
    } finally {
      setCallingMcpTool('')
    }
  }

  function editMcpServer(server: McpServer) {
    setMcpForm({
      id: server.id,
      name: server.name,
      transport: server.transport || 'streamable_http',
      endpoint: server.endpoint,
      command: server.command || '',
      args: server.args && server.args.length > 0 ? server.args : [''],
      envVars:
        server.environment && Object.keys(server.environment).length > 0
          ? Object.entries(server.environment).map(([key, value]) => ({ key, value }))
          : [{ key: '', value: '' }],
      workingDirectory: server.workingDirectory || '',
      bearerToken: '',
      enabled: server.enabled,
    })
    setSelectedMcpServerId(server.id)
  }

  async function toggleMcpServer(server: McpServer) {
    setMcpError('')
    try {
      const response = await fetch(`/api/mcp/servers/${server.id}`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          name: server.name,
          transport: server.transport,
          endpoint: server.endpoint,
          command: server.command,
          args: server.args,
          environment: server.environment,
          workingDirectory: server.workingDirectory,
          enabled: !server.enabled,
        }),
      })
      if (!response.ok) {
        throw new Error(await apiErrorMessage(response, `PUT /api/mcp/servers/${server.id} ${response.status}`))
      }
      const updatedServer = (await response.json()) as McpServer
      setMcpServers((current) => current.map((item) => (item.id === updatedServer.id ? updatedServer : item)))
    } catch (error) {
      setMcpError(error instanceof Error ? error.message : 'MCP 服务状态更新失败')
    }
  }

  function formatSchema(schema: unknown) {
    if (!schema) {
      return '{}'
    }
    return JSON.stringify(schema, null, 2)
  }

  function normalizeToolArguments(tool: McpTool, argumentsValue: Record<string, unknown>) {
    if (!isJsonObject(tool.inputSchema) || !isJsonObject(tool.inputSchema.properties)) {
      return argumentsValue
    }

    const canonicalNames = Object.keys(tool.inputSchema.properties)
    const canonicalByNormalizedName = new Map(
      canonicalNames.map((name) => [normalizeArgumentName(name), name] as const),
    )
    const normalizedArguments: Record<string, unknown> = {}

    for (const [name, value] of Object.entries(argumentsValue)) {
      const exactName = canonicalNames.includes(name) ? name : undefined
      const canonicalName = exactName ?? canonicalByNormalizedName.get(normalizeArgumentName(name)) ?? name
      if (!(canonicalName in normalizedArguments) || exactName) {
        normalizedArguments[canonicalName] = value
      }
    }

    return normalizedArguments
  }

  function validateToolArguments(tool: McpTool, argumentsValue: Record<string, unknown>) {
    if (!isJsonObject(tool.inputSchema)) {
      return
    }

    const schema = tool.inputSchema
    const requiredNames = Array.isArray(schema.required)
      ? schema.required.filter((item): item is string => typeof item === 'string')
      : []
    const missingNames = requiredNames.filter((name) => {
      const value = argumentsValue[name] ?? argumentValueByCanonicalName(argumentsValue, name)
      if (value !== undefined && value !== null && value !== '') {
        argumentsValue[name] = value
      }
      return value === undefined || value === null || value === ''
    })
    if (missingNames.length > 0) {
      throw new Error(`MCP 参数缺少必填字段：${missingNames.join(', ')}`)
    }

    if (schema.additionalProperties === false && isJsonObject(schema.properties)) {
      const allowedNames = new Set(Object.keys(schema.properties))
      const extraNames = Object.keys(argumentsValue).filter((name) => !allowedNames.has(name))
      if (extraNames.length > 0) {
        throw new Error(
          `MCP 参数字段名不匹配：${extraNames.join(', ')}。可用字段：${[...allowedNames].join(', ') || '无'}`,
        )
      }
    }
  }

  function argumentValueByCanonicalName(argumentsValue: Record<string, unknown>, canonicalName: string) {
    const normalizedCanonicalName = normalizeArgumentName(canonicalName)
    const matchedEntry = Object.entries(argumentsValue).find(
      ([name]) => normalizeArgumentName(name) === normalizedCanonicalName,
    )
    return matchedEntry?.[1]
  }

  function formatToolArgumentsExample(tool: McpTool) {
    return JSON.stringify(toolArgumentsExample(tool), null, 2)
  }

  function toolArgumentsExample(tool: McpTool): JsonObject {
    const schemaExample = schemaValueExample('', tool.inputSchema)
    if (isJsonObject(schemaExample)) {
      return schemaExample
    }
    return fallbackToolArgumentsExample(tool)
  }

  function schemaValueExample(name: string, schema: unknown): unknown {
    if (!isJsonObject(schema)) {
      return undefined
    }

    if ('example' in schema) {
      return schema.example
    }
    if (Array.isArray(schema.examples) && schema.examples.length > 0) {
      return schema.examples[0]
    }
    if ('default' in schema) {
      return schema.default
    }
    if ('const' in schema) {
      return schema.const
    }
    if (Array.isArray(schema.enum) && schema.enum.length > 0) {
      return schema.enum[0]
    }

    const objectExample = objectSchemaExample(schema)
    if (objectExample) {
      return objectExample
    }

    const variants = [schema.oneOf, schema.anyOf, schema.allOf]
      .filter(Array.isArray)
      .flat() as unknown[]
    for (const variant of variants) {
      const value = schemaValueExample(name, variant)
      if (value !== undefined) {
        return value
      }
    }

    if (schema.type === 'array') {
      const itemExample = schemaValueExample(name, schema.items)
      return [itemExample ?? stringExampleForName(name)]
    }
    if (schema.type === 'integer') {
      return numberExampleForName(name)
    }
    if (schema.type === 'number') {
      return numberExampleForName(name)
    }
    if (schema.type === 'boolean') {
      return true
    }
    if (schema.type === 'string') {
      return stringExampleForName(name, schema.format)
    }

    return undefined
  }

  function objectSchemaExample(schema: JsonObject) {
    if (!isJsonObject(schema.properties)) {
      return undefined
    }

    const properties = schema.properties
    const requiredNames = Array.isArray(schema.required)
      ? schema.required.filter((item): item is string => typeof item === 'string')
      : []
    const propertyNames = Object.keys(properties)
    if (propertyNames.length === 0) {
      return undefined
    }

    const orderedNames = [
      ...requiredNames.filter((name) => propertyNames.includes(name)),
      ...propertyNames.filter((name) => !requiredNames.includes(name)),
    ]

    return Object.fromEntries(
      orderedNames.map((propertyName) => [
        propertyName,
        schemaValueExample(propertyName, properties[propertyName]) ?? stringExampleForName(propertyName),
      ]),
    )
  }

  function fallbackToolArgumentsExample(tool: McpTool): JsonObject {
    const descriptor = `${tool.name} ${tool.title ?? ''} ${tool.description ?? ''}`.toLowerCase()
    if (descriptor.includes('annual') || descriptor.includes('leave') || descriptor.includes('年假')) {
      return { userId: 'U1001' }
    }
    if (descriptor.includes('order') || descriptor.includes('订单')) {
      return { orderNo: 'ORD-20260628-0001' }
    }
    if (descriptor.includes('knowledge') || descriptor.includes('search') || descriptor.includes('知识')) {
      return { query: '退款流程是什么？', topK: 5 }
    }
    if (descriptor.includes('query') || descriptor.includes('question')) {
      return { query: '订单状态' }
    }
    return {}
  }

  function stringExampleForName(name: string, format?: unknown) {
    const normalizedName = name.toLowerCase()
    if (format === 'date') {
      return '2026-06-28'
    }
    if (format === 'date-time') {
      return '2026-06-28T09:00:00+08:00'
    }
    if (format === 'email') {
      return 'user@example.com'
    }
    if (normalizedName.includes('order') || normalizedName.includes('订单')) {
      return 'ORD-20260628-0001'
    }
    if (normalizedName.includes('user') || normalizedName.includes('employee') || normalizedName.includes('用户')) {
      return 'U1001'
    }
    if (normalizedName.includes('query') || normalizedName.includes('question') || normalizedName.includes('keyword')) {
      return '退款流程是什么？'
    }
    return 'string'
  }

  function numberExampleForName(name: string) {
    const normalizedName = name.toLowerCase()
    if (normalizedName.includes('top') || normalizedName.includes('limit') || normalizedName.includes('size')) {
      return 5
    }
    return 1
  }

  function normalizeArgumentName(name: string) {
    return name.replace(/[^a-zA-Z0-9]/g, '').toLowerCase()
  }

  function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
  }

  function emptyMcpForm(): McpForm {
    return {
      id: '',
      name: '',
      transport: 'streamable_http',
      endpoint: '',
      command: '',
      args: [''],
      envVars: [{ key: '', value: '' }],
      workingDirectory: '',
      bearerToken: '',
      enabled: true,
    }
  }

  function cleanMcpArgs(args: string[]) {
    return args.map((value) => value.trim()).filter(Boolean)
  }

  function mcpEnvironmentRecord(envVars: Array<{ key: string; value: string }>) {
    return Object.fromEntries(
      envVars
        .map((item) => [item.key.trim(), item.value] as const)
        .filter(([key]) => key.length > 0),
    )
  }

  function updateMcpArg(index: number, value: string) {
    setMcpForm((current) => ({
      ...current,
      args: current.args.map((item, itemIndex) => (itemIndex === index ? value : item)),
    }))
  }

  function removeMcpArg(index: number) {
    setMcpForm((current) => ({
      ...current,
      args: current.args.length > 1 ? current.args.filter((_, itemIndex) => itemIndex !== index) : [''],
    }))
  }

  function updateMcpEnv(index: number, patch: Partial<{ key: string; value: string }>) {
    setMcpForm((current) => ({
      ...current,
      envVars: current.envVars.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)),
    }))
  }

  function removeMcpEnv(index: number) {
    setMcpForm((current) => ({
      ...current,
      envVars: current.envVars.length > 1 ? current.envVars.filter((_, itemIndex) => itemIndex !== index) : [{ key: '', value: '' }],
    }))
  }

  function parseSlashCommand(value: string): ParsedSlashCommand {
    if (!value.startsWith('/')) {
      return { query: value }
    }

    const [rawCommand = '', ...rest] = value.split(/\s+/)
    const command = rawCommand.slice(1).toLowerCase()
    const query = rest.join(' ').trim()
    if (isSlashCommand(command)) {
      return { name: command, query }
    }
    return { query: value }
  }

  function isSlashCommand(value: string): value is SlashCommandName {
    return ['multi-agent', 'plan', 'status', 'feedback'].includes(value)
  }

  function planQuery(query: string) {
    return [
      '请先进入计划模式，只输出可执行计划，不要直接执行或假装已经完成。',
      '计划需要包含：目标、关键步骤、接口/数据流影响、测试验证、假设与风险。',
      '',
      `用户目标：${query}`,
    ].join('\n')
  }

  function statusMessage() {
    const activeServer = activeMcpServer ? `${activeMcpServer.name} (${activeMcpServer.status})` : '未选择'
    return [
      '当前状态',
      '',
      `- 会话：${activeConversation.title}`,
      `- 知识库：${activeKnowledgeBase?.name ?? knowledgeBases[0]?.name ?? '未选择'}`,
      `- 文档：${totalDocumentCount} 个，分块：${totalChunkCount} 个`,
      `- 向量：${vectorStatus ? `${vectorStatus.vectorCount} 条 / ${vectorStatus.storeProvider}` : '未加载'}`,
      `- MCP：${mcpServers.length} 个服务，${onlineMcpCount} 个在线，${mcpToolCount} 个工具`,
      `- 当前 MCP：${activeServer}`,
      `- 已记录反馈：${feedbackEntries.length} 条`,
    ].join('\n')
  }

  function setSlashCommand(command: SlashCommandName) {
    const value = draft.trimStart()
    const parsed = parseSlashCommand(value)
    const nextQuery = parsed.name ? parsed.query : draft
    setDraft(`/${command} ${nextQuery}`.trimEnd())
  }

  function applySlashCommand(command: SlashCommandName) {
    setSlashCommand(command)
  }

  function traceLabel(step: AgentTraceStep) {
    if (step.attributes?.durationScope === 'graph_total') {
      return [step.phase, '图总耗时', step.toolName || step.route].filter(Boolean).join(' / ')
    }
    return [step.phase, step.action, step.toolName || step.route].filter(Boolean).join(' / ')
  }

  function traceMeta(step: AgentTraceStep) {
    const items = []
    if (step.status) {
      items.push(step.status)
    }
    if (step.attributes?.durationScope === 'graph_total') {
      items.push('图执行总计')
    }
    if (typeof step.durationMs === 'number' && step.durationMs >= 0) {
      items.push(`${step.durationMs} ms`)
    }
    return items.join(' · ')
  }

  async function openTraceReplay(traceId: string) {
    if (!traceId) {
      return
    }
    setTraceReplay({ traceId, loading: true, error: '' })
    setTraceReplaySort('sequence')
    try {
      const response = await fetch(`/api/traces/${encodeURIComponent(traceId)}`)
      if (!response.ok) {
        throw new Error(`GET /api/traces/${traceId} ${response.status}`)
      }
      const records = (await response.json()) as AgentTraceRecord[]
      const record = records[0]
      if (!record) {
        throw new Error('未找到该链路的持久化记录。')
      }
      setTraceReplay({ traceId, loading: false, error: '', record })
    } catch (error) {
      setTraceReplay({
        traceId,
        loading: false,
        error: error instanceof Error ? error.message : '链路回放加载失败',
      })
    }
  }

  function commandLabel(command?: SlashCommandName) {
    if (command === 'multi-agent') {
      return 'Multi-Agent'
    }
    if (command === 'plan') {
      return 'Plan'
    }
    if (command === 'status') {
      return 'Status'
    }
    if (command === 'feedback') {
      return 'Feedback'
    }
    return ''
  }

  const draftCommandText = draft.trimStart()
  const draftCommandComplete = /^\/(multi-agent|plan|status|feedback)\s/.test(draftCommandText.toLowerCase())
  const showCommandMenu = draftCommandText.startsWith('/') && !draftCommandComplete

  return (
    <main className={appClassName}>
      <aside className="sidebar" aria-label="会话与知识库">
        <div className="brand-row">
          <div className="brand-mark">
            <Sparkles size={18} />
          </div>
          <div>
            <strong>RAG Agent</strong>
            <span>Knowledge Workspace</span>
          </div>
          <button className="icon-button quiet" type="button" title="折叠侧栏">
            <PanelRight size={17} />
          </button>
        </div>

        <nav className="sidebar-menu" aria-label="主导航">
          <button
            className={activeView === 'chat' ? 'active' : ''}
            onClick={() => {
              if (activeView === 'chat') {
                createConversation()
              } else {
                setActiveView('chat')
              }
            }}
            type="button"
          >
            <MessageSquarePlus size={18} />
            新建对话
          </button>
          <button
            className={activeView === 'search' ? 'active' : ''}
            onClick={() => setActiveView('search')}
            type="button"
          >
            <Search size={18} />
            搜索对话
          </button>
          <button
            className={activeView === 'knowledge' ? 'active' : ''}
            onClick={() => setActiveView('knowledge')}
            type="button"
          >
            <Database size={18} />
            知识库
          </button>
          <button
            className={activeView === 'mcp' ? 'active' : ''}
            onClick={() => setActiveView('mcp')}
            type="button"
          >
            <Plug size={18} />
            MCP 管理
          </button>
        </nav>

        <section className="recent-section">
          {conversationError && <p className="conversation-error">{conversationError}</p>}
          <h2>最新</h2>
          <nav className="recent-list" aria-label="最近对话">
            {visibleConversations.map((item) => (
              <div className={item.id === activeId ? 'recent-item active conversation-row' : 'recent-item conversation-row'} key={item.id}>
                <button
                  className="conversation-open-button"
                  onClick={() => {
                    setActiveId(item.id)
                    setActiveView('chat')
                  }}
                  type="button"
                >
                  <span>{item.title}</span>
                  <small>{item.summary || `${item.messageCount ?? 0} messages`}</small>
                </button>
                <div className="conversation-row-actions">
                  <button
                    aria-label={item.pinned ? '取消置顶' : '置顶'}
                    onClick={() => updateConversation(item.id, { pinned: !item.pinned })}
                    type="button"
                  >
                    <Pin size={13} />
                  </button>
                  <button
                    aria-label={item.archived ? '取消归档' : '归档'}
                    onClick={() => updateConversation(item.id, { archived: !item.archived })}
                    type="button"
                  >
                    <Archive size={13} />
                  </button>
                  <button
                    aria-label="删除"
                    onClick={() => updateConversation(item.id, { deleted: true })}
                    type="button"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              </div>
            ))}
            {visibleConversations.length === 0 && (
              <div className="conversation-empty">
                {conversationLoading ? '加载会话中...' : '没有匹配的会话'}
              </div>
            )}
          </nav>
        </section>
      </aside>

      {activeView === 'knowledge' && (
        <section className="library-page" aria-label="知识库">
          <div className="library-container">
            <header className="library-header">
              <h1>知识库</h1>
              <div className="library-actions">
                <select
                  aria-label="选择知识库"
                  className="library-knowledge-select"
                  onChange={(event) => setActiveKnowledgeBaseId(event.target.value)}
                  value={activeKnowledgeBaseId}
                >
                  {knowledgeBases.length === 0 ? (
                    <option value="">暂无知识库</option>
                  ) : (
                    knowledgeBases.map((knowledgeBase) => (
                      <option key={knowledgeBase.id} value={knowledgeBase.id}>
                        {knowledgeBase.name}
                      </option>
                    ))
                  )}
                </select>
                <label className="library-search">
                  <Search size={15} />
                  <input
                    aria-label="搜索资料"
                    onChange={(event) => setKnowledgeSearch(event.target.value)}
                    placeholder="搜索"
                    value={knowledgeSearch}
                  />
                </label>
                <button
                  className="library-new-button"
                  disabled={knowledgeLoading || !uploadKnowledgeBaseId}
                  onClick={() => uploadInputRef.current?.click()}
                  type="button"
                >
                  <Plus size={15} />
                  {knowledgeLoading ? '处理中' : '添加文档'}
                </button>
              </div>
            </header>

            <div className="library-summary" aria-label="知识库总览">
              <div>
                <span>文档总数</span>
                <strong>{totalDocumentCount}</strong>
              </div>
              <div>
                <span>分块总数</span>
                <strong>{totalChunkCount}</strong>
              </div>
              <div>
                <span>向量数量</span>
                <strong>{vectorStatus?.vectorCount ?? '-'}</strong>
              </div>
              <div>
                <span>默认上传库</span>
                <strong>{activeKnowledgeBase?.name ?? knowledgeBases[0]?.name ?? '-'}</strong>
              </div>
            </div>

            <div className="library-toolbar">
              <div className="library-tabs" aria-label="资料类型">
                <button
                  className={libraryTypeFilter === 'all' ? 'active' : ''}
                  onClick={() => setLibraryTypeFilter('all')}
                  type="button"
                >
                  全部
                </button>
                <button
                  className={libraryTypeFilter === 'image' ? 'active' : ''}
                  onClick={() => setLibraryTypeFilter('image')}
                  type="button"
                >
                  图片
                </button>
                <button
                  className={libraryTypeFilter === 'file' ? 'active' : ''}
                  onClick={() => setLibraryTypeFilter('file')}
                  type="button"
                >
                  文件
                </button>
              </div>
              <div className="library-view-tools" aria-label="视图设置">
                <button
                  className={libraryFilterOpen ? 'active' : ''}
                  onClick={() => setLibraryFilterOpen((open) => !open)}
                  title="筛选"
                  type="button"
                >
                  <SlidersHorizontal size={17} />
                </button>
                <span />
                <button
                  className={libraryViewMode === 'grid' ? 'active' : ''}
                  onClick={() => setLibraryViewMode('grid')}
                  title="网格视图"
                  type="button"
                >
                  <Grid2X2 size={17} />
                </button>
                <button
                  className={libraryViewMode === 'list' ? 'active' : ''}
                  onClick={() => setLibraryViewMode('list')}
                  title="列表视图"
                  type="button"
                >
                  <List size={18} />
                </button>
              </div>
            </div>

            <div className="backend-status">
              <span>后端：127.0.0.1:28081</span>
              <span>向量：{vectorStatus ? `${vectorStatus.storeProvider} · ${vectorStatus.vectorCount} vectors` : '-'}</span>
              <span>知识库：{knowledgeBases.length} 个源库已合并显示</span>
            </div>

            {knowledgeError && <div className="library-error">{knowledgeError}</div>}

            <div className="library-table-head">
              <span>名称</span>
              <span>
                修改时间
                <ArrowDown size={13} />
              </span>
              <span>大小</span>
              <span>状态</span>
              <span>操作</span>
            </div>

            {filteredKnowledgeDocuments.length > 0 ? (
              <>
                {libraryViewMode === 'grid' ? (
                  <div className="document-grid">
                    {filteredKnowledgeDocuments.map((document) => (
                      <button
                        className={`document-card${previewDocument?.id === document.id ? ' selected' : ''}`}
                        key={document.id}
                        onClick={() => openDocumentPreview(document)}
                        type="button"
                      >
                        <span className="document-card-icon">
                          {isImageDocument(document) ? <ImageIcon size={22} /> : <FileText size={22} />}
                        </span>
                        <span className="document-card-meta">
                          <strong>{document.fileName}</strong>
                          <small>
                            {formatSize(document.size)} · {document.chunkCount} 分块 · {document.status}
                          </small>
                        </span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="document-list">
                    {filteredKnowledgeDocuments.map((document) => (
                      <Fragment key={document.id}>
                        <div
                          className={`document-row${previewDocument?.id === document.id ? ' selected' : ''}`}
                          onClick={() => openDocumentPreview(document)}
                          role="button"
                          tabIndex={0}
                        >
                          <span className="document-name">
                            {isImageDocument(document) ? <ImageIcon size={17} /> : <FileText size={17} />}
                            <span>
                              <strong>{document.fileName}</strong>
                              <small>
                                {document.knowledgeBaseId} · {document.chunkCount} 分块
                              </small>
                            </span>
                          </span>
                          <span>{formatDate(document.parsedAt || document.uploadedAt)}</span>
                          <span>{formatSize(document.size)}</span>
                          <em>{document.status}</em>
                          <button
                            className="document-delete-button"
                            disabled={deletingDocumentId === document.id}
                            onClick={(event) => {
                              event.stopPropagation()
                              deleteDocument(document)
                            }}
                            type="button"
                          >
                            <Trash2 size={15} />
                            {deletingDocumentId === document.id ? '删除中' : '删除'}
                          </button>
                        </div>
                        {previewDocument?.id === document.id && (
                          <div className="document-preview">
                            <div className="document-preview-head">
                              <strong>{previewDocument.fileName} · 分块预览</strong>
                              <button onClick={closeDocumentPreview} type="button">
                                收起
                              </button>
                            </div>
                            {previewLoading && <p className="document-preview-empty">加载中…</p>}
                            {previewError && <p className="document-preview-error">{previewError}</p>}
                            {!previewLoading && !previewError && previewChunks.length === 0 && (
                              <p className="document-preview-empty">该文档暂无可预览的分块。</p>
                            )}
                            {previewChunks.length > 0 && (
                              <ol className="document-preview-chunks">
                                {previewChunks.map((chunk, index) => (
                                  <li key={chunk.id}>
                                    <span>#{index + 1}</span>
                                    <p>{chunk.content}</p>
                                  </li>
                                ))}
                              </ol>
                            )}
                          </div>
                        )}
                      </Fragment>
                    ))}
                  </div>
                )}
                <button
                  className="inline-upload-button"
                  disabled={knowledgeLoading || !uploadKnowledgeBaseId}
                  onClick={() => uploadInputRef.current?.click()}
                  type="button"
                >
                  <Upload size={17} />
                  上传文件
                </button>
              </>
            ) : (
              <div className="upload-dropzone">
                <Upload size={26} />
                <button disabled={knowledgeLoading || !uploadKnowledgeBaseId} onClick={() => uploadInputRef.current?.click()} type="button">
                  {knowledgeSearch ? '没有匹配文档' : knowledgeLoading ? '连接中...' : '上传文件'}
                </button>
              </div>
            )}

            <input
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0]
                if (file) {
                  uploadDocument(file)
                }
              }}
              ref={uploadInputRef}
              type="file"
            />
          </div>
        </section>
      )}

      {activeView === 'search' && (
        <section className="search-page" aria-label="搜索对话">
          <div className="search-container">
            <div className="search-input-wrapper">
              <Search size={20} />
              <input
                aria-label="搜索历史对话"
                autoFocus
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="搜索历史对话…"
                type="search"
                value={searchQuery}
              />
            </div>
            <button
              className={showArchivedConversations ? 'search-archive-toggle active' : 'search-archive-toggle'}
              onClick={() => setShowArchivedConversations((value) => !value)}
              type="button"
            >
              <Archive size={15} />
              {showArchivedConversations ? '查看活跃' : '查看归档'}
            </button>

            {searchQuery.trim() === '' ? (
              <div className="search-empty">
                <Search size={40} />
                <p>输入关键词搜索历史会话</p>
              </div>
            ) : filteredSearchResults.length === 0 ? (
              <div className="search-empty">
                <p>未找到相关对话</p>
              </div>
            ) : (
              <div className="search-results">
                {filteredSearchResults.map(({ conversation, matchedMessages }) => (
                  <article className="search-result-card" key={conversation.id}>
                    <button
                      className="search-result-header"
                      onClick={() => {
                        setActiveId(conversation.id)
                        setActiveView('chat')
                        setScrollToMessageId(null)
                      }}
                      type="button"
                    >
                      <h3>{conversation.title}</h3>
                      <span>{conversation.time}</span>
                    </button>
                    <p className="search-result-summary">{conversation.summary}</p>
                    {matchedMessages.length > 0 && (
                      <ul className="search-result-messages">
                        {matchedMessages.map((message) => (
                          <li key={message.id}>
                            <button
                              onClick={() => {
                                setActiveId(conversation.id)
                                setActiveView('chat')
                                setScrollToMessageId(message.id)
                              }}
                              type="button"
                            >
                              <span className="search-result-role">
                                {message.role === 'user' ? '用户' : '助手'}
                              </span>
                              <span className="search-result-snippet">
                                {message.content.slice(0, 120)}
                                {message.content.length > 120 ? '…' : ''}
                              </span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </article>
                ))}
              </div>
            )}
          </div>
        </section>
      )}

      {activeView === 'mcp' && (
        <section className="mcp-page" aria-label="MCP 管理">
          <div className="mcp-container">
            <header className="library-header">
              <div>
                <span className="eyebrow">Model Context Protocol</span>
                <h1>MCP 管理</h1>
                <p className="mcp-header-copy">连接外部工具和数据源，统一管理服务启用状态、工具发现和测试调用。</p>
              </div>
            </header>

            <div className="library-summary mcp-summary" aria-label="MCP 总览">
              <div>
                <span>服务数量</span>
                <strong>{mcpServers.length}</strong>
              </div>
              <div>
                <span>在线服务</span>
                <strong>{onlineMcpCount}</strong>
              </div>
              <div>
                <span>可用工具</span>
                <strong>{mcpToolCount}</strong>
              </div>
              <div>
                <span>当前服务</span>
                <strong>{activeMcpServer?.name ?? '-'}</strong>
              </div>
            </div>

            {mcpError && <div className="library-error">{mcpError}</div>}

            <div className="mcp-grid">
              <section className="mcp-panel" aria-label="添加 MCP 服务">
                <div className="mcp-panel-heading">
                  <span>
                    <Server size={17} />
                    服务配置
                  </span>
                  {mcpForm.id && (
                    <button
                      className="mcp-link-button"
                      onClick={() => setMcpForm(emptyMcpForm())}
                      type="button"
                    >
                      新建
                    </button>
                  )}
                </div>

                <form className="mcp-form" id="mcp-server-form" onSubmit={saveMcpServer}>
                  <label>
                    <span>名称</span>
                    <input
                      onChange={(event) => setMcpForm((current) => ({ ...current, name: event.target.value }))}
                      placeholder="enterprise-tools"
                      required
                      value={mcpForm.name}
                    />
                  </label>
                  <label>
                    <span>服务 ID</span>
                    <input
                      disabled={Boolean(mcpForm.id)}
                      onChange={(event) => setMcpForm((current) => ({ ...current, id: event.target.value }))}
                      placeholder="留空则按名称生成"
                      value={mcpForm.id}
                    />
                  </label>
                  <div className="mcp-transport-control" aria-label="MCP 连接方式">
                    <button
                      className={mcpForm.transport === 'stdio' ? 'active' : ''}
                      onClick={() => setMcpForm((current) => ({ ...current, transport: 'stdio' }))}
                      type="button"
                    >
                      STDIO
                    </button>
                    <button
                      className={mcpForm.transport === 'streamable_http' ? 'active' : ''}
                      onClick={() => setMcpForm((current) => ({ ...current, transport: 'streamable_http' }))}
                      type="button"
                    >
                      流式 HTTP
                    </button>
                  </div>
                  {mcpForm.transport === 'streamable_http' && (
                    <>
                      <label>
                        <span>HTTP Endpoint</span>
                        <input
                          onChange={(event) => setMcpForm((current) => ({ ...current, endpoint: event.target.value }))}
                          placeholder="http://127.0.0.1:8080/mcp"
                          required
                          type="url"
                          value={mcpForm.endpoint}
                        />
                      </label>
                      <label>
                        <span>Bearer Token</span>
                        <input
                          onChange={(event) => setMcpForm((current) => ({ ...current, bearerToken: event.target.value }))}
                          placeholder={mcpForm.id ? '留空则沿用已有配置' : '可选'}
                          type="password"
                          value={mcpForm.bearerToken}
                        />
                      </label>
                    </>
                  )}
                  {mcpForm.transport === 'stdio' && (
                    <>
                      <label>
                        <span>启动命令</span>
                        <input
                          onChange={(event) => setMcpForm((current) => ({ ...current, command: event.target.value }))}
                          placeholder="node"
                          required
                          value={mcpForm.command}
                        />
                      </label>
                      <div className="mcp-form-group">
                        <span>参数</span>
                        {mcpForm.args.map((arg, index) => (
                          <div className="mcp-repeat-row" key={`arg-${index}`}>
                            <input
                              aria-label={`参数 ${index + 1}`}
                              onChange={(event) => updateMcpArg(index, event.target.value)}
                              placeholder={index === 0 ? 'server.js' : '--flag'}
                              value={arg}
                            />
                            <button onClick={() => removeMcpArg(index)} title="删除参数" type="button">
                              <Trash2 size={14} />
                            </button>
                          </div>
                        ))}
                        <button
                          className="mcp-add-row-button"
                          onClick={() => setMcpForm((current) => ({ ...current, args: [...current.args, ''] }))}
                          type="button"
                        >
                          <Plus size={14} />
                          添加参数
                        </button>
                      </div>
                      <div className="mcp-form-group">
                        <span>环境变量</span>
                        {mcpForm.envVars.map((env, index) => (
                          <div className="mcp-env-row" key={`env-${index}`}>
                            <input
                              aria-label={`环境变量键 ${index + 1}`}
                              onChange={(event) => updateMcpEnv(index, { key: event.target.value })}
                              placeholder="键"
                              value={env.key}
                            />
                            <input
                              aria-label={`环境变量值 ${index + 1}`}
                              onChange={(event) => updateMcpEnv(index, { value: event.target.value })}
                              placeholder="值"
                              value={env.value}
                            />
                            <button onClick={() => removeMcpEnv(index)} title="删除环境变量" type="button">
                              <Trash2 size={14} />
                            </button>
                          </div>
                        ))}
                        <button
                          className="mcp-add-row-button"
                          onClick={() => setMcpForm((current) => ({ ...current, envVars: [...current.envVars, { key: '', value: '' }] }))}
                          type="button"
                        >
                          <Plus size={14} />
                          添加环境变量
                        </button>
                      </div>
                      <label>
                        <span>工作目录</span>
                        <input
                          onChange={(event) => setMcpForm((current) => ({ ...current, workingDirectory: event.target.value }))}
                          placeholder="C:\\Users\\ASUS\\Desktop\\Codex\\Rag"
                          value={mcpForm.workingDirectory}
                        />
                      </label>
                    </>
                  )}
                  <label className="mcp-switch">
                    <input
                      checked={mcpForm.enabled}
                      onChange={(event) => setMcpForm((current) => ({ ...current, enabled: event.target.checked }))}
                      type="checkbox"
                    />
                    <span>启用服务</span>
                  </label>
                  <button className="mcp-save-button" disabled={mcpLoading} type="submit">
                    {mcpForm.id ? '保存配置' : '添加服务'}
                  </button>
                </form>
              </section>

              <section className="mcp-panel mcp-server-list" aria-label="MCP 服务列表">
                <div className="mcp-panel-heading">
                  <span>
                    <Plug size={17} />
                    MCP 服务器
                  </span>
                  <button
                    className="mcp-add-server-button"
                    onClick={() => {
                      setMcpForm(emptyMcpForm())
                      setSelectedMcpServerId('')
                    }}
                    type="button"
                  >
                    <Plus size={14} />
                    添加服务器
                  </button>
                </div>

                {mcpServers.length > 0 ? (
                  <div className="mcp-servers">
                    {mcpServers.map((server) => (
                      <div
                        className={server.id === activeMcpServer?.id ? 'mcp-server active' : 'mcp-server'}
                        key={server.id}
                      >
                        <button className="mcp-server-main" onClick={() => setSelectedMcpServerId(server.id)} type="button">
                          <span className={`mcp-status ${server.enabled ? server.status : 'disabled'}`} />
                          <span>
                            <strong>{server.name}</strong>
                            <small>
                              {server.transport === 'stdio'
                                ? `STDIO · ${[server.command, ...(server.args ?? [])].filter(Boolean).join(' ')}`
                                : `HTTP · ${server.endpoint}`}
                            </small>
                          </span>
                          <em>{server.tools.length}</em>
                        </button>
                        <div className="mcp-server-actions">
                          <button
                            className="mcp-icon-action"
                            onClick={() => editMcpServer(server)}
                            title="配置服务"
                            type="button"
                          >
                            <Settings size={15} />
                          </button>
                          <button
                            aria-pressed={server.enabled}
                            className={server.enabled ? 'mcp-toggle on' : 'mcp-toggle'}
                            onClick={() => toggleMcpServer(server)}
                            title={server.enabled ? '停用服务' : '启用服务'}
                            type="button"
                          >
                            <span />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="mcp-empty">
                    <Plug size={24} />
                    <p>还没有 MCP 服务。添加一个 Streamable HTTP endpoint 后刷新工具列表。</p>
                  </div>
                )}
              </section>
            </div>

            {activeMcpServer && (
              <section className="mcp-tools-panel" aria-label="MCP 工具">
                <div className="mcp-tools-header">
                  <div>
                    <span className={`mcp-status ${activeMcpServer.status}`} />
                    <strong>{activeMcpServer.name}</strong>
                    <small>{activeMcpServer.status}{activeMcpServer.lastError ? ` · ${activeMcpServer.lastError}` : ''}</small>
                  </div>
                  <div className="mcp-tool-actions">
                    <button onClick={() => editMcpServer(activeMcpServer)} type="button">
                      <SlidersHorizontal size={15} />
                      编辑
                    </button>
                    <button disabled={refreshingMcpId === activeMcpServer.id} onClick={() => refreshMcpServer(activeMcpServer)} type="button">
                      <RefreshCw size={15} />
                      {refreshingMcpId === activeMcpServer.id ? '刷新中' : '刷新工具'}
                    </button>
                    <button className="danger" onClick={() => deleteMcpServer(activeMcpServer)} type="button">
                      <Trash2 size={15} />
                      删除
                    </button>
                  </div>
                </div>

                {activeMcpServer.tools.length > 0 ? (
                  <div className="mcp-tool-list">
                    {activeMcpServer.tools.map((tool) => {
                      const key = `${activeMcpServer.id}.${tool.name}`
                      const exampleArguments = formatToolArgumentsExample(tool)
                      return (
                        <article className="mcp-tool-card" key={tool.name}>
                          <div className="mcp-tool-title">
                            <Wrench size={17} />
                            <div>
                              <strong>{tool.title || tool.name}</strong>
                              <small>{tool.name}</small>
                            </div>
                          </div>
                          <p>{tool.description || '该工具没有提供描述。'}</p>
                          <details>
                            <summary>Input schema / 参数结构</summary>
                            <pre>{formatSchema(tool.inputSchema)}</pre>
                          </details>
                          <div className="mcp-tool-example">
                            <div className="mcp-example-heading">
                              <span>示例参数</span>
                              <button
                                onClick={() =>
                                  setMcpToolArguments((current) => ({ ...current, [key]: exampleArguments }))
                                }
                                type="button"
                              >
                                填入示例
                              </button>
                            </div>
                            <pre>{exampleArguments}</pre>
                          </div>
                          <textarea
                            aria-label={`${tool.name} arguments`}
                            onChange={(event) =>
                              setMcpToolArguments((current) => ({ ...current, [key]: event.target.value }))
                            }
                            placeholder={exampleArguments}
                            rows={3}
                            value={mcpToolArguments[key] ?? exampleArguments}
                          />
                          <button
                            className="mcp-call-button"
                            disabled={callingMcpTool === key}
                            onClick={() => callMcpTool(activeMcpServer, tool)}
                            type="button"
                          >
                            <TerminalSquare size={15} />
                            {callingMcpTool === key ? '调用中' : '测试调用'}
                          </button>
                        </article>
                      )
                    })}
                  </div>
                ) : (
                  <div className="mcp-empty tools">
                    <Activity size={24} />
                    <p>当前服务尚未发现工具。点击“刷新工具”执行 initialize 和 tools/list。</p>
                  </div>
                )}
              </section>
            )}

            {mcpCallResult && (
              <section className="mcp-result" aria-label="MCP 调用结果">
                <div className="mcp-panel-heading">
                  <span>
                    <TerminalSquare size={17} />
                    调用结果
                  </span>
                  <strong>{mcpCallResult.success ? 'success' : 'error'}</strong>
                </div>
                <pre>{mcpCallResult.content || mcpCallResult.rawResult}</pre>
              </section>
            )}
          </div>
        </section>
      )}

      {activeView === 'chat' && (
        <section className="workspace" aria-label="聊天窗口">
          <header className="topbar">
            <div className="model-picker">
              <Bot size={18} />
              <span>RAG Chat</span>
            </div>
            <div className="topbar-actions">
              <button
                aria-pressed={inspectorOpen}
                className="icon-button"
                onClick={() => setInspectorOpen((open) => !open)}
                type="button"
                title="显示或隐藏检索面板"
              >
                <SlidersHorizontal size={18} />
              </button>
              <button
                aria-label={themeMode === 'dark' ? '切换浅色主题' : '切换暗黑主题'}
                aria-pressed={themeMode === 'dark'}
                className="icon-button theme-toggle"
                onClick={() => setThemeMode((mode) => (mode === 'dark' ? 'light' : 'dark'))}
                title={themeMode === 'dark' ? '切换浅色主题' : '切换暗黑主题'}
                type="button"
              >
                {themeMode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
              </button>
            </div>
          </header>

        <div className="chat-stage" ref={chatStageRef} onScroll={handleChatStageScroll}>
          <div className="conversation-title">
            <div className="conversation-heading">
              <h1>{activeConversation.title}</h1>
              <div className="context-strip" aria-label="当前知识库状态">
                <span>
                  <CheckCircle2 size={14} />
                  {activeKnowledgeBase?.name ?? '知识库就绪'}
                </span>
                <span>{activeKnowledgeBase?.documentCount ?? totalDocumentCount} 个文档</span>
                <span>引用优先</span>
                <span>{vectorStatus ? `${vectorStatus.vectorCount} vectors` : '向量状态同步中'}</span>
              </div>
            </div>
            <button className="ghost-button" onClick={() => setActiveView('knowledge')} type="button">
              <FolderKanban size={16} />
              切换知识库
            </button>
          </div>

          <div className="message-list">
            {messages.map((message) => (
              <article
                className={`message ${message.role}`}
                data-message-id={message.id}
                key={message.id}
              >
                <div className="avatar">{message.role === 'user' ? <UserRound size={17} /> : <Sparkles size={17} />}</div>
                <div className="message-body">
                  {message.command && (
                    <div className={`agent-mode-badge command-${message.command}`}>
                      <Sparkles size={13} />
                      {commandLabel(message.command)}
                    </div>
                  )}
                  <div className={`bubble ${message.role === 'assistant' && !message.content ? 'typing' : ''}`}>
                    {message.role === 'assistant' && !message.content ? (
                      <>
                        <span />
                        <span />
                        <span />
                      </>
) : message.role === 'assistant' ? (
                      <div className="answer-body">
                        {parseAnswerBlocks(message.content).map((block, index) => {
                          if (block.kind === 'table') {
                            const [header, ...body] = block.rows
                            return (
                              <table className="answer-table" key={index}>
                                <thead>
                                  <tr>
                                    {header.map((cell, c) => (
                                      <th key={c} style={{ textAlign: block.aligns[c] ?? 'left' }}>
                                        {cell}
                                      </th>
                                    ))}
                                  </tr>
                                </thead>
                                <tbody>
                                  {body.map((row, r) => (
                                    <tr key={r}>
                                      {row.map((cell, c) => (
                                        <td key={c} style={{ textAlign: block.aligns[c] ?? 'left' }}>
                                          {cell}
                                        </td>
                                      ))}
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            )
                          }
                          return <p key={index}>{block.text}</p>
                        })}
                        {isStreaming && message.id === messages[messages.length - 1]?.id && (
                          <span className="stream-caret" aria-hidden="true" />
                        )}
                      </div>
                    ) : (
                      <p>{message.content}</p>
                    )}
                  </div>
                  {message.role === 'user' && message.attachments && message.attachments.length > 0 && (
                    <div className="message-attachments">
                      {message.attachments.map((attachment, index) => (
                        <details key={`${attachment.fileName}-${index}`} className="attachment-card readonly">
                          <summary>
                            <span className="attachment-name">{attachment.fileName}</span>
                            <span className="attachment-size">{formatAttachmentSize(attachment.size)}</span>
                          </summary>
                          <pre className="attachment-preview">
                            {attachment.content.slice(0, 500)}
                            {attachment.content.length > 500 ? '...' : ''}
                          </pre>
                        </details>
                      ))}
                    </div>
                  )}
                  {message.role === 'assistant' && (
                    <div className="message-tools">
                      <button
                        className={copiedMessageId === message.id ? 'copied' : ''}
                        onClick={() => handleCopyMessage(message)}
                        title={copiedMessageId === message.id ? '已复制' : '复制回答'}
                        type="button"
                      >
                        {copiedMessageId === message.id ? (
                          <>
                            <CheckCircle2 size={14} />
                            已复制
                          </>
                        ) : (
                          <>
                            <Copy size={14} />
                            复制
                          </>
                        )}
                      </button>
                      {message.traceId && (
                        <button
                          disabled={traceReplay?.loading && traceReplay.traceId === message.traceId}
                          onClick={() => openTraceReplay(message.traceId ?? '')}
                          title="加载已持久化的 Agent 链路"
                          type="button"
                        >
                          <Activity size={14} />
                          {traceReplay?.loading && traceReplay.traceId === message.traceId ? '加载回放' : '链路回放'}
                        </button>
                      )}
                      <button
                        className={message.feedbackRating === 'up' ? 'active' : ''}
                        disabled={message.feedbackStatus === 'submitting' || !message.content}
                        onClick={() => submitMessageFeedback(message, 'up')}
                        title="回答有帮助"
                        type="button"
                      >
                        <ThumbsUp size={14} />
                        好
                      </button>
                      <button
                        className={message.feedbackRating === 'down' ? 'active danger' : ''}
                        disabled={message.feedbackStatus === 'submitting' || !message.content}
                        onClick={() => submitMessageFeedback(message, 'down')}
                        title="回答没有帮助"
                        type="button"
                      >
                        <ThumbsDown size={14} />
                        坏
                      </button>
                      {message.feedbackStatus === 'submitted' && <span>已记录</span>}
                      {message.feedbackStatus === 'error' && <span className="feedback-error">{message.feedbackError}</span>}
                    </div>
                  )}
                  {message.citations && message.citations.length > 0 && (
                    <div className="sources citation-details-list">
                      {message.citations.map((citation) => (
                        <details key={citationKey(citation)}>
                          <summary>
                            <span>{citation.index}</span>
                            <strong>{citationTitle(citation)}</strong>
                            <small>{citationMeta(citation)}</small>
                          </summary>
                          <p>{citationText(citation)}</p>
                        </details>
                      ))}
                    </div>
                  )}
                  {!message.citations?.length && message.sources && (
                    <div className="sources">
                      {message.sources.map((source, index) => (
                        <span className="source-chip" key={source}>
                          <span>{index + 1}</span>
                          {source}
                        </span>
                      ))}
                    </div>
                  )}
                  {message.role === 'assistant' && message.agentTrace && message.agentTrace.length > 0 && (
                    <details className="agent-trace-panel">
                      <summary>
                        <span>
                          <Brain size={14} />
                          执行轨迹
                        </span>
                        <strong>{message.agentTrace.length}</strong>
                      </summary>
                      <ol>
                        {message.agentTrace.map((step) => (
                          <li key={`${message.id}-${step.step}`}>
                            <span>{step.step}</span>
                            <div>
                              <strong>{traceLabel(step)}</strong>
                              {traceMeta(step) && <small>{traceMeta(step)}</small>}
                              <p>{step.observation}</p>
                              {step.error && <p className="trace-error">{step.error}</p>}
                            </div>
                          </li>
                        ))}
                      </ol>
                    </details>
                  )}
                </div>
              </article>
            ))}
          </div>
          {showScrollToBottom && (
            <button
              className="scroll-to-bottom"
              onClick={scrollChatToBottom}
              title="滚动到底部"
              aria-label="滚动到底部"
              type="button"
            >
              <ArrowDown size={18} />
            </button>
          )}
        </div>

        <div className="composer-zone">
          {showCommandMenu && (
            <div className="command-menu" role="listbox" aria-label="命令">
              <button onClick={() => applySlashCommand('multi-agent')} type="button">
                <span>
                  <Sparkles size={15} />
                  /multi-agent
                </span>
                <small>启用 Supervisor 和专家 Agent 编排</small>
              </button>
              <button onClick={() => applySlashCommand('plan')} type="button">
                <span>
                  <Brain size={15} />
                  /plan
                </span>
                <small>先生成执行计划，不直接执行</small>
              </button>
              <button onClick={() => applySlashCommand('status')} type="button">
                <span>
                  <Activity size={15} />
                  /status
                </span>
                <small>查看知识库、向量和 MCP 状态</small>
              </button>
              <button onClick={() => applySlashCommand('feedback')} type="button">
                <span>
                  <MessageSquarePlus size={15} />
                  /feedback
                </span>
                <small>记录本轮反馈</small>
              </button>
            </div>
          )}
          <form className="composer" onSubmit={sendMessage}>
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
                        title="Remove attachment"
                      >
                        x
                      </button>
                    </summary>
                    <pre className="attachment-preview">
                      {attachment.content.slice(0, 500)}
                      {attachment.content.length > 500 ? '...' : ''}
                    </pre>
                  </details>
                ))}
              </div>
            )}
            <textarea
              aria-label="输入问题"
              onChange={(event) => setDraft(event.target.value)}
              placeholder="输入问题，或输入 / 打开命令"
              rows={2}
              value={draft}
            />
            <div className="composer-toolbar">
              <div className="composer-tools">
                <button
                  className="icon-button"
                  onClick={() => composerUploadInputRef.current?.click()}
                  title="上传文件"
                  type="button"
                >
                  <Paperclip size={17} />
                </button>
                <input
                  accept=".txt,.md,.markdown,.csv,.json,text/plain,text/markdown,text/csv,application/json"
                  hidden
                  onChange={handleComposerUploadChange}
                  ref={composerUploadInputRef}
                  type="file"
                />
              </div>
              <button className="send-button" disabled={!draft.trim() || isStreaming} type="submit" title="发送">
                <ArrowUp size={18} />
              </button>
            </div>
          </form>
        </div>
        </section>
      )}

      {activeView === 'chat' && (
        <aside className="inspector" aria-label="检索参数">
        <section className="inspector-card live-card">
          <div className="inspector-heading">
            <span>检索状态</span>
            <strong>{retrievalStatusLabel(latestRetrievalStatus)}</strong>
          </div>
          <div className="retrieval-flow">
            <div className={retrievalFlowClass(latestRetrievalStatus, 'query')}>Query</div>
            <div className={retrievalFlowClass(latestRetrievalStatus, 'retrieve')}>Retrieve</div>
            <div className={retrievalFlowClass(latestRetrievalStatus, 'generate')}>Generate</div>
          </div>
          <p className="retrieval-meta">{retrievalStatusMeta(latestRetrievalStatus)}</p>
          <div className="metrics">
            <div>
              <span>命中文档</span>
              <strong>{latestRetrievalStatus?.hitCount ?? 0}</strong>
            </div>
            <div>
              <span>平均相似度</span>
              <strong>
                {typeof latestRetrievalStatus?.averageScore === 'number'
                  ? latestRetrievalStatus.averageScore.toFixed(2)
                  : '-'}
              </strong>
            </div>
          </div>
          {latestRetrievalStatus?.error && <p className="retrieval-error">{latestRetrievalStatus.error}</p>}
        </section>

        <section className="inspector-card">
          <div className="inspector-heading">
            <span>引用来源</span>
            <BookOpen size={16} />
          </div>
          <div className="source-preview">
            {activeCitations.length > 0
              ? activeCitations.map((citation) => (
                  <details key={citationKey(citation)} className="source-detail">
                    <summary>
                      <span>{citation.index}</span>
                      <div>
                        <strong>{citationTitle(citation)}</strong>
                        <small>{citationMeta(citation)}</small>
                      </div>
                    </summary>
                    <p>{citationText(citation)}</p>
                  </details>
                ))
              : sourcePreview.map((source, index) => (
                  <div className="source-preview-static" key={source.title}>
                    <span>{index + 1}</span>
                    <div>
                      <strong>{source.title}</strong>
                      <small>{source.meta}</small>
                    </div>
                  </div>
                ))}
          </div>
        </section>
        </aside>
      )}
      {traceReplay && (
        <div
          className="trace-replay-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              setTraceReplay(null)
            }
          }}
          role="presentation"
        >
          <section aria-label="Agent 链路回放" aria-modal="true" className="trace-replay-drawer" role="dialog">
            <header className="trace-replay-header">
              <div>
                <span>Agent 链路回放</span>
                <strong title={traceReplay.traceId}>{traceReplay.traceId}</strong>
              </div>
              <button aria-label="关闭链路回放" onClick={() => setTraceReplay(null)} type="button">
                <X size={18} />
              </button>
            </header>
            {traceReplay.loading && <p className="trace-replay-state">正在加载持久化轨迹…</p>}
            {traceReplay.error && <p className="trace-replay-state error">{traceReplay.error}</p>}
            {traceReplay.record && (
              <>
                <div className="trace-replay-summary">
                  <div>
                    <span>路由</span>
                    <strong>{traceReplay.record.route || '-'}</strong>
                  </div>
                  <div>
                    <span>工具</span>
                    <strong>{traceReplay.record.toolName || '直接回答'}</strong>
                  </div>
                  <div>
                    <span>步骤</span>
                    <strong>{traceReplay.record.agentTrace.length}</strong>
                  </div>
                  <div>
                    <span>异常</span>
                    <strong>{traceReplay.record.agentTrace.filter((step) => step.error || step.status === 'error').length}</strong>
                  </div>
                </div>
                <div className="trace-replay-controls">
                  <div>
                    <button
                      className={traceReplaySort === 'sequence' ? 'active' : ''}
                      onClick={() => setTraceReplaySort('sequence')}
                      type="button"
                    >
                      执行顺序
                    </button>
                    <button
                      className={traceReplaySort === 'duration' ? 'active' : ''}
                      onClick={() => setTraceReplaySort('duration')}
                      type="button"
                    >
                      耗时排序
                    </button>
                  </div>
                  <a href={`${JAEGER_UI_BASE_URL}/trace/${encodeURIComponent(traceReplay.record.traceId)}`} rel="noreferrer" target="_blank">
                    在 Jaeger 打开
                    <ExternalLink size={14} />
                  </a>
                </div>
                <p className="trace-replay-query">{traceReplay.record.query}</p>
                <ol className="trace-replay-timeline">
                  {replaySteps.map((step) => (
                    <li className={step.error || step.status === 'error' ? 'failed' : ''} key={`${traceReplay.record?.id}-${step.step}`}>
                      <span>{step.step + 1}</span>
                      <div>
                        <strong>{traceLabel(step)}</strong>
                        {traceMeta(step) && <small>{traceMeta(step)}</small>}
                        <p>{step.observation || '无额外观测信息'}</p>
                        {step.spanId && <code>span {step.spanId}</code>}
                        {step.error && <p className="trace-error">{step.error}</p>}
                      </div>
                    </li>
                  ))}
                </ol>
              </>
            )}
          </section>
        </div>
      )}
    </main>
  )
}

export default App
