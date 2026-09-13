import { createHash, timingSafeEqual } from 'node:crypto';

import {
  FILE_CHUNK_BYTES,
  FILE_MAX_CONCURRENT_TRANSFERS,
  FILE_TRANSFER_IDLE_TIMEOUT_MS,
} from '../constants.js';

/** Reasons a transfer may end early (protocol section 8.3.7). */
export const TRANSFER_CANCEL_REASONS = [
  'client_cancelled',
  'session_expired',
  'capability_revoked',
  'device_revoked',
  'too_large',
  'read_error',
  'timeout',
] as const;

export type TransferCancelReason = (typeof TRANSFER_CANCEL_REASONS)[number];

const SHA256_HEX = /^[0-9a-f]{64}$/i;

export function isTransferCancelReason(value: unknown): value is TransferCancelReason {
  return (TRANSFER_CANCEL_REASONS as readonly unknown[]).includes(value);
}

/**
 * Everything one transfer needs from the outside world.
 *
 * Keeping this an interface is what makes the hub testable without a socket or an
 * HTTP response: the rules below are the security-relevant part, so they must be
 * exercisable directly.
 */
export interface TransferChannel {
  /** Sends one frame to the device (`files.download.ack` or `files.download.cancel`). */
  sendToDevice(type: string, payload: Record<string, unknown>): void;
  /** Hands one verified chunk to the waiting HTTP response. */
  write(chunk: Buffer): void;
  /** Every byte arrived, the length matched and the digest verified. */
  finish(): void;
  /** The transfer died. The HTTP response must not look like a complete file. */
  fail(reason: TransferCancelReason, message: string): void;
}

export class TooManyTransfersError extends Error {
  constructor() {
    super('too many concurrent transfers for this device');
    this.name = 'TooManyTransfersError';
  }
}

interface ActiveTransfer {
  readonly transferId: string;
  readonly deviceId: string;
  readonly sessionId: string;
  readonly maxBytes: number;
  readonly channel: TransferChannel;
  readonly digest: ReturnType<typeof createHash>;
  /** Sequence number the next chunk must carry. */
  nextSequence: number;
  receivedBytes: number;
  lastProgressAt: number;
  sawLast: boolean;
  done: boolean;
}

/**
 * Bridges `files.download.*` frames onto an HTTP response.
 *
 * The server is a pipe, not a cache: a chunk is verified, written through and
 * forgotten. Nothing reaches disk, so an aborted transfer leaves no partial file
 * behind (protocol section 8.3.8).
 *
 * The sequence rules are not bookkeeping - they are what stops a device from
 * reordering, repeating or padding a file it already got permission to send. A
 * violation ends the transfer instead of being repaired.
 */
export class FileTransferHub {
  private readonly byTransfer = new Map<string, ActiveTransfer>();

  constructor(private readonly maxConcurrentPerDevice: number = FILE_MAX_CONCURRENT_TRANSFERS) {}

  begin(input: {
    transferId: string;
    deviceId: string;
    sessionId: string;
    maxBytes: number;
    now: number;
    channel: TransferChannel;
  }): void {
    if (this.byTransfer.has(input.transferId)) {
      throw new Error('duplicate transfer id');
    }
    if (this.activeCountForDevice(input.deviceId) >= this.maxConcurrentPerDevice) {
      throw new TooManyTransfersError();
    }
    this.byTransfer.set(input.transferId, {
      transferId: input.transferId,
      deviceId: input.deviceId,
      sessionId: input.sessionId,
      maxBytes: input.maxBytes,
      channel: input.channel,
      digest: createHash('sha256'),
      nextSequence: 0,
      receivedBytes: 0,
      lastProgressAt: input.now,
      sawLast: false,
      done: false,
    });
  }

  /**
   * Accepts one chunk. Returns false when the frame was rejected, in which case the
   * transfer has already been ended and the caller should answer `INVALID_MESSAGE`.
   */
  handleChunk(input: {
    deviceId: string;
    transferId: string;
    sequence: number;
    data: Buffer;
    last: boolean;
    now: number;
  }): boolean {
    const transfer = this.lookup(input.deviceId, input.transferId);
    if (transfer === undefined) {
      return false;
    }

    if (transfer.sawLast) {
      // A chunk after the final one means the device is still talking about a file
      // it already finished. Nothing good can come from accepting it.
      this.abort(transfer, 'read_error', 'chunk after the final chunk');
      return false;
    }
    if (input.sequence !== transfer.nextSequence) {
      this.abort(transfer, 'read_error', 'chunk out of sequence');
      return false;
    }
    if (input.data.length > FILE_CHUNK_BYTES) {
      this.abort(transfer, 'read_error', 'chunk exceeds FILE_CHUNK_BYTES');
      return false;
    }

    const total = transfer.receivedBytes + input.data.length;
    if (total > transfer.maxBytes) {
      this.abort(transfer, 'too_large', 'transfer exceeds the allowed size');
      return false;
    }

    transfer.receivedBytes = total;
    transfer.nextSequence += 1;
    transfer.lastProgressAt = input.now;
    transfer.digest.update(input.data);
    if (input.data.length > 0) {
      transfer.channel.write(input.data);
    }

    if (input.last) {
      transfer.sawLast = true;
    } else {
      // Backpressure: the device holds at most FILE_TRANSFER_WINDOW unacknowledged
      // chunks, so the ack is what lets it continue.
      transfer.channel.sendToDevice('files.download.ack', {
        transferId: transfer.transferId,
        sequence: input.sequence,
      });
    }
    return true;
  }

