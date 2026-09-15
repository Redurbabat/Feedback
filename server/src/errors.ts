/**
 * Error codes and HTTP mapping of PROTOCOL.md section 10.
 *
 * `message` is meant for humans and must never contain secrets.
 */

export const ERROR_CODES = [
  'UNAUTHORIZED',
  'FORBIDDEN',
  'REAUTH_REQUIRED',
  'SESSION_EXPIRED',
  'DEVICE_REVOKED',
  'CAPABILITY_DENIED',
  'PERMISSION_REQUIRED',
  'UNSUPPORTED',
  'INVALID_MESSAGE',
  'RATE_LIMITED',
  'PAIRING_EXPIRED',
  'PAIRING_ALREADY_USED',
  'NOT_FOUND',
  'INTERNAL',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

const HTTP_STATUS_BY_CODE: Record<ErrorCode, number> = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  REAUTH_REQUIRED: 403,
  SESSION_EXPIRED: 409,
  DEVICE_REVOKED: 403,
  CAPABILITY_DENIED: 403,
  PERMISSION_REQUIRED: 403,
  UNSUPPORTED: 400,
  INVALID_MESSAGE: 400,
  RATE_LIMITED: 429,
  PAIRING_EXPIRED: 410,
  PAIRING_ALREADY_USED: 409,
  NOT_FOUND: 404,
  INTERNAL: 500,
};

export function httpStatusForCode(code: ErrorCode): number {
  return HTTP_STATUS_BY_CODE[code];
}

export interface ProtocolErrorOptions {
  /** Structured, secret-free detail for the server log only. */
  readonly logDetail?: Record<string, unknown>;
  readonly cause?: unknown;
  /** Value for a Retry-After header, in seconds. */
  readonly retryAfterSeconds?: number;
}

/** An error that is safe to serialise to a client per PROTOCOL.md section 6. */
export class ProtocolError extends Error {
  readonly code: ErrorCode;
  readonly statusCode: number;
  readonly logDetail: Record<string, unknown> | undefined;
  readonly retryAfterSeconds: number | undefined;

  constructor(code: ErrorCode, message: string, options: ProtocolErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'ProtocolError';
    this.code = code;
    this.statusCode = httpStatusForCode(code);
    this.logDetail = options.logDetail;
    this.retryAfterSeconds = options.retryAfterSeconds;
  }
}

export function invalidMessage(message: string, options?: ProtocolErrorOptions): ProtocolError {
  return new ProtocolError('INVALID_MESSAGE', message, options);
}

export function isProtocolError(value: unknown): value is ProtocolError {
  return value instanceof ProtocolError;
}
