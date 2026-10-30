// SPDX-License-Identifier: AGPL-3.0-only
// Checks every answer the server tests recorded (testing/ContractAnswers.kt, OSTROM_CONTRACT_ANSWERS_DIR) against
// the ostrom-core API contract: the operation answering that method and path, the status among its responses, and the
// body against the response schema, extra fields included (a field the contract does not describe is a difference).
// Fails on any difference not listed in answers-drift.txt, and on a listed one the tests now answer as the contract.
//
//   node check-answers.mjs <openapi.yaml> <answers directory> <answers-drift.txt> [report.md]
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { parse } from 'yaml'

const [contractFile, answersDir, driftFile, reportFile] = process.argv.slice(2)
const contract = parse(readFileSync(contractFile, 'utf8'))
const METHODS = ['get', 'put', 'post', 'delete', 'patch']

// Every object that lists its properties refuses the others: the served shape must be the contract's.
function closeObjects(node) {
  if (Array.isArray(node)) return node.forEach(closeObjects)
  if (node === null || typeof node !== 'object') return
  if (node.properties && node.additionalProperties === undefined && node.unevaluatedProperties === undefined) {
    node.unevaluatedProperties = false
  }
  Object.values(node).forEach(closeObjects)
}
closeObjects(contract.components.schemas)
for (const item of Object.values(contract.paths)) closeObjects(item)

const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv)
const formats = new Set(JSON.stringify(contract).match(/"format":"[^"]+"/g)?.map((f) => f.slice(10, -1)) ?? [])
for (const format of formats) if (!ajv.formats[format]) ajv.addFormat(format, true)
ajv.addSchema(contract, 'contract')

const pointer = (...parts) => parts.map((p) => String(p).replaceAll('~', '~0').replaceAll('/', '~1')).join('/')

const operations = []
for (const [path, item] of Object.entries(contract.paths)) {
  for (const method of METHODS.filter((m) => item[m])) {
    const segments = path.split('/')
    const pattern = segments
      .map((s) => (s === '{path}' ? '.*' : /^\{.+\}$/.test(s) ? '[^/]+' : s.replace(/[.*+?^$()|[\]\\]/g, '\\$&')))
      .join('/')
    operations.push({
      method: method.toUpperCase(),
      path,
      regex: new RegExp(`^${pattern}$`),
      literals: segments.filter((s) => s && !s.startsWith('{')).length,
      operation: item[method],
    })
  }
}

function operationOf(method, path) {
  const candidates = operations.filter((o) => o.method === method && o.regex.test(path))
  return candidates.sort((a, b) => b.literals - a.literals)[0]
}

const answers = readdirSync(answersDir)
  .filter((f) => f.endsWith('.jsonl'))
  .flatMap((f) => readFileSync(join(answersDir, f), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)))

const seen = new Map() // key -> number of answers
const issues = new Map() // key -> { count, problems: Set }
const validators = new Map()
function issue(key, problem) {
  const entry = issues.get(key) ?? { count: 0, problems: new Set() }
  entry.count += 1
  if (entry.problems.size < 4) entry.problems.add(problem)
  issues.set(key, entry)
}

