import { bytes, fitsEvent, MAX_TIME, object, validateContext, validateRequest } from './protocol.js';
import type { Context, Event, JsonObject, Request } from './protocol.js';
export type { Context, Json, JsonObject } from './protocol.js';
export interface SdkError { reason: string; eventIds: string[]; status?: number; code?: string }
export interface FlushResult { accepted: number; failed: number }
export interface Options {
  endpoint: string; appId: string; appVersion: string; context?: JsonObject; identity?: JsonObject;
  batchSize?: number; flushIntervalMs?: number; maxQueueSize?: number; timeoutMs?: number;
  maxRetries?: number; retryBaseMs?: number; closeTimeoutMs?: number; onError?: (error: SdkError) => void;
}
interface Entry { event: Event; context: Context; identity: JsonObject; key: string; done: Promise<boolean>; settle: (ok: boolean) => void }
function integer(v: number | undefined, fallback: number, min: number, max: number): number {
  const n = v ?? fallback;
  if (!Number.isSafeInteger(n) || n < min || n > max) throw new Error(`Option must be an integer between ${min} and ${max}`);
  return n;
}
function osName(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  return /Android/i.test(ua) ? 'Android' : /iPhone|iPad|iPod/i.test(ua) ? 'iOS' : /Windows/i.test(ua) ? 'Windows' : /Macintosh/i.test(ua) ? 'macOS' : /Linux/i.test(ua) ? 'Linux' : 'unknown';
}
export class Analytics {
  private context: Context;
  private identity: JsonObject;
  private queue: Entry[] = [];
  private running = false;
  private closing = false;
  private stopped = false;
  private closePromise?: Promise<FlushResult>;
  private controller?: AbortController;
  private cancelDelay?: () => void;
  private timer: ReturnType<typeof setInterval>;
  private readonly options: Required<Omit<Options, 'context' | 'identity' | 'onError'>> & Pick<Options, 'onError'>;
  private readonly visibility = () => { if (document.visibilityState === 'hidden') this.start(); };
  constructor(options: Options) {
    const url = new URL(options.endpoint);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) throw new Error('Expected an HTTP(S) ingestion URL without credentials or fragment');
    this.options = { ...options, endpoint: url.href,
      batchSize: integer(options.batchSize, 20, 1, 500), flushIntervalMs: integer(options.flushIntervalMs, 10_000, 1, 2_147_483_647),
      maxQueueSize: integer(options.maxQueueSize, 1000, 1, 100_000), timeoutMs: integer(options.timeoutMs, 10_000, 1, 300_000),
      maxRetries: integer(options.maxRetries, 3, 0, 10), retryBaseMs: integer(options.retryBaseMs, 1000, 1, 60_000), closeTimeoutMs: integer(options.closeTimeoutMs, 15_000, 1, 300_000) };
    const extra = object(options.context ?? {});
    if ('app_id' in extra && extra.app_id !== options.appId) throw new Error('app_id is fixed');
    this.context = validateContext({ platform: 'web', os_name: osName(), ...extra, app_id: options.appId, app_version: options.appVersion });
    this.identity = object(options.identity ?? {});
    this.timer = setInterval(() => this.start(), this.options.flushIntervalMs);
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', this.visibility);
  }
  setIdentity(identity: JsonObject): void { this.assertOpen(); this.identity = object(identity); }
  setContext(context: JsonObject): void {
    this.assertOpen(); const update = object(context);
    if ('app_id' in update && update.app_id !== this.context.app_id) throw new Error('app_id is fixed');
    this.context = validateContext({ ...this.context, ...update });
  }
  track(name: string, properties: JsonObject = {}): string {
    this.assertOpen(); const id = crypto.randomUUID();
    try {
      if (this.queue.length >= this.options.maxQueueSize) throw new Error('Queue is full');
      const event: Event = { event_id: id, event_name: name, local_time_ms: Date.now(), properties: object(properties) };
      const context = validateContext(this.context), identity = object(this.identity);
      validateRequest({ context, identity, local_time: Math.floor(Date.now() / 1000), events: [event] });
      if (!fitsEvent(context, identity, event)) throw new Error('Normalized event exceeds 16000 bytes');
      let settle!: (ok: boolean) => void;
      const done = new Promise<boolean>(resolve => { settle = resolve; });
      this.queue.push({ event, context, identity, key: JSON.stringify([context, identity]), done, settle });
      if (this.queue.length >= this.options.batchSize) this.start();
      return id;
    } catch (error) { this.report({ reason: error instanceof Error ? error.message : 'Invalid event', eventIds: [id] }); throw error; }
  }
  async flush(): Promise<FlushResult> {
    const pending = this.queue.map(e => e.done); this.start();
    const results = await Promise.all(pending);
    return { accepted: results.filter(Boolean).length, failed: results.filter(v => !v).length };
  }
  close(): Promise<FlushResult> {
    if (this.closePromise) return this.closePromise;
    this.closing = true; clearInterval(this.timer);
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.visibility);
    this.closePromise = (async () => {
      const deadline = setTimeout(() => {
        this.stopped = true; this.controller?.abort(); this.cancelDelay?.();
        this.finish([...this.queue], false, { reason: 'Close timed out', eventIds: this.queue.map(e => e.event.event_id) });
      }, this.options.closeTimeoutMs);
      try { return await this.flush(); } finally { clearTimeout(deadline); this.stopped = true; }
    })();
    return this.closePromise;
  }
  private assertOpen(): void { if (this.closing || this.stopped) throw new Error('Analytics is closed'); }
  private report(error: SdkError): void { try { this.options.onError?.(error); } catch { /* isolate application callbacks */ } }
  private finish(batch: Entry[], ok: boolean, error?: SdkError): void {
    const active = batch.filter(e => this.queue.includes(e));
    const entries = new Set(active); this.queue = this.queue.filter(e => !entries.has(e));
    for (const e of active) e.settle(ok);
    if (error && active.length) this.report(error);
  }
  private start(): void {
    if (this.running || this.stopped || !this.queue.length) return;
    this.running = true;
    void this.drain().finally(() => { this.running = false; if (this.queue.length && !this.stopped) this.start(); });
  }
  private async drain(): Promise<void> {
    while (this.queue.length && !this.stopped) {
      const first = this.queue[0]!;
      const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
      const batch: Entry[] = [];
      for (const entry of this.queue) {
        if (entry.key !== first.key || batch.length >= this.options.batchSize) break;
        if (bytes(this.request([...batch, entry], MAX_TIME)) > (hidden ? 60_000 : 1_000_000)) break;
        batch.push(entry);
      }
      if (!batch.length) { this.finish([first], false, { reason: 'Request exceeds byte budget', eventIds: [first.event.event_id] }); continue; }
      let failure: SdkError = { reason: 'Delivery failed', eventIds: batch.map(e => e.event.event_id) }, accepted = false;
      for (let attempt = 0; attempt <= this.options.maxRetries && !this.stopped; attempt++) {
        let retry = true;
        this.controller = new AbortController();
        const controller = this.controller;
        const timeout = setTimeout(() => controller.abort(), this.options.timeoutMs);
        try {
          const response = await fetch(this.options.endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(this.request(batch, Math.floor(Date.now() / 1000))), signal: controller.signal,
            credentials: 'omit', redirect: 'error', keepalive: hidden });
          if (response.status === 202) { accepted = true; break; }
          failure = { reason: 'HTTP request rejected', eventIds: batch.map(e => e.event.event_id), status: response.status };
          try { const body = await response.json() as { error?: { code?: unknown } }; if (typeof body?.error?.code === 'string') failure.code = body.error.code; } catch { /* non-JSON gateway response */ }
          retry = response.status === 429 || response.status >= 500;
        } catch { failure = { reason: 'Network request failed or timed out', eventIds: batch.map(e => e.event.event_id) }; }
        finally { clearTimeout(timeout); this.controller = undefined; }
        if (!retry || attempt === this.options.maxRetries || this.stopped) break;
        await new Promise<void>(resolve => {
          const delay = setTimeout(() => { this.cancelDelay = undefined; resolve(); }, Math.min(60_000, this.options.retryBaseMs * 2 ** attempt) * (0.5 + Math.random() * 0.5));
          this.cancelDelay = () => { clearTimeout(delay); this.cancelDelay = undefined; resolve(); };
        });
      }
      this.finish(batch, accepted, accepted ? undefined : failure);
    }
  }
  private request(batch: Entry[], time: number): Request {
    return { context: batch[0]!.context, identity: batch[0]!.identity, local_time: time, events: batch.map(e => e.event) };
  }
}
