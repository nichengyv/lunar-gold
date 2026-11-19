/** 与 CLI 同源的 SSE 解析器；fixture 直接引用 cli/test/fixtures/sse。 */
export interface SseEvent {
  type: string
  data: string
}

/** onEvent 返回 true 表示已收到终态，立即取消读取。 */
export async function parseSSE(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: SseEvent) => boolean | void | Promise<boolean | void>
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventType = ''
  let dataLines: string[] = []
  let terminal = false

  const dispatch = async (): Promise<void> => {
    if (!eventType) {
      dataLines = []
      return
    }
    const type = eventType
    const data = dataLines.join('\n')
    eventType = ''
    dataLines = []
    if ((await onEvent({ type, data })) === true) terminal = true
  }

  const handleLine = async (line: string): Promise<void> => {
    if (line === '') {
      await dispatch()
      return
    }
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }

  try {
    while (!terminal) {
      const { done, value } = await reader.read()
      if (done) {
        buffer += decoder.decode()
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        await handleLine(line)
        if (terminal) break
      }
    }
    if (!terminal) {
      if (buffer) await handleLine(buffer)
      if (eventType) await dispatch()
    }
  } finally {
    if (terminal) await reader.cancel().catch(() => undefined)
  }
}

export function streamFromText(text: string): ReadableStream<Uint8Array> {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes)
      controller.close()
    }
  })
}
