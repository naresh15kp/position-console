import fs from 'node:fs'
import {chromium} from 'playwright-core'

const users = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const browserPath = process.env.CHROMIUM_PATH
if (!browserPath) throw new Error('Set CHROMIUM_PATH to a local Chromium executable')
const operator = users.find(user => user.role === 'OPERATOR')
const durationMs = Number(process.argv[3] ?? 165_000)
const browser = await chromium.launch({executablePath: browserPath, headless: true})
const context = await browser.newContext()
const page = await context.newPage()
const externalRequests = []
page.on('request', request => {
  const url = new URL(request.url())
  if (!['127.0.0.1', 'localhost'].includes(url.hostname)) externalRequests.push(request.url())
})
await page.addInitScript(() => {
  window.__longTasks = []
  new PerformanceObserver(list => {
    for (const entry of list.getEntries()) window.__longTasks.push(entry.duration)
  }).observe({entryTypes: ['longtask']})
})

await page.goto(process.env.CONSOLE_URL ?? 'http://127.0.0.1:5173', {waitUntil: 'networkidle'})
await page.getByLabel('Username').fill(operator.username)
await page.getByLabel('Password').fill(operator.password)
await page.getByRole('button', {name: 'Sign in'}).click()
await page.getByRole('status').filter({hasText: 'Live'}).waitFor()
await page.waitForTimeout(durationMs)
await page.getByRole('button', {name: 'Zoom in'}).click()
await page.getByLabel('Asset ID').fill('AST-00001')
const longTasks = await page.evaluate(() => window.__longTasks)
const result = {
  durationMs,
  externalRequests,
  maximumLongTaskMs: Math.max(0, ...longTasks),
  longTasksOver200Ms: longTasks.filter(duration => duration > 200).length,
  assetSummary: await page.locator('.workspace .section-head span').textContent(),
  connection: await page.getByRole('status').filter({hasText: 'Live'}).textContent(),
}
console.log(JSON.stringify(result))
await browser.close()
if (externalRequests.length || result.longTasksOver200Ms || result.connection?.trim() !== 'Live') {
  process.exitCode = 1
}
