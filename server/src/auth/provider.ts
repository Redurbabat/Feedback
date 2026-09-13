import type { UserRecord, UserRepository } from '../db/repositories/types.js';
import { ProtocolError } from '../errors.js';
import { burnPasswordWork, verifyPassword } from '../crypto/password.js';

/**
 * Authentication providers for the Control Center.
 *
 * Only the password provider exists today. The registry exists so that a second
 * factor (TOTP, WebAuthn) can be added later as an additional provider; nothing
 * here pretends that such a factor is implemented. Asking for an unregistered
 * method yields UNSUPPORTED.
 */

export type AuthMethod = 'password';

export interface PasswordCredentials {
  readonly method: 'password';
  readonly email: string;
  readonly password: string;
}

export type Credentials = PasswordCredentials;

export type AuthFailureReason = 'invalid_credentials' | 'account_disabled';

export type AuthOutcome =
  | { readonly ok: true; readonly user: UserRecord }
  | { readonly ok: false; readonly reason: AuthFailureReason };

export interface AuthProvider {
  readonly method: AuthMethod;
  authenticate(credentials: Credentials): Promise<AuthOutcome>;
}

export class PasswordAuthProvider implements AuthProvider {
  readonly method = 'password' as const;

  constructor(private readonly users: UserRepository) {}

  async authenticate(credentials: Credentials): Promise<AuthOutcome> {
    const user = await this.users.findByEmail(credentials.email);
    if (user === undefined) {
      // Spend comparable work on unknown accounts so response time does not
      // disclose whether an e-mail address is registered.
      await burnPasswordWork(credentials.password);
      return { ok: false, reason: 'invalid_credentials' };
    }

    const valid = await verifyPassword(credentials.password, user.passwordHash);
    if (!valid) {
      return { ok: false, reason: 'invalid_credentials' };
    }
    if (user.disabledAt !== null) {
      return { ok: false, reason: 'account_disabled' };
    }
    return { ok: true, user };
  }
}

export class AuthProviderRegistry {
  private readonly providers = new Map<AuthMethod, AuthProvider>();

  register(provider: AuthProvider): this {
    this.providers.set(provider.method, provider);
    return this;
  }

  /** Returns the provider for `method` or throws UNSUPPORTED. */
  require(method: AuthMethod): AuthProvider {
    const provider = this.providers.get(method);
    if (provider === undefined) {
      throw new ProtocolError('UNSUPPORTED', 'Anmeldeverfahren wird nicht unterstuetzt', {
        logDetail: { method },
      });
    }
    return provider;
  }

  has(method: AuthMethod): boolean {
    return this.providers.has(method);
  }
}
