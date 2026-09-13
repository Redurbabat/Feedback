import type { z } from 'zod';

import { invalidMessage } from '../errors.js';

/**
 * Validates request data with zod. Only the field path and the rule that failed
 * are reported back; the submitted value never appears in the message.
 */
export function parseOrThrow<T extends z.ZodTypeAny>(schema: T, value: unknown): z.infer<T> {
  const result = schema.safeParse(value);
  if (result.success) {
    return result.data;
  }
  const summary = result.error.issues
    .slice(0, 5)
    .map((issue) => {
      const path = issue.path.join('.');
      return path.length > 0 ? `${path}: ${issue.message}` : issue.message;
    })
    .join('; ');
  throw invalidMessage(`Anfrage ist ungueltig (${summary})`);
}
