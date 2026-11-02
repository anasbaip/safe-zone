import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import puppeteer from 'puppeteer';

export const baseUrl = 'https://localhost:4200';
export const runId = randomBytes(6).toString('hex');
export const keyword = `E2e${runId}`;
export const category = `e2e-${runId}`;
const emails = [];
const userIds = [];
export const productIds = Array.from({ length: 14 }, (_, i) => `e2e-${runId}-${i}`);

function mongo(service, operation) {
  const container = `buy02-local-${service}-mongodb`;
  const project = execFileSync('docker', ['inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', container], { encoding: 'utf8' }).trim();
  assert.equal(project, 'buy02-local', 'E2E fixtures require the isolated buy02-local stack');
  const script = `const target = db.getSiblingDB(process.env.MONGO_DATABASE);
    if (!target.auth(process.env.MONGO_USERNAME, process.env.MONGO_PASSWORD)) throw new Error('Fixture authentication failed');
    ${operation}`;
  // Credentials stay inside the database container; no shell interpolation or credentials in argv.
  execFileSync('docker', ['exec', container, 'mongosh', '--quiet', '--norc', '--eval', script], { stdio: ['ignore', 'pipe', 'pipe'] });
}

export async function launch() {
  let executablePath = process.env.PUPPETEER_EXECUTABLE_PATH;
  if (!executablePath && existsSync('/usr/bin/google-chrome')) executablePath = '/usr/bin/google-chrome';
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    acceptInsecureCerts: true,
    args: process.env.E2E_NO_SANDBOX === '1' ? ['--no-sandbox'] : []
  });
  console.log(`Browser: ${await browser.version()}`);
  return browser;
}

export async function openAccount(browser, role, label) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  await page.setViewport({ width: 1280, height: 900 });
  const email = `e2e-${runId}-${label}@buy02.local`;
  emails.push(email);
  await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle0' });
  await page.locator('#regFullName').fill(`E2E ${label}`);
  await page.locator('#regEmail').fill(email);
  await page.locator('#regPassword').fill(randomBytes(16).toString('hex'));
  if (role === 'SELLER') await clickText(page, 'button', 'Merchant', false);
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
  await page.locator('form button[type="submit"]').click();
  const registered = await response;
  assert.equal(registered.status(), 201, 'Browser registration must succeed');
  await page.waitForFunction(expected => location.pathname === expected && localStorage.getItem('buy01_user'), {}, role === 'SELLER' ? '/seller/dashboard' : '/products');
  const user = await page.evaluate(() => JSON.parse(localStorage.getItem('buy01_user')));
  userIds.push(user.id);
  return { page, context, user };
}

export function seedProducts(seller, otherSeller) {
  const documents = productIds.map((id, i) => ({
    _id: id,
    name: `${keyword} Chair ${String(i + 1).padStart(2, '0')}`,
    description: 'Browser test product fixture',
    category: i === 1 ? `${category}-other` : category,
    price: 10 + i,
    quantity: i === 13 ? 0 : 10,
    ownerId: i === 1 ? otherSeller : seller,
    images: [], mediaIds: []
  }));
  mongo('product', `const products = ${JSON.stringify(documents)};
    products.forEach(product => { product.createdAt = new Date(); product.updatedAt = new Date(); });
    target.products.insertMany(products);`);
}

export function cleanup() {
  // Every filter contains only this run's exact random IDs/emails. Never clear collections or volumes.
  const ids = JSON.stringify(userIds);
  const failures = [];
  for (const [service, operation] of [
    ['order', `target.orders.deleteMany({customerId: {$in: ${ids}}});
      const customerIds = ${ids};
      const cartIds = customerIds.flatMap(id => /^[a-f0-9]{24}$/i.test(id) ? [id, ObjectId(id)] : [id]);
      target.carts.deleteMany({_id: {$in: cartIds}});
      target.checkout_receipts.deleteMany({userId: {$in: customerIds}});
      if (target.orders.countDocuments({customerId: {$in: customerIds}})
          || target.carts.countDocuments({_id: {$in: cartIds}})
          || target.checkout_receipts.countDocuments({userId: {$in: customerIds}})) {
        throw new Error('Order fixtures remain after cleanup');
      }`],
    ['product', `target.products.deleteMany({_id: {$in: ${JSON.stringify(productIds)}}});`],
    ['user', `target.users.deleteMany({email: {$in: ${JSON.stringify(emails)}}});`]
  ]) {
    try { mongo(service, operation); } catch { failures.push(service); }
  }
  assert.equal(failures.length, 0, `Could not remove ${failures.join(', ')} fixtures; inspect this run's e2e-${runId} records`);
}

export async function clickText(page, selector, text, exact = true) {
  await page.waitForFunction((query, wanted, equals) => [...document.querySelectorAll(query)]
    .some(element => !element.disabled && (equals ? element.textContent.trim() === wanted : element.textContent.includes(wanted))), {}, selector, text, exact);
  const elements = await page.$$(selector);
  try {
    for (const element of elements) {
      if (await element.evaluate((node, wanted, equals) => !node.disabled && (equals ? node.textContent.trim() === wanted : node.textContent.includes(wanted)), text, exact)) {
        await element.click();
        return;
      }
    }
  } finally { await Promise.all(elements.map(element => element.dispose())); }
  throw new Error(`No enabled ${selector} with text ${text}`);
}

