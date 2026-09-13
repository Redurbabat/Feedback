import type { FastifyError, FastifyReply, FastifyRequest } from 'fastify';

import type { ErrorCode } from '../errors.js';
import { ProtocolError, isProtocolError } from '../errors.js';

/**
 * Single error serializer. Every response body follows PROTOCOL.md section 6:
 *
 *   { "error": { "code": "...", "message": "..." } }
 *
 * `message` is human readable and never contains secrets or raw input.
 */

export interface ErrorBody {
  readonly error: {
    readonly code: ErrorCode;
    readonly message: string;
  };
}

export function errorBody(code: ErrorCode, message: string): ErrorBody {
  return { error: { code, message } };
}

const FASTIFY_CODE_MAP: Record<string, { code: ErrorCode; message: string }> = {
  FST_ERR_CTP_EMPTY_JSON_BODY: { code: 'INVALID_MESSAGE', message: 'Leerer JSON-Body' },
  FST_ERR_CTP_INVALID_JSON_BODY: { code: 'INVALID_MESSAGE', message: 'Ungueltiges JSON' },
  FST_ERR_CTP_BODY_TOO_LARGE: { code: 'INVALID_MESSAGE', message: 'Anfrage ist zu gross' },
  FST_ERR_CTP_INVALID_MEDIA_TYPE: {
    code: 'UNSUPPORTED',
    message: 'Nicht unterstuetzter Content-Type',
  },
  FST_ERR_CTP_INVALID_CONTENT_LENGTH: {
    code: 'INVALID_MESSAGE',
    message: 'Ungueltige Content-Length',
  },
  FST_ERR_VALIDATION: { code: 'INVALID_MESSAGE', message: 'Anfrage ist ungueltig' },
  FST_ERR_NOT_FOUND: { code: 'NOT_FOUND', message: 'Ressource nicht gefunden' },
};

/** Translates any thrown value into a protocol conformant error. */
export function toProtocolError(error: unknown): ProtocolError {
  if (isProtocolError(error)) {
    return error;
  }

  const fastifyError = error as Partial<FastifyError> | undefined;
  const mapped =
    fastifyError?.code !== undefined ? FASTIFY_CODE_MAP[fastifyError.code] : undefined;
  if (mapped !== undefined) {
    return new ProtocolError(mapped.code, mapped.message, { cause: error });
  }

  if (typeof fastifyError?.statusCode === 'number') {
    if (fastifyError.statusCode === 404) {
      return new ProtocolError('NOT_FOUND', 'Ressource nicht gefunden', { cause: error });
    }
    if (fastifyError.statusCode === 405) {
      return new ProtocolError('UNSUPPORTED', 'Methode wird nicht unterstuetzt', { cause: error });
    }
    if (fastifyError.statusCode === 413) {
      return new ProtocolError('INVALID_MESSAGE', 'Anfrage ist zu gross', { cause: error });
    }
    if (fastifyError.statusCode >= 400 && fastifyError.statusCode < 500) {
      return new ProtocolError('INVALID_MESSAGE', 'Anfrage ist ungueltig', { cause: error });
    }
  }

  return new ProtocolError('INTERNAL', 'Unerwarteter Serverfehler', { cause: error });
}

export function sendProtocolError(
  request: FastifyRequest,
  reply: FastifyReply,
  error: ProtocolError,
): FastifyReply {
  if (error.retryAfterSeconds !== undefined) {
    reply.header('Retry-After', String(error.retryAfterSeconds));
  }

  const logPayload = {
    errorCode: error.code,
    statusCode: error.statusCode,
    detail: error.logDetail,
  };

  if (error.statusCode >= 500) {
    // Only server faults carry the stack; client errors stay quiet on purpose.
    request.log.error({ ...logPayload, err: error }, 'request failed');
  } else {
    request.log.warn(logPayload, 'request rejected');
  }

  return reply.code(error.statusCode).send(errorBody(error.code, error.message));
}
