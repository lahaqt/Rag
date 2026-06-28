import type { Conversation, Message } from './App'

export interface SearchResult {
  conversation: Conversation
  matchedMessages: Message[]
}

export function searchConversations(
  query: string,
  conversations: Conversation[],
  messagesByConversation: Record<string, Message[]>,
): SearchResult[] {
  const keyword = query.trim().toLowerCase()
  if (!keyword) {
    return []
  }

  return conversations
    .map((conversation) => {
      const titleHit = conversation.title.toLowerCase().includes(keyword)
      const summaryHit = conversation.summary.toLowerCase().includes(keyword)
      const messages = messagesByConversation[conversation.id] ?? []
      const matchedMessages = messages.filter((message) =>
        message.content.toLowerCase().includes(keyword),
      )

      if (!titleHit && !summaryHit && matchedMessages.length === 0) {
        return null
      }

      return { conversation, matchedMessages }
    })
    .filter((result): result is SearchResult => result !== null)
}
