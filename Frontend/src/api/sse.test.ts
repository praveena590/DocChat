import { describe, expect, it } from 'vitest'
import { parseSse } from './sse'

describe('parseSse', () => {
  it('reads complete events', () => {
    const { events, rest } = parseSse('event:token\ndata:{"text":"Hi"}\n\nevent:done\ndata:{"ok":true}\n\n')
    expect(events).toEqual([
      { event: 'token', data: '{"text":"Hi"}' },
      { event: 'done', data: '{"ok":true}' },
    ])
    expect(rest).toBe('')
  })

  it('keeps a half-received event for the next chunk', () => {
    const { events, rest } = parseSse('event:token\ndata:{"text":"A"}\n\nevent:tok')
    expect(events).toHaveLength(1)
    expect(rest).toBe('event:tok')
  })

  it('copes with Windows line endings', () => {
    const { events } = parseSse('event:token\r\ndata:{"text":"B"}\r\n\r\n')
    expect(events[0].data).toBe('{"text":"B"}')
  })
})
