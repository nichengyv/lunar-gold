import assert from 'node:assert/strict'
import { test } from 'node:test'
import { reconstructBefore } from '../src/shared/diff.ts'

test('按单块差异重建修改前内容并保留 CRLF', () => {
  const result = reconstructBefore(
    '@@ -2,1 +2,1 @@\n- old\n+ new\n',
    'first\r\nnew\r\nlast\r\n'
  )
  assert.deepEqual(result, { ok: true, text: 'first\r\nold\r\nlast\r\n' })
})

test('新增文件的完整差异可重建空基线', () => {
  const result = reconstructBefore('@@ -1,0 +1,2 @@\n+ hello\n+ world\n', 'hello\nworld')
  assert.deepEqual(result, { ok: true, text: '' })
})

test('删除文件可从空右侧恢复内容与末尾换行', () => {
  const result = reconstructBefore('@@ -1,2 +1,0 @@\n- gone\n- \n', '')
  assert.deepEqual(result, { ok: true, text: 'gone\n' })
})

test('文件末尾新增空行可逆重建', () => {
  const result = reconstructBefore('@@ -2,0 +2,1 @@\n+ \n', 'a\n')
  assert.deepEqual(result, { ok: true, text: 'a' })
})

test('截断差异明确降级', () => {
  const result = reconstructBefore('@@ -1,402 +1,0 @@\n- old\n…（省略 401 行删除）\n', '')
  assert.equal(result.ok, false)
  if (!result.ok) assert.match(result.reason, /省略/)
})

test('磁盘内容与新增块不匹配时拒绝伪造 before', () => {
  const result = reconstructBefore('@@ -2,1 +2,1 @@\n- old\n+ new\n', 'first\nexternal\nlast')
  assert.equal(result.ok, false)
  if (!result.ok) assert.match(result.reason, /再次修改/)
})

test('块头、位置与行数不完整时均降级', () => {
  assert.equal(reconstructBefore('不是 diff', 'a').ok, false)
  assert.equal(reconstructBefore('@@ -2,1 +1,1 @@\n- a\n+ b\n', 'b').ok, false)
  assert.equal(reconstructBefore('@@ -1,2 +1,1 @@\n- a\n+ b\n', 'b').ok, false)
  assert.equal(reconstructBefore('@@ -1,1 +1,1 @@\n a\n', 'b').ok, false)
})
