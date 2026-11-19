/**
 * SSE 解析器，移植自 frontend/src/api/sse.ts（P6-02 决策 2：两端各用自己的运行器，
 * 共用同一组 fixture 文件，防止协议分叉）。
 *
 * 支持：CRLF、多行 data:、注释帧（心跳）、流尾无空行的残余事件、[DONE] 终止。
 * onEvent 返回 true 表示「本次流已终结」，解析器立即取消读取，不等服务端关连接。
 */
export interface SseEvent {
  type: string
  data: string
}

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

  // SSE 以空行为事件边界：必须攒够整块再派发，逐行派发会把 event 行当成结束
  const handleLine = async (line: string): Promise<void> => {
    if (line === '') {
      await dispatch()
      return
    }
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // 按规范只去掉冒号后的一个空格，其余内容原样保留
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // id: / retry: / 注释行（: 开头）忽略
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

    // 流结束：未闭合的最后一个事件也照常派发
    if (!terminal) {
      if (buffer) await handleLine(buffer)
      if (eventType) await dispatch()
    }
  } finally {
    if (terminal) {
      await reader.cancel().catch(() => undefined)
    }
  }
}

/** 从文本构造流，fixture 测试与 stub 服务共用 */
export function streamFromText(text: string): ReadableStream<Uint8Array> {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes)
      controller.close()
    }
  })
}
