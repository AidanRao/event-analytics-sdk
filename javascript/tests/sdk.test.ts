import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import Ajv from 'ajv/dist/2020.js';
import { Analytics } from '../src/index.js';
import { fitsEvent, validateRequest } from '../src/protocol.js';
const read = (p: string) => JSON.parse(readFileSync(new URL(`../../spec/${p}`, import.meta.url), 'utf8'));
const fixtures = read('fixtures/requests.json');
const options = { endpoint: 'https://events.example/v1/events', appId: 'demo', appVersion: '1', retryBaseMs: 1 };
const clients: Analytics[] = [];
const client = (extra = {}) => { const sdk = new Analytics({ ...options, ...extra }); clients.push(sdk); return sdk; };
const accepted = () => new Response(null, { status: 202 });
afterEach(async () => { for (const c of clients.splice(0)) await c.close(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('shared protocol', () => {
  const ajv = new Ajv({ strict: false });
  ajv.addSchema(read('event.schema.json'));
  const validate = ajv.compile(read('protocol.schema.json'));
  for (const f of fixtures) it(f.name, () => {
    expect(validate(f.request)).toBe(f.valid);
    if (f.valid) expect(() => validateRequest(f.request)).not.toThrow();
    else expect(() => validateRequest(f.request)).toThrow();
  });
  for (const f of read('fixtures/sdk-cases.json')) it(f.name, () => {
    const r = fixtures[0].request;
    expect(fitsEvent(r.context, r.identity, { ...r.events[0], properties: f.properties })).toBe(f.valid);
  });
});
it('accepts empty 202 and freezes identity, context and caller objects', async () => {
  const requests: any[] = [];
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => { requests.push(JSON.parse(init.body)); return accepted(); }));
  const sdk = client(); const properties = { nested: { a: 1 } };
  const identity = { user: 'A' }; sdk.setIdentity(identity); sdk.track('first', properties);
  properties.nested.a = 2; identity.user = 'changed'; sdk.setIdentity({ user: 'B' }); sdk.setContext({ app_version: '2' }); sdk.track('second');
  expect(await sdk.flush()).toEqual({ accepted: 2, failed: 0 });
  expect(requests).toHaveLength(2); expect(requests[0].identity.user).toBe('A');
  expect(requests[0].events[0].properties.nested.a).toBe(1); expect(requests[0].context.app_version).toBe('1');
  expect(requests[1].identity.user).toBe('B'); expect(requests[1].context.app_version).toBe('2');
  expect(() => sdk.setContext({ app_id: 'other' })).toThrow();
});
it('retries partial acceptance with identical IDs and ignores confirmed count', async () => {
  const requests: any[] = []; const onError = vi.fn();
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => { requests.push(JSON.parse(init.body)); return requests.length === 1
    ? new Response(JSON.stringify({ error: { code: 'B02-001', details: { confirmed_events: 1, possibly_partial: true } } }), { status: 503 }) : accepted(); }));
  const sdk = client({ onError }); sdk.track('one'); sdk.track('two');
  expect(await sdk.flush()).toEqual({ accepted: 2, failed: 0 });
  expect(requests[0].events).toEqual(requests[1].events); expect(onError).not.toHaveBeenCalled();
});
it.each([400, 403, 413, 200, 302])('does not retry permanent or unexpected status %i', async status => {
  const fn = vi.fn(async () => new Response(JSON.stringify({ error: { code: 'A00-001' } }), { status })); vi.stubGlobal('fetch', fn);
  const onError = vi.fn(); const sdk = client({ onError }); const id = sdk.track('one');
  expect(await sdk.flush()).toEqual({ accepted: 0, failed: 1 }); expect(fn).toHaveBeenCalledTimes(1);
  expect(onError.mock.calls[0][0]).toMatchObject({ eventIds: [id], status, code: 'A00-001' });
});
it.each([429, 503])('exhausts exactly three additional retries for %i', async status => {
  const fn = vi.fn(async () => new Response('gateway', { status })); vi.stubGlobal('fetch', fn);
  const sdk = client(); sdk.track('one'); expect(await sdk.flush()).toEqual({ accepted: 0, failed: 1 }); expect(fn).toHaveBeenCalledTimes(4);
});
it('retries network failures and aborts timed-out fetches', async () => {
  let calls = 0;
  vi.stubGlobal('fetch', vi.fn((_url, init) => { calls++; return new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted')))); }));
  const sdk = client({ timeoutMs: 5, maxRetries: 1 }); sdk.track('one');
  expect(await sdk.flush()).toEqual({ accepted: 0, failed: 1 }); expect(calls).toBe(2);
});
it('includes in-flight events in capacity and concurrent flushes share completion', async () => {
  let resolve!: (v: Response) => void; vi.stubGlobal('fetch', vi.fn(() => new Promise(r => { resolve = r; })));
  const sdk = client({ maxQueueSize: 1 }); sdk.track('one'); const a = sdk.flush(), b = sdk.flush();
  expect(() => sdk.track('two')).toThrow('Queue is full'); resolve(accepted());
  expect(await a).toEqual({ accepted: 1, failed: 0 }); expect(await b).toEqual(await a);
});
it('flush only waits for its captured events', async () => {
  const resolvers: ((v: Response) => void)[] = []; vi.stubGlobal('fetch', vi.fn(() => new Promise(r => resolvers.push(r))));
  const sdk = client(); sdk.track('one'); const first = sdk.flush(); sdk.track('two'); resolvers[0](accepted());
  expect(await first).toEqual({ accepted: 1, failed: 0 });
  const rest = sdk.flush(); resolvers[1](accepted()); expect(await rest).toEqual({ accepted: 1, failed: 0 });
});
it('rejects lossy JSON values, cycles, oversize UTF8 and invalid options', () => {
  const sdk = client(); const circular: any = {}; circular.self = circular;
  for (const p of [{ x: undefined }, { x: NaN }, { x: Infinity }, { x: 1n }, { x: new Date() }, circular, { x: '😀'.repeat(4000) }])
    expect(() => sdk.track('bad', p as any)).toThrow();
  expect(() => client({ batchSize: 501 })).toThrow();
});
it('isolates error callback exceptions', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('bad', { status: 400 })));
  const sdk = client({ onError: () => { throw new Error('application callback'); } }); sdk.track('one');
  expect(await sdk.flush()).toEqual({ accepted: 0, failed: 1 });
});
it('automatically flushes on threshold and interval', async () => {
  const fn = vi.fn(async () => accepted()); vi.stubGlobal('fetch', fn); vi.useFakeTimers();
  const sdk = client({ batchSize: 2, flushIntervalMs: 100 }); sdk.track('one'); expect(fn).not.toHaveBeenCalled();
  sdk.track('two'); await sdk.flush(); expect(fn).toHaveBeenCalledTimes(1);
  sdk.track('three'); await vi.advanceTimersByTimeAsync(100); expect(fn).toHaveBeenCalledTimes(2);
});
it('splits at UTF8 HTTP budget', async () => {
  const sizes: number[] = []; vi.stubGlobal('fetch', vi.fn(async (_url, init) => { sizes.push(new TextEncoder().encode(init.body).length); return accepted(); }));
  const sdk = client({ batchSize: 500 }); for (let i = 0; i < 100; i++) sdk.track('large', { text: 'x'.repeat(14000) });
  expect(await sdk.flush()).toEqual({ accepted: 100, failed: 0 }); expect(sizes.length).toBe(2); expect(sizes.every(v => v <= 1_000_000)).toBe(true);
});
it('visibility sends small keepalive batches and removes listener on close', async () => {
  const listeners = new Map<string, Function>(); const doc = { visibilityState: 'visible', addEventListener: (k: string, f: Function) => listeners.set(k, f), removeEventListener: (k: string) => listeners.delete(k) };
  vi.stubGlobal('document', doc);
  const sent: RequestInit[] = []; vi.stubGlobal('fetch', vi.fn(async (_url, init) => { sent.push(init); return accepted(); }));
  const sdk = client({ batchSize: 500 }); for (let i = 0; i < 10; i++) sdk.track('large', { text: 'x'.repeat(14000) });
  doc.visibilityState = 'hidden'; listeners.get('visibilitychange')!(); await sdk.flush();
  expect(sent.length).toBe(3); expect(sent.every(v => v.keepalive && new TextEncoder().encode(v.body as string).length <= 60_000)).toBe(true);
  await sdk.close(); expect(listeners.size).toBe(0);
});
it('close has a deadline and is idempotent, cancels a request and rejects new events', async () => {
  vi.stubGlobal('fetch', vi.fn((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))))));
  const onError = vi.fn(); const sdk = client({ closeTimeoutMs: 5, onError }); sdk.track('one');
  const closing = sdk.close(); expect(sdk.close()).toBe(closing); expect(() => sdk.track('two')).toThrow('closed');
  expect(await closing).toEqual({ accepted: 0, failed: 1 }); expect(onError.mock.calls[0][0].reason).toBe('Close timed out');
});
