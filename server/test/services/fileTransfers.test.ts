import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';

import { FILE_CHUNK_BYTES } from '../../src/constants.js';
import {
  FileTransferHub,
  TooManyTransfersError,
  type TransferCancelReason,
  type TransferChannel,
} from '../../src/services/fileTransfers.js';

interface SentFrame {
  readonly type: string;
  readonly payload: Record<string, unknown>;
}

class FakeChannel implements TransferChannel {
  readonly sent: SentFrame[] = [];
  readonly written: Buffer[] = [];
  finished = false;
  failure: { reason: TransferCancelReason; message: string } | undefined;

  sendToDevice(type: string, payload: Record<string, unknown>): void {
    this.sent.push({ type, payload });
  }

  write(chunk: Buffer): void {
    this.written.push(Buffer.from(chunk));
  }

  finish(): void {
    this.finished = true;
  }

  fail(reason: TransferCancelReason, message: string): void {
    this.failure = { reason, message };
  }

  get body(): Buffer {
    return Buffer.concat(this.written);
  }

  get acked(): number[] {
    return this.sent
      .filter((frame) => frame.type === 'files.download.ack')
      .map((frame) => Number(frame.payload.sequence));
  }

  get cancels(): string[] {
    return this.sent
      .filter((frame) => frame.type === 'files.download.cancel')
      .map((frame) => String(frame.payload.reason));
  }
}

function sha256Hex(value: Buffer): string {
  return createHash('sha256').update(value).digest('hex');
}

function start(
  hub: FileTransferHub,
  channel: FakeChannel,
  overrides: { maxBytes?: number; transferId?: string; deviceId?: string; sessionId?: string } = {},
): string {
  const transferId = overrides.transferId ?? 'transfer-1';
  hub.begin({
    transferId,
    deviceId: overrides.deviceId ?? 'device-a',
    sessionId: overrides.sessionId ?? 'session-1',
    maxBytes: overrides.maxBytes ?? 1_000_000,
    now: 1_000,
    channel,
  });
  return transferId;
}

