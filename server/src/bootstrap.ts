import { hashPassword } from './crypto/password.js';
import type { UserRepository } from './db/repositories/types.js';

/**
 * Optional first-user bootstrap.
 *
 * Only runs when no user exists at all and both FEEDBACK_BOOTSTRAP_EMAIL and
 * FEEDBACK_BOOTSTRAP_PASSWORD are provided. There is no default administrator
 * and no password anywhere in the source. The preferred path is
 * `npm run user:create`.
 */
export interface BootstrapResult {
  readonly created: boolean;
  readonly reason: 'created' | 'users_exist' | 'not_configured';
}

export async function bootstrapFirstUser(
  users: UserRepository,
  bootstrap: { email: string; password: string } | undefined,
): Promise<BootstrapResult> {
  if (bootstrap === undefined) {
    return { created: false, reason: 'not_configured' };
  }
  const existing = await users.count();
  if (existing > 0) {
    return { created: false, reason: 'users_exist' };
  }
  const passwordHash = await hashPassword(bootstrap.password);
  await users.create({ email: bootstrap.email, passwordHash });
  return { created: true, reason: 'created' };
}
