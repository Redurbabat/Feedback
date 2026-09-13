import { ConfigError, loadConfig } from './config.js';
import { bootstrapFirstUser } from './bootstrap.js';
import { createAppContext } from './context.js';
import { openDatabase } from './db/client.js';
import { runMigrations } from './db/migrate.js';
import { buildApp } from './http/app.js';
import { NONCE_RETENTION_MS } from './constants.js';

/** Background house keeping interval for expired rows. */
const MAINTENANCE_INTERVAL_MS = 5 * 60_000;
const HUB_SWEEP_INTERVAL_MS = 5_000;

async function main(): Promise<void> {
  const config = loadConfig();
  const handle = openDatabase(config.databaseFile);

  const migrations = runMigrations(handle.sqlite);
  const context = createAppContext({ config, db: handle.db });
  const app = await buildApp(context);

  app.log.info(
    { applied: migrations.applied.length, total: migrations.applied.length + migrations.skipped.length },
    'migrations ready',
  );

  const bootstrap = await bootstrapFirstUser(context.repositories.users, config.bootstrap);
  if (bootstrap.created) {
    // The password is never logged, not even redacted.
    app.log.warn('bootstrap user created from environment - rotate the password after first login');
  }

  const maintenance = setInterval(() => {
    void (async () => {
      try {
        const now = context.clock.now();
        await context.repositories.pairingSessions.expireStale(now);
        await context.repositories.pairingNonces.deleteExpired(now - NONCE_RETENTION_MS);
        await context.sessions.pruneExpired();
      } catch (error) {
        app.log.error({ err: error }, 'maintenance run failed');
      }
    })();
  }, MAINTENANCE_INTERVAL_MS);
  maintenance.unref();

  /**
   * Second, much faster loop for the two transfer hubs.
   *
   * Their idle timeouts are measured in seconds (15 for a screen stream, 30 for a
   * file transfer), while the maintenance run above is on a five minute cycle.
   * Sweeping them there would have turned both limits into fiction: a stalled stream
   * would have kept a response and a MediaProjection alive for minutes.
   */
  const hubSweep = setInterval(() => {
    const now = context.clock.now();
    context.fileTransfers.sweep(now);
    context.screenStreams.sweep(now);
  }, HUB_SWEEP_INTERVAL_MS);
  hubSweep.unref();

  const shutdown = (signal: string): void => {
    app.log.info({ signal }, 'shutting down');
    clearInterval(maintenance);
    clearInterval(hubSweep);
    void app.close().then(
      () => {
        handle.close();
        process.exit(0);
      },
      (error: unknown) => {
        app.log.error({ err: error }, 'shutdown failed');
        process.exit(1);
      },
    );
  };

  process.on('SIGINT', () => {
    shutdown('SIGINT');
  });
  process.on('SIGTERM', () => {
    shutdown('SIGTERM');
  });

  await app.listen({ host: config.host, port: config.port });
}

main().catch((error: unknown) => {
  if (error instanceof ConfigError) {
    console.error(error.message);
  } else {
    console.error(error);
  }
  process.exitCode = 1;
});
