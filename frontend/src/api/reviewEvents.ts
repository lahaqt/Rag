export type ReviewEvent = { id: string; event: string; data: Record<string, unknown> }

export async function watchReviewRunEvents(
  runId: string,
  accessToken: string,
  signal: AbortSignal,
  onEvent: (event: ReviewEvent) => void,
  retryDelayMs = 500,
) {
  let lastEventId = ''
  while (!signal.aborted) {
    try {
      const headers = new Headers({ Authorization: `Bearer ${accessToken}`, Accept: 'text/event-stream' })
      if (lastEventId) headers.set('Last-Event-ID', lastEventId)
      const response = await fetch(`/api/v1/review-runs/${runId}/events`, { headers, signal })
      if (!response.ok || !response.body) throw new Error(`Review event stream failed (${response.status})`)
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let pending = ''
      while (!signal.aborted) {
        const { value, done } = await reader.read()
        pending += decoder.decode(value, { stream: !done }).replaceAll('\r\n', '\n')
        const blocks = pending.split('\n\n')
        pending = blocks.pop() ?? ''
        for (const block of blocks) {
          const parsed = parseReviewEvent(block)
          if (parsed) {
            lastEventId = parsed.id || lastEventId
            onEvent(parsed)
          }
        }
        if (done) break
      }
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof DOMException && error.name === 'AbortError') return
    }
    if (!signal.aborted) await new Promise(resolve => window.setTimeout(resolve, retryDelayMs))
  }
}

export function parseReviewEvent(block: string): ReviewEvent | null {
  let id = ''
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  try {
    return { id, event, data: JSON.parse(data.join('\n')) as Record<string, unknown> }
  } catch {
    return { id, event, data: { message: data.join('\n') } }
  }
}
