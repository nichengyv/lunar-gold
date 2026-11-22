// 极简 stdio MCP server：只实现握手、tools/list 与 tools/call，用于 M1 传输冒烟。
const readline = require('readline');

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    return;
  }
  if (message.id === undefined) {
    return; // 通知消息不需要响应
  }
  let result;
  switch (message.method) {
    case 'initialize':
      result = {
        protocolVersion: message.params?.protocolVersion || '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'test-mcp', version: '1.0.0' }
      };
      break;
    case 'tools/list':
      result = {
        tools: [{
          name: 'ping',
          description: 'returns pong',
          inputSchema: { type: 'object', properties: {} }
        }]
      };
      break;
    case 'tools/call':
      result = {
        content: [{ type: 'text', text: 'pong' }],
        isError: false
      };
      break;
    default:
      process.stdout.write(JSON.stringify({
        jsonrpc: '2.0',
        id: message.id,
        error: { code: -32601, message: 'method not found' }
      }) + '\n');
      return;
  }
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }) + '\n');
});