  /**
   * Closes a transfer after the device claims it is done. Returns false when the
   * claim does not match what actually arrived.
   */
  handleComplete(input: {
    deviceId: string;
    transferId: string;
    totalBytes: number;
    sha256: string;
  }): boolean {
    const transfer = this.lookup(input.deviceId, input.transferId);
    if (transfer === undefined) {
      return false;
    }
    if (!transfer.sawLast) {
      this.abort(transfer, 'read_error', 'complete before the final chunk');
      return false;
    }
    if (input.totalBytes !== transfer.receivedBytes) {
      this.abort(transfer, 'read_error', 'totalBytes does not match the received bytes');
      return false;
    }

    // Buffer.from(..., 'hex') does not throw on malformed input, it silently stops at
    // the first bad pair. Checking the shape first is what keeps "zz" from arriving
    // here as an empty buffer.
    if (!SHA256_HEX.test(input.sha256)) {
      this.abort(transfer, 'read_error', 'sha256 is not a 64 character hex digest');
      return false;
    }

    const expected = transfer.digest.digest();
    const actual = Buffer.from(input.sha256, 'hex');
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
      // The bytes are already on their way to the client, so this cannot "undo" the
      // download - it destroys the response instead, which is what makes a truncated
      // or altered file visible as a failure rather than as a short file.
      this.abort(transfer, 'read_error', 'sha256 does not match the received content');
      return false;
    }

    transfer.done = true;
    this.byTransfer.delete(transfer.transferId);
    transfer.channel.finish();
    return true;
  }

  /** Ends a transfer the device cancelled. */
  cancelFromDevice(deviceId: string, transferId: string, reason: TransferCancelReason): boolean {
    const transfer = this.lookup(deviceId, transferId);
    if (transfer === undefined) {
      return false;
    }
    this.finish(transfer, reason, 'device cancelled the transfer', false);
    return true;
  }

  /** Ends a transfer from the server side and tells the device to stop reading. */
  cancel(transferId: string, reason: TransferCancelReason, message: string): boolean {
    const transfer = this.byTransfer.get(transferId);
    if (transfer === undefined) {
      return false;
    }
    this.abort(transfer, reason, message);
    return true;
  }

  cancelForSession(sessionId: string, reason: TransferCancelReason, message: string): number {
    return this.cancelWhere((transfer) => transfer.sessionId === sessionId, reason, message);
  }

  cancelForDevice(deviceId: string, reason: TransferCancelReason, message: string): number {
    return this.cancelWhere((transfer) => transfer.deviceId === deviceId, reason, message);
  }

  /** Ends transfers that made no progress within FILE_TRANSFER_IDLE_TIMEOUT_MS. */
  sweep(now: number, idleTimeoutMs: number = FILE_TRANSFER_IDLE_TIMEOUT_MS): number {
    return this.cancelWhere(
      (transfer) => now - transfer.lastProgressAt >= idleTimeoutMs,
      'timeout',
      'transfer made no progress',
    );
  }

  activeCountForDevice(deviceId: string): number {
    let count = 0;
    for (const transfer of this.byTransfer.values()) {
      if (transfer.deviceId === deviceId) {
        count += 1;
      }
    }
    return count;
  }

  get size(): number {
    return this.byTransfer.size;
  }

  private cancelWhere(
    predicate: (transfer: ActiveTransfer) => boolean,
    reason: TransferCancelReason,
    message: string,
  ): number {
    let cancelled = 0;
    for (const transfer of [...this.byTransfer.values()]) {
      if (predicate(transfer)) {
        this.abort(transfer, reason, message);
        cancelled += 1;
      }
    }
    return cancelled;
  }

  private lookup(deviceId: string, transferId: string): ActiveTransfer | undefined {
    const transfer = this.byTransfer.get(transferId);
    // A transfer id from another device is treated as unknown rather than as a
    // permission error: it must not reveal that the id exists at all.
    return transfer !== undefined && transfer.deviceId === deviceId ? transfer : undefined;
  }

  private abort(transfer: ActiveTransfer, reason: TransferCancelReason, message: string): void {
    this.finish(transfer, reason, message, true);
  }

  private finish(
    transfer: ActiveTransfer,
    reason: TransferCancelReason,
    message: string,
    tellDevice: boolean,
  ): void {
    if (transfer.done) {
      return;
    }
    transfer.done = true;
    this.byTransfer.delete(transfer.transferId);
    if (tellDevice) {
      transfer.channel.sendToDevice('files.download.cancel', {
        transferId: transfer.transferId,
        reason,
      });
    }
    transfer.channel.fail(reason, message);
  }
}
