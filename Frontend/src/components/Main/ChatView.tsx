import { useEffect, useRef, useState, type Dispatch, type FormEvent, type SetStateAction } from 'react'
import { askStream } from '../../api/chat'
import type { ChatMessage, DocumentRecord } from '../../types'

interface Props {
  doc: DocumentRecord
  messages: ChatMessage[]
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>
}

function withMarkers(text: string) {
  return text.split(/(\[\d+\])/g).map((part, i) =>
    /^\[\d+\]$/.test(part) ? (
      <sup key={i} className="marker">
        {part.slice(1, -1)}
      </sup>
    ) : (
      part
    ),
  )
}

export const ChatView = ({ doc, messages, setMessages }: Props) => {
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const controller = useRef<AbortController | null>(null)
  const end = useRef<HTMLDivElement>(null)

  const latestSources = [...messages].reverse().find((m) => m.sources.length > 0)?.sources ?? []

  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  useEffect(() => () => controller.current?.abort(), [])

  function change(id: string, fn: (m: ChatMessage) => ChatMessage) {
    setMessages((prev) => prev.map((m) => (m.id === id ? fn(m) : m)))
  }

  async function ask(e: FormEvent) {
    e.preventDefault()
    const question = input.trim()
    if (!question || streaming) return
    setInput('')

    const id = crypto.randomUUID()
    setMessages((prev) => [
      ...prev,
      { id: `${id}-q`, docId: doc.id, role: 'user', text: question, sources: [], status: 'done' },
      { id, docId: doc.id, role: 'assistant', text: '', sources: [], status: 'streaming' },
    ])
    setStreaming(true)
    controller.current = new AbortController()

    await askStream(
      question,
      doc.id,
      {
        onSources: (sources) => change(id, (m) => ({ ...m, sources })),
        onToken: (token) => change(id, (m) => ({ ...m, text: m.text + token })),
        onDone: () => change(id, (m) => ({ ...m, status: 'done' })),
        onError: (error) => change(id, (m) => ({ ...m, status: 'error', text: m.text || error })),
      },
      controller.current.signal,
    )

    change(id, (m) => (m.status === 'streaming' ? { ...m, status: 'done' } : m))
    setStreaming(false)
  }

  return (
    <div className="chat">
      <section className="chat-main">
        <header className="chat-title">{doc.fileName}</header>

        <div className="thread" aria-live="polite">
          {messages.length === 0 && <p className="thread-empty">Nothing asked yet. Try a question about {doc.fileName}.</p>}

          {messages.map((m) =>
            m.role === 'user' ? (
              <p key={m.id} className="msg msg-user">
                {m.text}
              </p>
            ) : (
              <p key={m.id} className={m.status === 'error' ? 'msg msg-bot msg-error' : 'msg msg-bot'}>
                {m.text ? withMarkers(m.text) : <em>Reading the document…</em>}
                {m.status === 'streaming' && m.text && <span className="caret" />}
              </p>
            ),
          )}
          <div ref={end} />
        </div>

        <form className="composer" onSubmit={ask}>
          <label>
            <span className="sr-only">Your question</span>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={1000}
              placeholder="Ask something about this file"
            />
          </label>
          {streaming ? (
            <button type="button" className="stop" onClick={() => controller.current?.abort()}>
              Stop
            </button>
          ) : (
            <button type="submit" className="send" disabled={!input.trim()}>
              Ask
            </button>
          )}
        </form>
      </section>

      <aside className="sources">
        <h3>Passages used</h3>
        {latestSources.length === 0 && <p className="sources-empty">The passages behind an answer show up here.</p>}
        <ol>
          {latestSources.map((s) => (
            <li key={s.chunkId}>
              <p>{s.snippet}</p>
              <small>
                {s.chunkId} · match {s.score.toFixed(2)}
              </small>
            </li>
          ))}
        </ol>
      </aside>
    </div>
  )
}
