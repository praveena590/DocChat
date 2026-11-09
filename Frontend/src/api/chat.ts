import { BASE_URL } from './http'
import { parseSse } from './sse'
import type { Citation } from '../types'

export interface StreamHandlers {
  onSources: (sources: Citation[]) => void
  onToken: (token: string) => void
  onDone: () => void
  onError: (message: string) => void
}

// axios waits for the whole response, so streaming has to use fetch.
export async function askStream(
  question: string,
  documentId: string,
  handlers: StreamHandlers,
  signal: AbortSignal,
) {
  try {
    const res = await fetch(`${BASE_URL}/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, documentId }),
      signal,
    })

    if (!res.ok || !res.body) {
      let message = `Request failed (${res.status})`
      try {
        message = (await res.json()).error ?? message
      } catch {
        // body was not JSON, keep the default message
      }
      handlers.onError(message)
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finished = false

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const { events, rest } = parseSse(buffer)
      buffer = rest

      for (const e of events) {
        if (e.event === 'sources') handlers.onSources(JSON.parse(e.data))
        else if (e.event === 'token') handlers.onToken(JSON.parse(e.data).text)
        else if (e.event === 'error') {
          handlers.onError(JSON.parse(e.data).error)
          finished = true
        } else if (e.event === 'done') {
          handlers.onDone()
          finished = true
        }
      }
    }
    if (!finished) handlers.onDone()
  } catch (err) {
    if ((err as Error).name === 'AbortError') return
    handlers.onError('Could not reach the server.')
  }
}
