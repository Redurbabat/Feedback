import type { Clock } from '../services/clock.js';

/**
 * Token bucket rate limiting, applied per IP and per principal.
 *
 * Limits follow PROTOCOL.md section 11. The store is in-process, which is
 * sufficient for the single-node MVP; a multi-node deployment needs a shared
 * store (Redis or the database) behind the same interface.
 */

export interface RateLimitRule {
  /** Bucket capacity, i.e. the burst size and the number of tokens per window. */
  readonly limit: number;
  /** Time in which a completely empty bucket refills. */
  readonly windowMs: number;
}

export const RATE_LIMITS = {
  authLogin: { limit: 10, windowMs: 15 * 60_000 },
  pairingStart: { limit: 10, windowMs: 10 * 60_000 },
  pairingLookup: { limit: 10, windowMs: 10 * 60_000 },
  pairingStatus: { limit: 120, windowMs: 10 * 60_000 },
  pairingClaim: { limit: 10, windowMs: 10 * 60_000 },
  filesSession: { limit: 30, windowMs: 10 * 60_000 },
  filesContent: { limit: 60, windowMs: 10 * 60_000 },
  apiDefault: { limit: 600, windowMs: 10 * 60_000 },
} as const satisfies Record<string, RateLimitRule>;

export type RateLimitName = keyof typeof RATE_LIMITS;

export interface RateLimitDecision {
  readonly allowed: boolean;
  readonly remaining: number;
  readonly retryAfterSeconds: number;
}

interface Bucket {
  tokens: number;
  updatedAt: number;
}

const SWEEP_INTERVAL_MS = 60_000;

export class RateLimiter {
  private readonly buckets = new Map<string, Bucket>();
  private lastSweep: number;

  constructor(private readonly clock: Clock) {
    this.lastSweep = clock.now();
  }

  /** Takes one token from the bucket identified by `key`. */
  consume(key: string, rule: RateLimitRule): RateLimitDecision {
    const now = this.clock.now();
    this.sweep(now);

    const refillPerMs = rule.limit / rule.windowMs;
    const bucket = this.buckets.get(key) ?? { tokens: rule.limit, updatedAt: now };
    const elapsed = Math.max(0, now - bucket.updatedAt);
    bucket.tokens = Math.min(rule.limit, bucket.tokens + elapsed * refillPerMs);
    bucket.updatedAt = now;

    if (bucket.tokens < 1) {
      this.buckets.set(key, bucket);
      const missing = 1 - bucket.tokens;
      return {
        allowed: false,
        remaining: 0,
        retryAfterSeconds: Math.max(1, Math.ceil(missing / refillPerMs / 1000)),
      };
    }

    bucket.tokens -= 1;
    this.buckets.set(key, bucket);
    return {
      allowed: true,
      remaining: Math.floor(bucket.tokens),
      retryAfterSeconds: 0,
    };
  }

  /** Drops buckets that have fully refilled; they carry no information. */
  private sweep(now: number): void {
    if (now - this.lastSweep < SWEEP_INTERVAL_MS) {
      return;
    }
    this.lastSweep = now;
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.updatedAt > SWEEP_INTERVAL_MS * 30) {
        this.buckets.delete(key);
      }
    }
  }

  reset(): void {
    this.buckets.clear();
  }

  get size(): number {
    return this.buckets.size;
  }
}