describe('FileTransferHub', () => {
  it('streams a file through, acks every chunk but the last and verifies the digest', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    const first = Buffer.from('hello ');
    const second = Buffer.from('world');
    const whole = Buffer.concat([first, second]);

    expect(
      hub.handleChunk({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        sequence: 0,
        data: first,
        last: false,
        now: 1_100,
      }),
    ).toBe(true);
    expect(
      hub.handleChunk({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        sequence: 1,
        data: second,
        last: true,
        now: 1_200,
      }),
    ).toBe(true);

    // The final chunk is not acked: there is nothing left to unblock.
    expect(channel.acked).toEqual([0]);

    expect(
      hub.handleComplete({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        totalBytes: whole.length,
        sha256: sha256Hex(whole),
      }),
    ).toBe(true);

    expect(channel.finished).toBe(true);
    expect(channel.failure).toBeUndefined();
    expect(channel.body.toString()).toBe('hello world');
    expect(hub.size).toBe(0);
  });

  it('carries an empty file as one final chunk', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    expect(
      hub.handleChunk({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        sequence: 0,
        data: Buffer.alloc(0),
        last: true,
        now: 1_100,
      }),
    ).toBe(true);
    expect(
      hub.handleComplete({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        totalBytes: 0,
        sha256: sha256Hex(Buffer.alloc(0)),
      }),
    ).toBe(true);
    expect(channel.finished).toBe(true);
    expect(channel.body.length).toBe(0);
  });

  it.each([
    ['a gap in the sequence', 1],
    ['a repeated sequence number', 0],
  ])('ends the transfer on %s', (_label, secondSequence) => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data: Buffer.from('a'),
      last: false,
      now: 1_100,
    });
    const accepted = hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: secondSequence === 0 ? 0 : 2,
      data: Buffer.from('b'),
      last: false,
      now: 1_200,
    });

    expect(accepted).toBe(false);
    expect(channel.finished).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
    expect(channel.cancels).toEqual(['read_error']);
    expect(hub.size).toBe(0);
  });

  it('refuses a chunk that arrives after the final one', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data: Buffer.from('a'),
      last: true,
      now: 1_100,
    });
    const accepted = hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 1,
      data: Buffer.from('b'),
      last: false,
      now: 1_200,
    });

    expect(accepted).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
  });

  it('refuses a chunk larger than FILE_CHUNK_BYTES', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    const accepted = hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data: Buffer.alloc(FILE_CHUNK_BYTES + 1),
      last: false,
      now: 1_100,
    });

    expect(accepted).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
    expect(channel.written).toEqual([]);
  });

  it('stops a transfer that grows past the allowed size', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel, { maxBytes: 4 });

    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data: Buffer.from('abc'),
      last: false,
      now: 1_100,
    });
    const accepted = hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 1,
      data: Buffer.from('de'),
      last: false,
      now: 1_200,
    });

    expect(accepted).toBe(false);
    expect(channel.failure?.reason).toBe('too_large');
    expect(channel.cancels).toEqual(['too_large']);
  });

  it('rejects a completion that claims the wrong length', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    const data = Buffer.from('abc');
    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data,
      last: true,
      now: 1_100,
    });

    expect(
      hub.handleComplete({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        totalBytes: 99,
        sha256: sha256Hex(data),
      }),
    ).toBe(false);
    expect(channel.finished).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
  });

  it('rejects a completion before the final chunk arrived', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    const data = Buffer.from('abc');
    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data,
      last: false,
      now: 1_100,
    });

    expect(
      hub.handleComplete({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        totalBytes: data.length,
        sha256: sha256Hex(data),
      }),
    ).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
  });

  it.each([
    ['a digest of different content', sha256Hex(Buffer.from('something else'))],
    ['a digest that is not hex', 'z'.repeat(64)],
    ['a digest of the wrong length', 'ab'],
  ])('fails the response on %s', (_label, digest) => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    const data = Buffer.from('abc');
    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data,
      last: true,
      now: 1_100,
    });

    expect(
      hub.handleComplete({
        deviceId: 'device-a',
        transferId: 'transfer-1',
        totalBytes: data.length,
        sha256: digest,
      }),
    ).toBe(false);
    // The bytes already went out, so the only honest outcome is a broken response
    // rather than a short file that looks complete.
    expect(channel.finished).toBe(false);
    expect(channel.failure?.reason).toBe('read_error');
  });

  it('treats a transfer id of another device as unknown', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    expect(
      hub.handleChunk({
        deviceId: 'device-b',
        transferId: 'transfer-1',
        sequence: 0,
        data: Buffer.from('a'),
        last: false,
        now: 1_100,
      }),
    ).toBe(false);
    // The real transfer is untouched: no data, no failure, still open.
    expect(channel.written).toEqual([]);
    expect(channel.failure).toBeUndefined();
    expect(hub.size).toBe(1);
  });

  it('caps concurrent transfers per device but not across devices', () => {
    const hub = new FileTransferHub(2);
    start(hub, new FakeChannel(), { transferId: 't1' });
    start(hub, new FakeChannel(), { transferId: 't2' });

    expect(() => start(hub, new FakeChannel(), { transferId: 't3' })).toThrow(
      TooManyTransfersError,
    );
    expect(() =>
      start(hub, new FakeChannel(), { transferId: 't4', deviceId: 'device-b' }),
    ).not.toThrow();
  });

  it('refuses a duplicate transfer id', () => {
    const hub = new FileTransferHub();
    start(hub, new FakeChannel(), { transferId: 'same' });
    expect(() => start(hub, new FakeChannel(), { transferId: 'same' })).toThrow(
      /duplicate transfer id/,
    );
  });

  it('ends every transfer of a revoked session and leaves the others alone', () => {
    const hub = new FileTransferHub(4);
    const doomedOne = new FakeChannel();
    const doomedTwo = new FakeChannel();
    const survivor = new FakeChannel();
    start(hub, doomedOne, { transferId: 't1', sessionId: 'session-doomed' });
    start(hub, doomedTwo, { transferId: 't2', sessionId: 'session-doomed' });
    start(hub, survivor, { transferId: 't3', sessionId: 'session-other' });

    expect(hub.cancelForSession('session-doomed', 'session_expired', 'session gone')).toBe(2);
    expect(doomedOne.failure?.reason).toBe('session_expired');
    expect(doomedTwo.failure?.reason).toBe('session_expired');
    expect(doomedOne.cancels).toEqual(['session_expired']);
    expect(survivor.failure).toBeUndefined();
    expect(hub.size).toBe(1);
  });

  it('ends every transfer of a revoked device', () => {
    const hub = new FileTransferHub(4);
    const channel = new FakeChannel();
    start(hub, channel, { transferId: 't1' });

    expect(hub.cancelForDevice('device-a', 'device_revoked', 'device revoked')).toBe(1);
    expect(channel.failure?.reason).toBe('device_revoked');
  });

  it('ends a transfer that stopped making progress', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    expect(hub.sweep(1_000 + 5_000, 10_000)).toBe(0);
    expect(hub.sweep(1_000 + 10_000, 10_000)).toBe(1);
    expect(channel.failure?.reason).toBe('timeout');
    expect(channel.cancels).toEqual(['timeout']);
  });

  it('keeps the idle clock alive while chunks keep arriving', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    hub.handleChunk({
      deviceId: 'device-a',
      transferId: 'transfer-1',
      sequence: 0,
      data: Buffer.from('a'),
      last: false,
      now: 9_000,
    });

    expect(hub.sweep(1_000 + 10_000, 10_000)).toBe(0);
    expect(hub.sweep(9_000 + 10_000, 10_000)).toBe(1);
  });

  it('does not tell the device to stop when the device is the one cancelling', () => {
    const hub = new FileTransferHub();
    const channel = new FakeChannel();
    start(hub, channel);

    expect(hub.cancelFromDevice('device-a', 'transfer-1', 'read_error')).toBe(true);
    expect(channel.failure?.reason).toBe('read_error');
    expect(channel.cancels).toEqual([]);
    expect(hub.size).toBe(0);
  });
});
