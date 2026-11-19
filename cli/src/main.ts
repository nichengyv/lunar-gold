#!/usr/bin/env node
/**
 * 入口：node cli/src/main.ts（Node ≥ 24 默认 type-stripping，无构建步骤）。
 */
import { execute } from './cli.ts'

process.exitCode = await execute(process.argv.slice(2))
