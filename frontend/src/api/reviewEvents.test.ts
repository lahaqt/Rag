import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseReviewEvent, watchReviewRunEvents } from './reviewEvents'

afterEach(() => vi.unstubAllGlobals())

describe('review event stream', () => {
  it('parses persisted event identifiers and payloads', () => {
    expect(parseReviewEvent('id: 12\nevent: checkpoint\ndata: {"phase":"hybrid_retrieval"}'))
      .toEqual({ id: '12', event: 'checkpoint', data: { phase: 'hybrid_retrieval' } })
  })

  it('reconnects and resumes with Last-Event-ID', async () => {
    const controller = new AbortController()
    const encoder = new TextEncoder()
    const stream = (id: number, phase: string) => new Response(new ReadableStream({
      start(value) { value.enqueue(encoder.encode(`id: ${id}\ndata: {"phase":"${phase}"}\n\n`)); value.close() },
    }))
    const fetchMock = vi.fn().mockResolvedValueOnce(stream(4, 'retrieval')).mockResolvedValueOnce(stream(5, 'aggregate'))
    vi.stubGlobal('fetch', fetchMock)
    const events: string[] = []

    await watchReviewRunEvents('run-1', 'token', controller.signal, event => {
      events.push(String(event.data.phase))
      if (events.length === 2) controller.abort()
    }, 0)

    expect(events).toEqual(['retrieval', 'aggregate'])
    expect(fetchMock.mock.calls[1][1].headers.get('Last-Event-ID')).toBe('4')
  })
})
