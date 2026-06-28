import { describe, expect, it } from 'vitest'
import type { Conversation, Message } from './App'
import { searchConversations } from './search'

const sampleConversations: Conversation[] = [
  {
    id: '1',
    title: 'RAG 知识问答设计',
    summary: '检索策略、引用、模型参数',
    time: '刚刚',
    messages: [],
  },
  {
    id: '2',
    title: '知识库接入',
    summary: '上传、切片、向量化状态',
    time: '12:48',
    messages: [],
  },
]

const sampleMessages: Record<string, Message[]> = {
  1: [
    { id: 101, role: 'user', content: '我想做一个基于 RAG 的企业知识问答' },
    { id: 102, role: 'assistant', content: '建议把体验收束在一条主路径' },
  ],
}

describe('searchConversations', () => {
  it('returns empty array for empty query', () => {
    const result = searchConversations('', sampleConversations, sampleMessages)
    expect(result).toEqual([])
  })

  it('matches conversation title', () => {
    const result = searchConversations('RAG', sampleConversations, sampleMessages)
    expect(result).toHaveLength(1)
    expect(result[0].conversation.id).toBe('1')
  })

  it('matches conversation summary', () => {
    const result = searchConversations('切片', sampleConversations, sampleMessages)
    expect(result).toHaveLength(1)
    expect(result[0].conversation.id).toBe('2')
  })

  it('matches message content', () => {
    const result = searchConversations('主路径', sampleConversations, sampleMessages)
    expect(result).toHaveLength(1)
    expect(result[0].matchedMessages).toHaveLength(1)
    expect(result[0].matchedMessages[0].id).toBe(102)
  })

  it('is case-insensitive', () => {
    const result = searchConversations('rag', sampleConversations, sampleMessages)
    expect(result).toHaveLength(1)
    expect(result[0].conversation.id).toBe('1')
  })
})