export async function api(page, path, method = 'GET', body) {
  return page.evaluate(async (requestPath, requestMethod, requestBody) => {
    const headers = { Authorization: `Bearer ${localStorage.getItem('buy01_token')}` };
    if (requestMethod !== 'GET') {
      const csrf = await fetch('/api/csrf').then(response => response.json());
      headers['X-XSRF-TOKEN'] = csrf.token;
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(`/api${requestPath}`, { method: requestMethod, headers,
      body: requestBody === undefined ? undefined : JSON.stringify(requestBody) });
    return { status: response.status, body: await response.json() };
  }, path, method, body);
}

/** Verify observed reads in the page, independent of DevTools' evictable response-body buffer. */
export async function observedGet(page, response) {
  assert.equal(response.request().method(), 'GET', 'Only read-only requests may be repeated for verification');
  const url = new URL(response.url());
  const result = await api(page, url.pathname.replace(/^\/api/, '') + url.search);
  assert.equal(result.status, 200);
  return result.body;
}

export async function search(page, filters, action) {
  const pending = page.waitForResponse(response => {
    const url = new URL(response.url());
    return response.request().method() === 'GET' && url.pathname === '/api/products/search' && Object.entries(filters)
      .every(([key, value]) => url.searchParams.get(key) === String(value));
  });
  await action();
  const response = await pending;
  assert.equal(response.status(), 200);
  const result = await observedGet(page, response);
  await page.waitForFunction(names => JSON.stringify([...document.querySelectorAll('.atelier-gallery-grid .product-title')]
    .map(node => node.textContent.trim())) === JSON.stringify(names), {}, result.content.map(product => product.name));
  return result;
}

export async function catalog(page, query = keyword) {
  return search(page, { q: query, page: 0 }, async () => {
    await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle0' });
    await page.locator('input[aria-label="Search products"]').fill(query);
  });
}

export async function addProduct(page, productId) {
  await page.waitForSelector('.atelier-grid-tile');
  const pending = page.waitForResponse(r => new URL(r.url()).pathname === '/api/cart/items' && r.request().method() === 'POST');
  await page.evaluate(id => {
    const tile = [...document.querySelectorAll('.atelier-grid-tile')]
      .find(element => element.querySelector('h2')?.textContent.trim() === id);
    const button = [...(tile?.querySelectorAll('button') ?? [])].find(element => element.textContent.includes('Add to cart'));
    if (!button || button.disabled) throw new Error('Product cannot be added');
    button.click();
  }, `${keyword} Chair ${String(productIds.indexOf(productId) + 1).padStart(2, '0')}`);
  assert.equal((await pending).status(), 200);
  await page.waitForFunction(() => [...document.querySelectorAll('.atelier-grid-tile button')]
    .filter(button => button.textContent.includes('Add to cart')).some(button => !button.disabled));
}

export async function checkout(page) {
  await page.goto(`${baseUrl}/cart`, { waitUntil: 'networkidle0' });
  await clickText(page, 'a', 'Proceed to checkout');
  const fields = { fullName: 'Browser Test', phone: '0600000000', street: '1 Test Street',
    city: 'Casablanca', postalCode: '20000', country: 'Morocco' };
  for (const [name, value] of Object.entries(fields)) await page.locator(`#${name}`).fill(value);
  await clickText(page, 'button', 'Review order');
  await page.waitForFunction(() => [...document.querySelectorAll('h2')].some(element => element.textContent === 'Review your order'));
  const pending = page.waitForResponse(r => r.url().endsWith('/api/cart/checkout') && r.request().method() === 'POST');
  await clickText(page, 'button', 'Confirm Pay on Delivery');
  const response = await pending;
  assert.equal(response.status(), 200, 'Checkout must succeed through the real gateway');
  await page.waitForFunction(() => document.body.textContent.includes('Order confirmed'));
  const orderPath = await page.$eval('a[href^="/orders/"]', link => new URL(link.href).pathname);
  const stored = await api(page, orderPath);
  assert.equal(stored.status, 200, 'The order shown by checkout must be persisted');
  const order = stored.body;
  const cart = await api(page, '/cart');
  assert.equal(cart.status, 200);
  assert.equal(cart.body.items.length, 0, 'Confirmed checkout clears the cart');
  return order;
}

export async function report(page, role, expected, path = '/profile') {
  const endpoint = role === 'SELLER' ? 'seller' : 'me';
  const pending = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/orders/analytics/${endpoint}`);
  await page.goto(`${baseUrl}${path}`, { waitUntil: 'networkidle0' });
  const response = await pending;
  assert.equal(response.status(), 200);
  const result = await observedGet(page, response);
  assert.equal(result.totalAmount, expected.amount);
  assert.equal(result.orderCount, expected.orders);
  assert.equal(result.totalUnits, expected.units);
  await page.waitForFunction(() => document.querySelectorAll('app-analytics-dashboard dl dd').length === 3);
  assert.deepEqual(await page.$$eval('app-analytics-dashboard dl dd', nodes => nodes.map(node => node.textContent.trim())),
    [`$${expected.amount.toFixed(2)}`, String(expected.orders), String(expected.units)]);
  return result;
}

export async function saveFailure(browser, name) {
  mkdirSync('e2e/artifacts', { recursive: true });
  const pages = await browser.pages();
  for (const [index, page] of pages.entries()) {
    await page.screenshot({ path: `e2e/artifacts/${name.replaceAll(/[^a-z0-9]/gi, '-')}-${index}.png`, fullPage: true }).catch(() => {});
  }
}
