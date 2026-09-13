import { randomBytes } from 'node:crypto';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { openDatabase } from '../../src/db/client.js';
import type { DatabaseHandle } from '../../src/db/client.js';
import { runMigrations } from '../../src/db/migrate.js';
import { createSqliteRepositories } from '../../src/db/repositories/index.js';
import type { Repositories } from '../../src/db/repositories/types.js';
import { sha256Hex } from '../../src/crypto/tokens.js';

describe('migrations and repository layer', () => {
  let handle: DatabaseHandle;
  let repositories: Repositories;

  beforeEach(() => {
    handle = openDatabase(':memory:');
    runMigrations(handle.sqlite);
    repositories = createSqliteRepositories(handle.db);
  });

  afterEach(() => {
    handle.close();
  });

  it('creates every table required by the milestone', () => {
    const tables = handle.sqlite
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
      .all()
      .map((row) => (row as { name: string }).name);

    for (const expected of [
      'audit_events',
      'auth_sessions',
      'device_capabilities',
      'device_tokens',
      'devices',
      'pairing_nonces',
      'pairing_sessions',
      'remote_sessions',
      'users',
    ]) {
      expect(tables).toContain(expected);
    }
  });

  it('is idempotent when run twice', () => {
    const second = runMigrations(handle.sqlite);
    expect(second.applied).toHaveLength(0);
    expect(second.skipped.length).toBeGreaterThan(0);
  });

  it('enforces the unique e-mail and normalises it', async () => {
    const passwordHash = 'scrypt$N=4096,r=8,p=1$c2FsdA$aGFzaA';
    await repositories.users.create({ email: 'Owner@Example.test', passwordHash });

    const found = await repositories.users.findByEmail('owner@example.TEST');
    expect(found?.email).toBe('Owner@Example.test');

    await expect(
      repositories.users.create({ email: 'OWNER@example.test', passwordHash }),
    ).rejects.toThrow();
    expect(await repositories.users.count()).toBe(1);
  });

  it('stores a nonce once per device and reports a replay', async () => {
    const nonceHash = sha256Hex(randomBytes(18).toString('base64url'));
    const expiresAt = Date.now() + 60_000;

    expect(await repositories.pairingNonces.tryInsert('fb-a', nonceHash, expiresAt)).toBe(true);
    expect(await repositories.pairingNonces.tryInsert('fb-a', nonceHash, expiresAt)).toBe(false);
    // A different device may use the same nonce value.
    expect(await repositories.pairingNonces.tryInsert('fb-b', nonceHash, expiresAt)).toBe(true);

    expect(await repositories.pairingNonces.deleteExpired(expiresAt + 1)).toBe(2);
    expect(await repositories.pairingNonces.tryInsert('fb-a', nonceHash, expiresAt)).toBe(true);
  });

  it('re-pairing updates the device record and clears the revocation', async () => {
    const user = await repositories.users.create({
      email: 'owner@example.test',
      passwordHash: 'scrypt$N=4096,r=8,p=1$c2FsdA$aGFzaA',
    });
    const base = {
      ownerId: user.id,
      deviceId: 'fb-0123456789abcdef01234567',
      publicKey: 'QUJD',
      fingerprint: 'aaaa:bbbb',
      name: 'Erstes Geraet',
      platform: 'android',
      osVersion: '16',
      sdkInt: 36,
      appVersion: '0.2.0',
    };

    const created = await repositories.devices.upsert(base);
    await repositories.devices.revoke(created.id, Date.now());
    expect((await repositories.devices.findById(created.id))?.revokedAt).not.toBeNull();

    const repaired = await repositories.devices.upsert({ ...base, name: 'Neuer Name' });
    expect(repaired.id).toBe(created.id);
    expect(repaired.createdAt).toBe(created.createdAt);
    expect(repaired.name).toBe('Neuer Name');
    expect(repaired.revokedAt).toBeNull();
  });

  it('revokes every device token of a device', async () => {
    const user = await repositories.users.create({
      email: 'owner@example.test',
      passwordHash: 'scrypt$N=4096,r=8,p=1$c2FsdA$aGFzaA',
    });
    const device = await repositories.devices.upsert({
      ownerId: user.id,
      deviceId: 'fb-0123456789abcdef01234567',
      publicKey: 'QUJD',
      fingerprint: 'aaaa:bbbb',
      name: 'Geraet',
      platform: 'android',
      osVersion: '16',
      sdkInt: 36,
      appVersion: '0.2.0',
    });

    const tokenHash = sha256Hex(randomBytes(32).toString('base64url'));
    await repositories.deviceTokens.create({ deviceId: device.id, tokenHash });
    expect(await repositories.deviceTokens.findActiveByTokenHash(tokenHash, Date.now())).toBeDefined();

    expect(await repositories.deviceTokens.revokeAllForDevice(device.id, Date.now())).toBe(1);
    expect(
      await repositories.deviceTokens.findActiveByTokenHash(tokenHash, Date.now()),
    ).toBeUndefined();
  });

  it('never resolves an ambiguous display code', async () => {
    const now = Date.now();
    const displayCodeHash = sha256Hex('123456');
    const template = {
      displayCodeHash,
      deviceId: 'fb-0123456789abcdef01234567',
      publicKey: 'QUJD',
      fingerprint: 'aaaa:bbbb',
      metadata: '{}',
      deviceSecretHash: sha256Hex('secret'),
      expiresAt: now + 60_000,
    };

    await repositories.pairingSessions.create({ ...template, ticketHash: sha256Hex('t1') });
    expect(
      await repositories.pairingSessions.findPendingByDisplayCodeHash(displayCodeHash, now),
    ).toBeDefined();

    await repositories.pairingSessions.create({ ...template, ticketHash: sha256Hex('t2') });
    expect(
      await repositories.pairingSessions.findPendingByDisplayCodeHash(displayCodeHash, now),
    ).toBeUndefined();
    expect(
      await repositories.pairingSessions.countPendingByDisplayCodeHash(displayCodeHash, now),
    ).toBe(2);
  });

  it('expires stale pending sessions', async () => {
    const now = Date.now();
    const session = await repositories.pairingSessions.create({
      ticketHash: sha256Hex('t1'),
      displayCodeHash: sha256Hex('123456'),
      deviceId: 'fb-0123456789abcdef01234567',
      publicKey: 'QUJD',
      fingerprint: 'aaaa:bbbb',
      metadata: '{}',
      deviceSecretHash: sha256Hex('secret'),
      expiresAt: now - 1,
    });

    expect(await repositories.pairingSessions.expireStale(now)).toBe(1);
    expect((await repositories.pairingSessions.findById(session.id))?.status).toBe('expired');
  });

  it('keeps audit details free of secret keys', async () => {
    const event = await repositories.audit.record({
      eventType: 'pairing.start',
      result: 'success',
      deviceId: 'fb-0123456789abcdef01234567',
      detail: { reason: 'ok' },
    });
    expect(event.detail).toBe('{"reason":"ok"}');

    const listed = await repositories.audit.listForDevice('fb-0123456789abcdef01234567', 10);
    expect(listed).toHaveLength(1);
  });
});
