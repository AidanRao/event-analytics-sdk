export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type JsonObject = { [key: string]: Json };
export interface Event { event_id: string; event_name: string; local_time_ms: number; properties: JsonObject }
export interface Context extends JsonObject { app_id: string; app_version: string; platform: string; os_name: string }
export interface Request { context: Context; identity: JsonObject; local_time: number; events: Event[] }
export const MAX_TIME = Number.MAX_SAFE_INTEGER;
export const bytes = (v: unknown): number => new TextEncoder().encode(JSON.stringify(v)).byteLength;
export function copyJson(v: unknown, seen = new Set<object>(), depth = 0): Json {
  if (depth > 64) throw new Error('JSON nesting exceeds 64 levels');
  if (v === null || typeof v === 'string' || typeof v === 'boolean') return v;
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v !== 'object' || v === null) throw new Error('Expected a JSON value');
  if (seen.has(v)) throw new Error('Circular JSON value');
  if (!Array.isArray(v) && Object.getPrototypeOf(v) !== Object.prototype && Object.getPrototypeOf(v) !== null) throw new Error('Expected a plain JSON object');
  if (Object.getOwnPropertySymbols(v).length) throw new Error('Symbol keys are not JSON');
  seen.add(v);
  try {
    if (Array.isArray(v)) return Array.from(v, x => copyJson(x, seen, depth + 1));
    return Object.fromEntries(Object.keys(v).sort().map(k => [k, copyJson((v as Record<string, unknown>)[k], seen, depth + 1)]));
  } finally { seen.delete(v); }
}
export function object(v: unknown): JsonObject {
  const result = copyJson(v);
  if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('Expected a JSON object');
  return result;
}
function text(v: unknown): void {
  if (typeof v !== 'string' || v.length > 256 || !/\S/u.test(v)) throw new Error('Expected nonblank text of at most 256 UTF-16 units');
}
function timestamp(v: unknown): void {
  if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) throw new Error('Expected a nonnegative safe integer timestamp');
}
function keys(v: JsonObject, allowed: string[]): void {
  if (Object.keys(v).some(k => !allowed.includes(k)) || allowed.some(k => !(k in v))) throw new Error('Missing or unknown field');
}
export function validateContext(value: unknown): Context {
  const v = object(value);
  if (typeof v.app_id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(v.app_id)) throw new Error('Invalid app_id');
  for (const k of ['app_version', 'platform', 'os_name']) text(v[k]);
  return v as Context;
}
export function validateRequest(value: unknown): Request {
  const v = object(value); keys(v, ['context', 'identity', 'local_time', 'events']);
  validateContext(v.context); object(v.identity); timestamp(v.local_time);
  if (!Array.isArray(v.events) || v.events.length < 1 || v.events.length > 500) throw new Error('Expected 1–500 events');
  for (const item of v.events) {
    const e = object(item); keys(e, ['event_id', 'event_name', 'local_time_ms', 'properties']);
    text(e.event_id); text(e.event_name); timestamp(e.local_time_ms); object(e.properties);
  }
  return v as unknown as Request;
}
export function fitsEvent(context: Context, identity: JsonObject, event: Event): boolean {
  return bytes({ context, identity, local_time: MAX_TIME, version: 1, environment: 'production', received_at_ms: MAX_TIME, event }) <= 16_000;
}