for (const answer of answers) {
  const found = operationOf(answer.method, answer.path)
  if (!found) {
    // A test asking for a route no module serves gets the router's 404 or 405: nothing to compare.
    if (answer.status !== 404 && answer.status !== 405) {
      const route = answer.path.replace(/\/[0-9a-f-]{8,}(?=\/|$)/gi, '/{}')
      issue(`UNDESCRIBED ${answer.method} ${route}`, `${answer.status}: no operation of the contract has this path`)
    }
    continue
  }
  const id = found.operation.operationId
  const status = String(answer.status)
  const key = `${id} ${status}`
  seen.set(key, (seen.get(key) ?? 0) + 1)
  const responses = found.operation.responses ?? {}
  const code = [status, `${status[0]}XX`, 'default'].find((c) => responses[c])
  if (!code) {
    issue(key, `status ${status} is not among the contract's (${Object.keys(responses).join(', ')})`)
    continue
  }
  const response = responses[code].$ref
    ? responses[code].$ref.split('/').slice(1).reduce((n, k) => n[k], contract)
    : responses[code]
  const media = Object.keys(response.content ?? {})
  const body = answer.body
  const served = (answer.contentType ?? '').split(';')[0].trim()
  if (media.length === 0) {
    if (body) issue(key, `a ${served || 'body'} is served; the contract's ${code} has none`)
    continue
  }
  const json = media.find((m) => m === 'application/json' || m.endsWith('+json'))
  if (!json) continue // A file, a stream: the media type only.
  if (body === null || body === undefined) continue // Streamed: not recorded.
  if (served !== 'application/json' && !served.endsWith('+json')) {
    issue(key, `${served || 'no content type'} is served; the contract answers ${json}`)
    continue
  }
  let value
  try {
    value = JSON.parse(body)
  } catch {
    issue(key, 'the body is not JSON')
    continue
  }
  const ref = responses[code].$ref
    ? `contract#${pointer('', ...responses[code].$ref.split('/').slice(1), 'content', json, 'schema')}`
    : `contract#${pointer('', 'paths', found.path, found.method.toLowerCase(), 'responses', code, 'content', json, 'schema')}`
  let validate = validators.get(ref)
  if (!validate) {
    validate = ajv.compile({ $ref: ref })
    validators.set(ref, validate)
  }
  if (!validate(value)) {
    const keys = value && typeof value === 'object' && !Array.isArray(value) ? Object.keys(value).join(', ') : typeof value
    const missing = new Set()
    const extra = new Set()
    const other = new Set()
    for (const e of validate.errors) {
      const at = e.instancePath || ''
      if (e.keyword === 'required') missing.add(`${at}/${e.params.missingProperty}`.replace(/^\//, ''))
      else if (e.keyword === 'unevaluatedProperties') extra.add(`${at}/${e.params.unevaluatedProperty}`.replace(/^\//, ''))
      else if (e.keyword !== 'oneOf' && e.keyword !== 'if') other.add(`${at || '(root)'} ${e.message}`)
    }
    const parts = [
      missing.size ? `missing ${[...missing].join(', ')}` : '',
      extra.size ? `not in the contract ${[...extra].join(', ')}` : '',
      other.size ? [...other].slice(0, 3).join('; ') : '',
    ].filter(Boolean)
    issue(key, `served {${keys}}: ${parts.join('; ')}`)
  }
}

const drift = new Map()
for (const raw of readFileSync(driftFile, 'utf8').split('\n')) {
  const line = raw.trim()
  if (!line || line.startsWith('#')) continue
  const [key, reason] = line.split(/\s{2,}/)
  drift.set(key.trim(), (reason ?? '').trim())
}

const unlisted = [...issues.keys()].filter((k) => !drift.has(k)).sort()
const fixed = [...drift.keys()].filter((k) => seen.has(k) && !issues.has(k)).sort()
const unseen = [...drift.keys()].filter((k) => !seen.has(k) && !issues.has(k)).sort()

const lines = [`# Answers against the contract`, '', `${answers.length} answers, ${seen.size} operation and status pairs.`, '']
const section = (title, keys, describe) => {
  if (keys.length === 0) return
  lines.push(`## ${title} (${keys.length})`, '')
  for (const key of keys) lines.push(`- \`${key}\`${describe(key)}`)
  lines.push('')
}
section('Differences not in answers-drift.txt', unlisted, (k) => ` (${issues.get(k).count}): ${[...issues.get(k).problems].join(' | ')}`)
section('Listed in answers-drift.txt, now as the contract: remove the line', fixed, () => '')
section('Known differences (answers-drift.txt)', [...drift.keys()].filter((k) => issues.has(k)).sort(),
  (k) => ` (${issues.get(k).count}): ${[...issues.get(k).problems].join(' | ')} — ${drift.get(k)}`)
section('Listed, not answered by any test', unseen, () => '')
const report = lines.join('\n')
console.log(report)
if (reportFile) writeFileSync(reportFile, report)
if (unlisted.length > 0 || fixed.length > 0) process.exit(1)
