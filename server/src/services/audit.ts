import type { AuditRepository, AuditResult } from '../db/repositories/types.js';

/**
 * Audit trail for security relevant events (FEEDBACK_CONSTITUTION section 10).
 *
 * Detail values are restricted to short, secret-free scalars. Tickets, device
 * secrets, tokens, cookies and passwords must never reach this module.
 */

export const AUDIT_EVENT_TYPES = [
  'auth.login.success',
  'auth.login.failure',
  'auth.logout',
  'pairing.start',
  'pairing.lookup',
  'pairing.approve',
  'pairing.reject',
  'pairing.claim',
  'device.capabilities.update',
  'device.revoke',
  'system.info.request',
  'files.session.open',
  'file.transfer.started',
  'file.transfer.completed',
  'file.transfer.cancelled',
] as const;

export type AuditEventType = (typeof AUDIT_EVENT_TYPES)[number];

export interface AuditWriteInput {
  readonly eventType: AuditEventType;
  readonly result: AuditResult;
  readonly userId?: string | null | undefined;
  readonly deviceId?: string | null | undefined;
  readonly sessionId?: string | null | undefined;
  readonly detail?: Record<string, string | number | boolean> | undefined;
}

export interface AuditLoggerDeps {
  readonly audit: AuditRepository;
  /** Called when writing the audit record itself fails. */
  readonly onError?: (error: unknown) => void;
}

export interface AuditLogger {
  record(input: AuditWriteInput): Promise<void>;
}

const FORBIDDEN_DETAIL_KEYS = new Set([
  'ticket',
  'devicesecret',
  'devicetoken',
  'token',
  'password',
  'cookie',
  'authorization',
  'secret',
  'displaycode',
  'privatekey',
  'signature',
]);

function sanitizeDetail(
  detail: Record<string, string | number | boolean> | undefined,
): Record<string, string | number | boolean> | undefined {
  if (detail === undefined) {
    return undefined;
  }
  const clean: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(detail)) {
    if (FORBIDDEN_DETAIL_KEYS.has(key.toLowerCase())) {
      // Defence in depth: a caller mistake must not persist a secret.
      continue;
    }
    clean[key] = typeof value === 'string' ? value.slice(0, 200) : value;
  }
  return clean;
}

export function createAuditLogger(deps: AuditLoggerDeps): AuditLogger {
  return {
    async record(input: AuditWriteInput): Promise<void> {
      try {
        await deps.audit.record({
          eventType: input.eventType,
          result: input.result,
          userId: input.userId ?? null,
          deviceId: input.deviceId ?? null,
          sessionId: input.sessionId ?? null,
          detail: sanitizeDetail(input.detail),
        });
      } catch (error) {
        // An unwritable audit trail must be visible, but it must not turn a
        // successful security decision into a 500 for the caller.
        deps.onError?.(error);
      }
    },
  };
}
