import {
  ArrowDown,
  ArrowUp,
  BookOpen,
  Bot,
  Brain,
  CheckCircle2,
  ChevronDown,
  Copy,
  Database,
  FileText,
  FolderKanban,
  Grid2X2,
  List,
  MessageSquarePlus,
  MoreHorizontal,
  PanelRight,
  Paperclip,
  Plug,
  Plus,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Trash2,
  Upload,
  UserRound,
  Zap,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { searchConversations } from './search'
import type { SearchResult } from './search'
import './App.css'

export type Message = {
  id: number
  role: 'user' | 'assistant'
  content: string
  sources?: string[]
}

export type Conversation = {
  id: number
  title: string
  summary: string
  time: string
  messages: Message[]
}

type ViewMode = 'chat' | 'search' | 'knowledge' | 'plugins'

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
  documentName: string
  chunkIndex: number
  score: number
  excerpt: string
}

type AgentChatResponse = {
  answer: string
  rewrittenQuery: string
  citations: ChatCitation[]
  agentTrace?: Array<{
    step: number
    phase: string
    route: string
    toolName: string
    action: string
    observation: string
  }>
  toolName?: string
  webSearchResults?: Array<{
    index: number
    title: string
    url: string
    snippet: string
  }>
  finishReason: string
}

