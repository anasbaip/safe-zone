import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import {
  addProduct, api, baseUrl, catalog, category, checkout, cleanup, clickText,
  keyword, launch, observedGet, openAccount, productIds, report, saveFailure, search, seedProducts
} from './support.mjs';

describe('Real marketplace browser flows', { concurrency: false, timeout: 300000 }, () => {
  let browser, client, seller, otherSeller, stranger, delivered;
  before(async () => {
    browser = await launch();
    seller = await openAccount(browser, 'SELLER', 'Seller');
    otherSeller = await openAccount(browser, 'SELLER', 'OtherSeller');
    client = await openAccount(browser, 'CLIENT', 'Client');
    stranger = await openAccount(browser, 'CLIENT', 'Stranger');
    seedProducts(seller.user.id, otherSeller.user.id);
  });
  after(async () => {
    try { if (browser) await browser.close(); } finally { cleanup(); }
  });

  function scenario(name, run) {
    it(name, { timeout: 90000 }, async () => {
      try { await run(); } catch (error) {
        if (browser) await saveFailure(browser, name);
        throw error;
      }
    });
  }

  scenario('search combines filters, sorts, paginates and handles invalid or empty results', async () => {
    const page = client.page;
    const initial = await catalog(page);
    assert.equal(initial.totalElements, 14);
    assert.equal(initial.totalPages, 2);
    const next = await search(page, { q: keyword, page: 1 }, () => clickText(page, 'nav[aria-label="Product pages"] button', 'Next'));
    assert.equal(next.content.length, 2);
    const filtered = await search(page, { q: keyword, category, page: 0 }, () => page.locator('#categoryFilter').fill(category));
    assert.equal(filtered.totalElements, 13, 'Changing filters resets pagination');
    await search(page, { category, minPrice: 10 }, () => page.locator('#minPriceFilter').fill('10'));
    const prices = await search(page, { category, maxPrice: 12 }, () => page.locator('#maxPriceFilter').fill('12'));
    assert.equal(prices.totalElements, 2);
    const sorted = await search(page, { category, maxPrice: 12, sort: 'price-desc' }, () => page.select('#sortSelect', 'price-desc'));
    assert.deepEqual(sorted.content.map(product => product.price), [12, 10]);
    await page.locator('#minPriceFilter').fill('25');
    await page.waitForFunction(() => document.querySelector('#priceFilterError')?.textContent.includes('Minimum'));
    assert.equal(await page.$eval('form button[type="submit"]', button => button.disabled), true);
    await page.goto(`${baseUrl}/products`, { waitUntil: 'networkidle0' });
    await catalog(page);
    const inStock = await search(page, { q: keyword, inStock: true }, async () => {
      const value = await page.$eval('select[aria-label="Filter inventory availability"]', element => element.options[1].value);
      await page.select('select[aria-label="Filter inventory availability"]', value);
    });
    assert.equal(inStock.totalElements, 13);
    const empty = await search(page, { q: `${keyword}missing` }, () => page.locator('input[aria-label="Search products"]').fill(`${keyword}missing`));
    assert.equal(empty.totalElements, 0);
    await page.waitForFunction(() => document.body.textContent.includes('No matching pieces found'));
  });

  scenario('a customer checks out a mixed-seller cart and pending orders contribute zero analytics', async () => {
    await catalog(client.page);
    await addProduct(client.page, productIds[0]);
    await addProduct(client.page, productIds[1]);
    await client.page.goto(`${baseUrl}/cart`, { waitUntil: 'networkidle0' });
    const quantity = client.page.waitForResponse(r => new URL(r.url()).pathname === `/api/cart/items/${productIds[0]}` && r.request().method() === 'PUT');
    await client.page.locator(`button[aria-label="Increase quantity of ${keyword} Chair 01"]`).click();
    assert.equal((await quantity).status(), 200);
    delivered = await checkout(client.page);
    assert.equal(delivered.totalAmount, 31);
    assert.equal(delivered.totalItems, 3);
    await report(client.page, 'CLIENT', { amount: 0, orders: 0, units: 0 });
    await report(seller.page, 'SELLER', { amount: 0, orders: 0, units: 0 }, '/seller/dashboard');
  });

  scenario('seller status changes produce customer totals and isolate each seller in a mixed order', async () => {
    assert.ok(delivered, 'Checkout scenario must produce an order');
    await seller.page.goto(`${baseUrl}/orders/${delivered.id}`, { waitUntil: 'networkidle0' });
    for (const status of ['CONFIRMED', 'SHIPPED', 'DELIVERED']) {
      await seller.page.select('#next-status', status);
      const pending = seller.page.waitForResponse(r => r.url().endsWith(`/orders/${delivered.id}/status`) && r.request().method() === 'PATCH');
      await clickText(seller.page, 'button[type="submit"]', 'Update status');
      const response = await pending;
      assert.equal(response.status(), 200);
      const stored = await api(seller.page, `/orders/${delivered.id}`);
      assert.equal(stored.status, 200);
      assert.equal(stored.body.status, status);
      await seller.page.waitForFunction(label => document.querySelector('main header')?.textContent.includes(label), {},
        status[0] + status.slice(1).toLowerCase());
      await seller.page.waitForFunction(() => !document.querySelector('#next-status')?.disabled);
    }
    const customer = await report(client.page, 'CLIENT', { amount: 31, orders: 1, units: 3 });
    assert.equal(customer.products.length, 2);
    assert.equal(customer.months.reduce((total, month) => total + month.amount, 0), 31);
    const first = await report(seller.page, 'SELLER', { amount: 20, orders: 1, units: 2 }, '/seller/dashboard');
    assert.deepEqual(first.products.map(product => product.productId), [productIds[0]]);
    const second = await report(otherSeller.page, 'SELLER', { amount: 11, orders: 1, units: 1 });
    assert.deepEqual(second.products.map(product => product.productId), [productIds[1]]);
    await report(stranger.page, 'CLIENT', { amount: 0, orders: 0, units: 0 });
    assert.equal((await api(client.page, '/orders/analytics/seller')).status, 403);
    assert.equal((await api(seller.page, '/orders/analytics/me')).status, 403);
    assert.equal((await api(stranger.page, `/orders/${delivered.id}`)).status, 403);
    const scoped = await api(stranger.page, `/orders/analytics/me?userId=${client.user.id}`);
    assert.equal(scoped.status, 200);
    assert.equal(scoped.body.orderCount, 0, 'A caller-supplied identity cannot select another customer');
  });

  scenario('cancelling a second purchase leaves delivered analytics unchanged', async () => {
    await catalog(client.page);
    await addProduct(client.page, productIds[2]);
    const cancelled = await checkout(client.page);
    await client.page.goto(`${baseUrl}/orders/${cancelled.id}`, { waitUntil: 'networkidle0' });
    await clickText(client.page, 'button', 'Cancel order');
    const pending = client.page.waitForResponse(r => r.url().endsWith(`/orders/${cancelled.id}/cancel`) && r.request().method() === 'PATCH');
    await clickText(client.page, 'button', 'Yes, cancel it');
    const response = await pending;
    assert.equal(response.status(), 200);
    const stored = await api(client.page, `/orders/${cancelled.id}`);
    assert.equal(stored.status, 200);
    assert.equal(stored.body.status, 'CANCELLED');
    await report(client.page, 'CLIENT', { amount: 31, orders: 1, units: 3 });
    await report(seller.page, 'SELLER', { amount: 20, orders: 1, units: 2 });
  });

  scenario('date filters include the placement day, exclude other days, and validate ranges on mobile', async () => {
    const page = client.page;
    await report(page, 'CLIENT', { amount: 31, orders: 1, units: 3 });
    const placed = delivered.createdAt.slice(0, 10);
    const previous = new Date(`${placed}T00:00:00Z`);
    previous.setUTCDate(previous.getUTCDate() - 1);
    const yesterday = previous.toISOString().slice(0, 10);
    async function dates(from, to) {
      await page.evaluate((start, end) => {
        for (const [field, value] of [['from', start], ['to', end]]) {
          const input = document.querySelector(`app-analytics-dashboard input[formcontrolname="${field}"]`);
          input.value = value;
          input.dispatchEvent(new Event('input', { bubbles: true }));
        }
      }, from, to);
    }
    async function apply(from, to) {
      await dates(from, to);
      const pending = page.waitForResponse(r => {
        const url = new URL(r.url());
        return url.pathname.endsWith('/orders/analytics/me') && url.searchParams.get('from') === from && url.searchParams.get('to') === to;
      });
      await clickText(page, 'app-analytics-dashboard button', 'Apply dates');
      const response = await pending;
      assert.equal(response.status(), 200);
      const result = await observedGet(page, response);
      await page.waitForFunction(amount => document.querySelector('app-analytics-dashboard dl dd')?.textContent.trim() === `$${amount.toFixed(2)}`, {}, result.totalAmount);
      return result;
    }
    await page.setViewport({ width: 390, height: 844 });
    assert.equal((await apply(placed, placed)).totalAmount, 31);
    assert.equal((await apply(yesterday, yesterday)).totalAmount, 0);
    await page.waitForFunction(() => document.querySelector('app-analytics-dashboard')?.textContent.includes('No delivered orders'));
    await dates(placed, yesterday);
    await page.waitForFunction(() => document.querySelector('app-analytics-dashboard form button')?.disabled);
    assert.match(await page.$eval('app-analytics-dashboard [role="alert"]', node => node.textContent), /on or after/);
    assert.equal((await apply(placed, placed)).totalAmount, 31);
  });
});
