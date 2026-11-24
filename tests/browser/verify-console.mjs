import fs from 'node:fs'
import {fileURLToPath} from 'node:url'
import {chromium} from 'playwright-core'

const users = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const browserPath = process.env.CHROMIUM_PATH
if (!browserPath) throw new Error('Set CHROMIUM_PATH to a local Chromium executable')
const operator = users.find(user => user.role === 'OPERATOR')
if (!operator) throw new Error('No operator account')

const browser = await chromium.launch({executablePath: browserPath, headless: true})
const context = await browser.newContext({bypassCSP: true})
const page = await context.newPage()
const externalRequests = []
const requestFailures = []
page.on('request', request => {
  const url = new URL(request.url())
  if (!['127.0.0.1', 'localhost'].includes(url.hostname)) externalRequests.push(request.url())
})
page.on('requestfailed', request => requestFailures.push(request.url()))
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
const assetSummary = page.locator('.workspace .section-head span')
await assetSummary.filter({hasText: /assets/}).waitFor()

for (let index = 0; index < 12; index++) await page.keyboard.press('Tab')
const focused = await page.evaluate(() => ({
  tag: document.activeElement?.tagName,
  text: document.activeElement?.textContent?.trim(),
  outline: getComputedStyle(document.activeElement).outlineStyle,
}))

await page.addScriptTag({path: fileURLToPath(new URL('./node_modules/axe-core/axe.min.js', import.meta.url))})
const accessibility = await page.evaluate(async () => {
  const result = await axe.run(document, {
    runOnly: {type: 'tag', values: ['wcag2a', 'wcag2aa']},
  })
  return result.violations.map(({id, impact, description, nodes}) => ({
    id, impact, description, nodes: nodes.length,
  }))
})

await context.setOffline(true)
await page.getByRole('status').filter({hasText: 'Reconnecting'}).waitFor({timeout: 12_000})
await page.waitForTimeout(30_000)
await context.setOffline(false)
await page.getByRole('status').filter({hasText: 'Live'}).waitFor({timeout: 15_000})
await assetSummary.filter({hasText: /assets/}).waitFor()
const longTasks = await page.evaluate(() => window.__longTasks)

const result = {
  externalRequests,
  failedRequestsDuringOutage: requestFailures.length,
  focusedAfterKeyboardNavigation: focused,
  wcagViolations: accessibility,
  maximumLongTaskMs: Math.max(0, ...longTasks),
  assetSummaryAfterReconnect: await assetSummary.textContent(),
  connectionAfterReconnect: await page.getByRole('status').filter({hasText: 'Live'}).textContent(),
}
console.log(JSON.stringify(result))
await browser.close()
if (externalRequests.length || accessibility.length || focused.outline === 'none'
    || result.connectionAfterReconnect?.trim() !== 'Live') {
  process.exitCode = 1
}
