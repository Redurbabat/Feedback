import { AuthProviderRegistry, PasswordAuthProvider } from './auth/provider.js';
import { SessionService } from './auth/sessionService.js';
import type { AppConfig } from './config.js';
import type { Db } from './db/client.js';
import { createSqliteRepositories } from './db/repositories/index.js';
import type { Repositories } from './db/repositories/types.js';
import { loadOrCreateServerIdentity } from './crypto/serverIdentity.js';
import type { ServerIdentity } from './crypto/serverIdentity.js';
import { RateLimiter } from './http/rateLimit.js';
import { AgentConnectionRegistry } from './services/agentConnections.js';
import { FileTransferHub } from './services/fileTransfers.js';
import { ScreenStreamHub } from './services/screenStreams.js';
import { createAuditLogger } from './services/audit.js';
import type { AuditLogger } from './services/audit.js';
import type { Clock } from './services/clock.js';
import { systemClock } from './services/clock.js';
import { PairingService } from './services/pairingService.js';

/**
 * Reports errors that happen outside of a request (audit writes, background
 * pruning). The HTTP layer installs the real logger once it exists.
 */
export class ErrorSink {
  private handler: ((error: unknown, message: string) => void) | undefined;

  setHandler(handler: (error: unknown, message: string) => void): void {
    this.handler = handler;
  }

  report(error: unknown, message: string): void {
    if (this.handler !== undefined) {
      this.handler(error, message);
      return;
    }
    console.error(message, error);
  }
}

export interface AppContext {
  readonly config: AppConfig;
  readonly repositories: Repositories;
  readonly clock: Clock;
  readonly sessions: SessionService;
  readonly authProviders: AuthProviderRegistry;
  readonly audit: AuditLogger;
  readonly rateLimiter: RateLimiter;
  /**
   * The key that answers "which server is this", so the answer is not just an address. Created on
   * first start and then unchanged: a new key locks out every device paired with the old one.
   */
  readonly serverIdentity: ServerIdentity;
  readonly pairing: PairingService;
  readonly agentConnections: AgentConnectionRegistry;
  readonly fileTransfers: FileTransferHub;
  readonly screenStreams: ScreenStreamHub;
  readonly errors: ErrorSink;
}

export interface CreateAppContextOptions {
  readonly config: AppConfig;
  readonly db: Db;
  readonly clock?: Clock;
}

export function createAppContext(options: CreateAppContextOptions): AppContext {
  const clock = options.clock ?? systemClock;
  const repositories = createSqliteRepositories(options.db);
  const errors = new ErrorSink();

  const audit = createAuditLogger({
    audit: repositories.audit,
    onError: (error) => {
      errors.report(error, 'audit write failed');
    },
  });

  const sessions = new SessionService({
    authSessions: repositories.authSessions,
    users: repositories.users,
    clock,
    cookieSecret: options.config.cookieSecret,
    sessionTtlMs: options.config.sessionTtlMs,
    isProduction: options.config.isProduction,
  });

  const authProviders = new AuthProviderRegistry().register(
    new PasswordAuthProvider(repositories.users),
  );

  const serverIdentity = loadOrCreateServerIdentity(options.config.serverKeyFile);

  const pairing = new PairingService({ repositories, clock, audit });

  return {
    config: options.config,
    repositories,
    clock,
    sessions,
    authProviders,
    audit,
    rateLimiter: new RateLimiter(clock),
    serverIdentity,
    pairing,
    agentConnections: new AgentConnectionRegistry(),
    fileTransfers: new FileTransferHub(),
    screenStreams: new ScreenStreamHub(),
    errors,
  };
}