const conversations: Conversation[] = [
  {
    id: 1,
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
          '建议把体验收束在一条主路径：选择知识库、提出问题、查看答案、核对引用、继续追问。主界面保持 ChatGPT 式的低干扰聊天体验，知识库和文件上下文放在左侧，检索参数放到可收起的右侧面板。',
        sources: ['产品需求文档.pdf', 'RAG 服务接口草案.md', '企业制度库 / 入职流程'],
      },
    ],
  },
  {
    id: 2,
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
    id: 3,
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

const quickPrompts = [
  { icon: Search, text: '检索当前知识库的关键结论' },
  { icon: ShieldCheck, text: '只基于引用来源回答' },
  { icon: FileText, text: '生成接口对接清单' },
]

const sourcePreview = [
  { title: '产品需求文档.pdf', meta: '第 4 页 · 命中 0.83' },
  { title: 'RAG 服务接口草案.md', meta: '接口定义 · 命中 0.79' },
  { title: '入职流程.docx', meta: '制度章节 · 命中 0.73' },
]

function App() {
  const [activeId, setActiveId] = useState(1)
  const [activeView, setActiveView] = useState<ViewMode>('chat')
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [activeKnowledgeBaseId, setActiveKnowledgeBaseId] = useState('')
  const [knowledgeDocuments, setKnowledgeDocuments] = useState<KnowledgeDocument[]>([])
  const [vectorStatus, setVectorStatus] = useState<VectorStatus | null>(null)
  const [knowledgeLoading, setKnowledgeLoading] = useState(false)
  const [knowledgeError, setKnowledgeError] = useState('')
  const [knowledgeSearch, setKnowledgeSearch] = useState('')
  const [deletingDocumentId, setDeletingDocumentId] = useState('')
  const [draft, setDraft] = useState('')
  const [inspectorOpen, setInspectorOpen] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [scrollToMessageId, setScrollToMessageId] = useState<number | null>(null)
  const [messagesByConversation, setMessagesByConversation] = useState(() =>
    Object.fromEntries(conversations.map((item) => [item.id, item.messages])),
  )
  const [isStreaming, setIsStreaming] = useState(false)
  const nextMessageId = useRef(100)
  const uploadInputRef = useRef<HTMLInputElement | null>(null)

  const activeConversation = useMemo(
    () => conversations.find((item) => item.id === activeId) ?? conversations[0],
    [activeId],
  )

  const messages = messagesByConversation[activeId] ?? []

  useEffect(() => {
    if (!scrollToMessageId) {
      return
    }

    const element = document.querySelector(`[data-message-id="${scrollToMessageId}"]`)
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' })
      element.classList.add('highlight-message')
      const timer = setTimeout(() => {
        element.classList.remove('highlight-message')
      }, 2000)
      return () => clearTimeout(timer)
    }

    setScrollToMessageId(null)
  }, [activeView, activeId, messages, scrollToMessageId])

  const searchResults = useMemo<SearchResult[]>(
    () => searchConversations(searchQuery, conversations, messagesByConversation),
    [searchQuery, messagesByConversation],
  )
  const activeKnowledgeBase = knowledgeBases.find((item) => item.id === activeKnowledgeBaseId)
  const uploadKnowledgeBaseId = activeKnowledgeBaseId || knowledgeBases[0]?.id || ''
  const totalDocumentCount = knowledgeBases.reduce((total, item) => total + item.documentCount, 0)
  const totalChunkCount = knowledgeBases.reduce((total, item) => total + item.chunkCount, 0)
  const filteredKnowledgeDocuments = useMemo(() => {
    const keyword = knowledgeSearch.trim().toLowerCase()
    if (!keyword) {
      return knowledgeDocuments
    }

    return knowledgeDocuments.filter((document) =>
      [document.fileName, document.contentType, document.status, document.knowledgeBaseId]
        .join(' ')
        .toLowerCase()
        .includes(keyword),
    )
  }, [knowledgeDocuments, knowledgeSearch])

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

  function sendMessage(event?: FormEvent<HTMLFormElement>, preset?: string) {
    event?.preventDefault()

    const text = (preset ?? draft).trim()
    if (!text || isStreaming) {
      return
    }

    const nextUserMessage: Message = {
      id: nextMessageId.current++,
      role: 'user',
      content: text,
    }

    const assistantMessageId = nextMessageId.current++
    let nextAssistantMessage: Message = {
      id: assistantMessageId,
      role: 'assistant',
      content: '正在处理请求...',
    }

    setDraft('')
    setIsStreaming(true)
    setMessagesByConversation((current) => ({
      ...current,
      [activeId]: [...(current[activeId] ?? []), nextUserMessage],
    }))

    fetch('/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query: text,
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
        },
      }),
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`POST /api/chat ${response.status}`)
        }
        return (await response.json()) as AgentChatResponse
      })
      .then((chatResponse) => {
        nextAssistantMessage = {
          id: assistantMessageId,
          role: 'assistant',
          content: chatResponse.answer,
          sources:
            chatResponse.citations.length > 0
              ? chatResponse.citations.map(
                  (citation) =>
                    `${citation.documentName || `片段 ${citation.index}`} / chunk ${citation.chunkIndex} / ${citation.score.toFixed(3)}`,
                )
              : chatResponse.webSearchResults?.map((result) => `${result.title} / ${result.url}`),
        }
      })
      .catch((error) => {
        nextAssistantMessage = {
          id: assistantMessageId,
          role: 'assistant',
          content: error instanceof Error ? `Agent 问答失败：${error.message}` : 'Agent 问答失败',
        }
      })
      .finally(() => {
        setMessagesByConversation((current) => ({
          ...current,
          [activeId]: [...(current[activeId] ?? []), nextAssistantMessage],
        }))
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

  function formatSize(size: number) {
    if (size < 1024) {
      return `${size} B`
    }
    if (size < 1024 * 1024) {
      return `${(size / 1024).toFixed(1)} KB`
    }

    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }

  const appClassName = [
    'app-shell',
    activeView === 'knowledge' || activeView === 'plugins' || activeView === 'search' ? 'library-mode' : '',
    inspectorOpen ? '' : 'inspector-collapsed',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <main className={appClassName}>
      <aside className="sidebar" aria-label="会话与知识库">
        <div className="brand-row">
          <div className="brand-mark">
            <Sparkles size={18} />
          </div>
          <button className="icon-button quiet" type="button" title="折叠侧栏">
            <PanelRight size={17} />
          </button>
        </div>

        <nav className="sidebar-menu" aria-label="主导航">
          <button
            className={activeView === 'chat' ? 'active' : ''}
            onClick={() => setActiveView('chat')}
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
            className={activeView === 'plugins' ? 'active' : ''}
            onClick={() => setActiveView('plugins')}
            type="button"
          >
            <Plug size={18} />
            插件
          </button>
        </nav>

        <section className="recent-section">
          <h2>最新</h2>
          <nav className="recent-list" aria-label="最近对话">
            {conversations.map((item) => (
              <button
                className={item.id === activeId ? 'recent-item active' : 'recent-item'}
                key={item.id}
                onClick={() => {
                  setActiveId(item.id)
                  setActiveView('chat')
                }}
                type="button"
              >
                <span>{item.title}</span>
              </button>
            ))}
          </nav>
        </section>
      </aside>

      {activeView === 'knowledge' && (
        <section className="library-page" aria-label="知识库">
          <div className="library-container">
            <header className="library-header">
              <h1>知识库</h1>
              <div className="library-actions">
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
                <button className="active" type="button">
                  全部
                </button>
                <button type="button">图片</button>
                <button type="button">文件</button>
              </div>
              <div className="library-view-tools" aria-label="视图设置">
                <button type="button" title="筛选">
                  <SlidersHorizontal size={17} />
                </button>
                <span />
                <button type="button" title="网格视图">
                  <Grid2X2 size={17} />
                </button>
                <button className="active" type="button" title="列表视图">
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
                <div className="document-list">
                  {filteredKnowledgeDocuments.map((document) => (
                    <div className="document-row" key={document.id}>
                      <span className="document-name">
                        <FileText size={17} />
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
                        onClick={() => deleteDocument(document)}
                        type="button"
                      >
                        <Trash2 size={15} />
                        {deletingDocumentId === document.id ? '删除中' : '删除'}
                      </button>
                    </div>
                  ))}
                </div>
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

            {searchQuery.trim() === '' ? (
              <div className="search-empty">
                <Search size={40} />
                <p>输入关键词搜索历史会话</p>
              </div>
            ) : searchResults.length === 0 ? (
              <div className="search-empty">
                <p>未找到相关对话</p>
              </div>
            ) : (
              <div className="search-results">
                {searchResults.map(({ conversation, matchedMessages }) => (
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

      {activeView === 'plugins' && (
        <section className="simple-page" aria-label="插件">
          <div className="simple-panel">
            <Plug size={24} />
            <h1>插件</h1>
            <p>后续可在这里管理模型工具、联网检索、文件解析和企业系统连接器。</p>
          </div>
        </section>
      )}

      {activeView === 'chat' && (
        <section className="workspace" aria-label="聊天窗口">
          <header className="topbar">
            <div className="model-picker">
              <Bot size={18} />
              <span>RAG Chat</span>
              <button type="button">
                GPT Compatible
                <ChevronDown size={15} />
              </button>
            </div>
            <div className="topbar-actions">
              <button className="pill-button" type="button">
                <Zap size={15} />
                快速
              </button>
              <button className="pill-button subtle" type="button">
                <Brain size={15} />
                推理
              </button>
              <button
                aria-pressed={inspectorOpen}
                className="icon-button"
                onClick={() => setInspectorOpen((open) => !open)}
                type="button"
                title="显示或隐藏检索面板"
              >
                <SlidersHorizontal size={18} />
              </button>
              <button className="icon-button" type="button" title="更多">
                <MoreHorizontal size={18} />
              </button>
            </div>
          </header>

        <div className="chat-stage">
          <div className="context-strip">
            <span>
              <CheckCircle2 size={14} />
              企业制度库已同步
            </span>
            <span>18 个文档</span>
            <span>引用优先</span>
          </div>

          <div className="conversation-title">
            <div>
              <span className="eyebrow">ChatGPT 极简聊天体验 · Kimi 式知识库上下文</span>
              <h1>{activeConversation.title}</h1>
            </div>
            <button className="ghost-button" type="button">
              <FolderKanban size={16} />
              切换知识库
            </button>
          </div>

          <div className="prompt-grid">
            {quickPrompts.map((prompt) => {
              const Icon = prompt.icon
              return (
                <button key={prompt.text} onClick={() => sendMessage(undefined, prompt.text)} type="button">
                  <Icon size={17} />
                  <span>{prompt.text}</span>
                </button>
              )
            })}
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
                  <div className="bubble">
                    <p>{message.content}</p>
                  </div>
                  {message.role === 'assistant' && (
                    <div className="message-tools">
                      <button type="button">
                        <Copy size={14} />
                        复制
                      </button>
                      <button type="button">
                        <BookOpen size={14} />
                        展开引用
                      </button>
                    </div>
                  )}
                  {message.sources && (
                    <div className="sources">
                      {message.sources.map((source, index) => (
                        <button key={source} type="button">
                          <span>{index + 1}</span>
                          {source}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </article>
            ))}
            {isStreaming && (
              <article className="message assistant">
                <div className="avatar">
                  <Sparkles size={17} />
                </div>
                <div className="message-body">
                  <div className="bubble typing">
                    <span />
                    <span />
                    <span />
                  </div>
                </div>
              </article>
            )}
          </div>
        </div>

        <div className="composer-zone">
          <form className="composer" onSubmit={sendMessage}>
            <textarea
              aria-label="输入问题"
              onChange={(event) => setDraft(event.target.value)}
              placeholder="向知识库提问，或粘贴需要分析的内容"
              rows={2}
              value={draft}
            />
            <div className="composer-toolbar">
              <div className="composer-tools">
                <button className="icon-button" title="上传文件" type="button">
                  <Paperclip size={17} />
                </button>
                <button className="mini-chip" type="button">
                  <Database size={14} />
                  企业制度库
                </button>
                <button className="mini-chip" type="button">
                  <ShieldCheck size={14} />
                  引用优先
                </button>
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
            <strong>Ready</strong>
          </div>
          <div className="retrieval-flow">
            <div className="done">Query</div>
            <div className="done">Retrieve</div>
            <div>Generate</div>
          </div>
          <div className="metrics">
            <div>
              <span>命中文档</span>
              <strong>8</strong>
            </div>
            <div>
              <span>平均相似度</span>
              <strong>0.78</strong>
            </div>
          </div>
        </section>

        <section className="inspector-card">
          <div className="inspector-heading">
            <span>模型与检索</span>
            <Settings2 size={16} />
          </div>
          <label>
            <span>Provider</span>
            <select defaultValue="openai">
              <option value="openai">OpenAI Compatible</option>
              <option value="qwen">通义千问</option>
              <option value="deepseek">DeepSeek</option>
            </select>
          </label>
          <label>
            <span>Top K</span>
            <input defaultValue="6" max="20" min="1" type="number" />
          </label>
          <label>
            <span>相似度阈值</span>
            <input defaultValue="0.72" max="1" min="0" step="0.01" type="number" />
          </label>
        </section>

        <section className="inspector-card">
          <div className="inspector-heading">
            <span>引用来源</span>
            <BookOpen size={16} />
          </div>
          <div className="source-preview">
            {sourcePreview.map((source, index) => (
              <button key={source.title} type="button">
                <span>{index + 1}</span>
                <div>
                  <strong>{source.title}</strong>
                  <small>{source.meta}</small>
                </div>
              </button>
            ))}
          </div>
        </section>
        </aside>
      )}
    </main>
  )
}

export default App
