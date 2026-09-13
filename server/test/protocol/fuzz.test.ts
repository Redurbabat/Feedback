import { describe, expect, it } from 'vitest';

import {
  FileTransferHub,
  TooManyTransfersError,
  type TransferCancelReason,
  type TransferChannel,
} from '../../src/services/fileTransfers.js';
import {
  ScreenStreamHub,
  TooManyStreamsError,
  type ScreenConsentState,
  type ScreenStopReason,
  type ScreenStreamChannel,
} from '../../src/services/screenStreams.js';

/**
 * Randomised state machine tests for the two hubs (Milestone 7).
 *
 * The hand written tests check the cases somebody thought of. These check the ones nobody did:
 * thousands of random operation sequences, including ones that make no sense, and after every
 * single step the invariants have to still hold.
 *
 * The generator is seeded rather than random. A fuzz test that fails differently on every run is
 * a fuzz test nobody ever fixes - a failing seed here reproduces exactly.
 *
 * It is also deliberately biased towards *valid* traffic, and the coverage assertions at the end
 * of each run are what forced that. A purely random generator looked thorough and reached almost
 * nothing: a frame needs consent, then a start, then a matching chunk index and a rising
 * sequence, so uniform noise ends every stream on its first malformed frame and then checks its
 * invariants against an empty hub forever. Roughly one operation in five is deliberately broken;
 * the rest is what a working device does.
 */

/** mulberry32. Small, fast, and good enough for choosing between fifteen operations. */
function rng(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function pick<T>(next: () => number, values: readonly T[]): T {
  return values[Math.floor(next() * values.length)] as T;
}

function int(next: () => number, min: number, max: number): number {
  return min + Math.floor(next() * (max - min + 1));
}

const DEVICE = 'device-fuzz';
const SESSION = 'session-fuzz';

// ------------------------------------------------------------------ screen ---

interface ScreenObservation {
  ended: boolean;
  endCount: number;
  framesAfterEnd: number;
  deliveredSequences: number[];
  sawKeyframe: boolean;
  deltaBeforeKeyframe: number;
}

function screenChannel(observation: ScreenObservation): ScreenStreamChannel {
  return {
    sendToDevice() {
      // The device side is not under test here.
    },
    status() {
      if (observation.ended) observation.framesAfterEnd += 1;
    },
    started() {
      if (observation.ended) observation.framesAfterEnd += 1;
    },
    frame(frame) {
      if (observation.ended) {
        observation.framesAfterEnd += 1;
        return;
      }
      observation.deliveredSequences.push(frame.sequence);
      if (frame.keyFrame) {
        observation.sawKeyframe = true;
      } else if (!observation.sawKeyframe) {
        observation.deltaBeforeKeyframe += 1;
      }
    },
    end() {
      observation.endCount += 1;
      observation.ended = true;
    },
  };
}

const CONSENT_STATES: readonly ScreenConsentState[] = ['pending', 'granted', 'declined'];
const STOP_REASONS: readonly ScreenStopReason[] = [
  'owner_stopped',
  'client_cancelled',
  'session_expired',
  'timeout',
  'encoder_error',
];

describe('ScreenStreamHub under random traffic', () => {
  for (const seed of [1, 7, 42, 1337, 99991, 2026]) {
    it(`holds its invariants for seed ${seed}`, () => {
      const next = rng(seed);
      const hub = new ScreenStreamHub();
      let deliveredOverall = 0;
      let endedOverall = 0;
      let observation: ScreenObservation | undefined;
      let streamId = 'stream-0';
      let now = 1_000;
      let counter = 0;
      let sequenceCounter = -1;
      let firstFrame = true;

      for (let step = 0; step < 600; step += 1) {
        // Small steps most of the time, so a stream survives long enough to stream; the
        // occasional jump is what exercises the sweeps.
        now += next() < 0.85 ? int(next, 0, 200) : int(next, 0, 900_000);
        // Weighted towards frames: that is what a running stream mostly does.
        const action = next() < 0.5 ? 3 : int(next, 0, 8);
        try {
          switch (action) {
            case 0: {
              counter += 1;
              streamId = `stream-${counter}`;
              observation = {
                ended: false,
                endCount: 0,
                framesAfterEnd: 0,
                deliveredSequences: [],
                sawKeyframe: false,
                deltaBeforeKeyframe: 0,
              };
              sequenceCounter = -1;
              firstFrame = true;
              hub.begin({
                streamId,
                deviceId: DEVICE,
                sessionId: SESSION,
                now,
                channel: screenChannel(observation),
              });
              // A real stream is consented to and started; the fuzzer still gets to send
              // consent and start messages out of order through cases 1 and 2.
              hub.handleConsent(DEVICE, streamId, 'granted', now);
              hub.handleStarted(
                DEVICE,
                streamId,
                { width: 720, height: 1280, codec: 'avc1.42E01E', fps: 15, config: 'AAAAAWc=' },
                now,
              );
              break;
            }
            case 1:
              hub.handleConsent(DEVICE, streamId, pick(next, CONSENT_STATES), now);
              break;
            case 2:
              hub.handleStarted(
                DEVICE,
                streamId,
                {
                  width: int(next, 16, 1280),
                  height: int(next, 16, 1280),
                  codec: 'avc1.42E01E',
                  fps: int(next, 1, 15),
                  config: 'AAAAAWc=',
                },
                now,
              );
              break;
            case 3: {
              // Mostly a well formed frame, so the stream actually runs; every fifth is
              // deliberately impossible - repeated, backwards, or a chunk index out of range.
              const broken = next() < 0.2;
              const sequence = broken ? int(next, -2, sequenceCounter) : sequenceCounter + 1;
              const chunkCount = broken ? int(next, 0, 3) : 1;
              const chunkIndex = broken ? int(next, 0, 3) : 0;
              if (!broken) {
                sequenceCounter += 1;
              }
              hub.handleFrame({
                deviceId: DEVICE,
                streamId,
                sequence,
                chunkIndex,
                chunkCount,
                keyFrame: firstFrame || next() < 0.2,
                timestampUs: int(next, 0, 1_000_000),
                data: Buffer.alloc(int(next, 0, 64)),
                now,
              });
              firstFrame = false;
              break;
            }
            case 4:
              hub.requestKeyframe(streamId, DEVICE);
              break;
            case 5:
              hub.stopFromDevice(DEVICE, streamId, pick(next, STOP_REASONS));
              break;
            case 6:
              hub.stop(streamId, pick(next, STOP_REASONS), 'fuzz');
              break;
            case 7:
              hub.sweep(now);
              break;
            default:
              hub.stopForSession(SESSION, 'session_expired', 'fuzz');
              break;
          }
        } catch (error) {
          // TooManyStreamsError is the documented answer to a second stream, not a crash.
          if (!(error instanceof TooManyStreamsError)) {
            throw error;
          }
        }

        // Invariant 1: never more streams than the protocol allows.
        expect(hub.size).toBeLessThanOrEqual(1);
        if (observation !== undefined) {
          // Invariant 2: a viewer is told exactly once that it is over, and never after that.
          expect(observation.endCount).toBeLessThanOrEqual(1);
          expect(observation.framesAfterEnd).toBe(0);
          // Invariant 3: delivered sequences rise strictly.
          for (let index = 1; index < observation.deliveredSequences.length; index += 1) {
            expect(observation.deliveredSequences[index]).toBeGreaterThan(
              observation.deliveredSequences[index - 1] as number,
            );
          }
          // Invariant 4: no delta frame reaches a decoder that has never seen a keyframe.
          expect(observation.deltaBeforeKeyframe).toBe(0);
          deliveredOverall = Math.max(deliveredOverall, observation.deliveredSequences.length);
          endedOverall += observation.endCount;
        }
      }

      // The invariants above are only worth anything if the run actually reached the states
      // they talk about. Without this, a generator that never delivered a frame would pass
      // every one of them by doing nothing.
      expect(deliveredOverall, 'kein Frame ausgeliefert - der Fuzzer hat nichts erreicht').toBeGreaterThan(0);
      expect(endedOverall, 'kein Strom beendet').toBeGreaterThan(0);
    });
  }
});

// ------------------------------------------------------------------- files ---

interface TransferObservation {
  written: number;
  finished: number;
  failed: number;
}

function transferChannel(observation: TransferObservation): TransferChannel {
  return {
    sendToDevice() {
      // Not under test here.
    },
    write(chunk) {
      observation.written += chunk.length;
    },
    finish() {
      observation.finished += 1;
    },
    fail() {
      observation.failed += 1;
    },
  };
}

const CANCEL_REASONS: readonly TransferCancelReason[] = [
  'client_cancelled',
  'session_expired',
  'timeout',
  'read_error',
];

describe('FileTransferHub under random traffic', () => {
  for (const seed of [3, 11, 77, 4711, 31337]) {
    it(`never hands over more bytes than allowed for seed ${seed}`, () => {
      const next = rng(seed);
      const hub = new FileTransferHub();
      const maxBytes = 4_096;
      let writtenOverall = 0;
      let settledOverall = 0;
      let observation: TransferObservation | undefined;
      let transferId = 'transfer-0';
      let now = 1_000;
      let counter = 0;
      let expectedSequence = 0;

      for (let step = 0; step < 600; step += 1) {
        now += next() < 0.85 ? int(next, 0, 200) : int(next, 0, 90_000);
        try {
          switch (next() < 0.5 ? 1 : int(next, 0, 6)) {
            case 0: {
              counter += 1;
              transferId = `transfer-${counter}`;
              expectedSequence = 0;
              observation = { written: 0, finished: 0, failed: 0 };
              hub.begin({
                transferId,
                deviceId: DEVICE,
                sessionId: SESSION,
                maxBytes,
                now,
                channel: transferChannel(observation),
              });
              break;
            }
            case 1: {
              // Same bias as above: mostly the chunk the hub is waiting for, every fifth one
              // out of order, repeated or negative.
              const broken = next() < 0.2;
              const sequence = broken ? int(next, -1, expectedSequence + 3) : expectedSequence;
              const accepted = hub.handleChunk({
                deviceId: DEVICE,
                transferId,
                sequence,
                data: Buffer.alloc(int(next, 0, 2_048)),
                last: next() < 0.15,
                now,
              });
              expectedSequence = accepted ? expectedSequence + 1 : 0;
              break;
            }
            case 2:
              hub.handleComplete({
                deviceId: DEVICE,
                transferId,
                totalBytes: int(next, 0, 8_192),
                // Mostly nonsense, which is the point: a digest that does not verify must
                // destroy the response rather than shorten it.
                sha256: next() < 0.5 ? 'zz' : 'a'.repeat(64),
              });
              break;
            case 3:
              hub.cancelFromDevice(DEVICE, transferId, pick(next, CANCEL_REASONS));
              break;
            case 4:
              hub.cancel(transferId, pick(next, CANCEL_REASONS), 'fuzz');
              break;
            case 5:
              hub.sweep(now);
              break;
            default:
              hub.cancelForDevice(DEVICE, 'device_revoked', 'fuzz');
              break;
          }
        } catch (error) {
          if (!(error instanceof TooManyTransfersError)) {
            throw error;
          }
        }

        expect(hub.activeCountForDevice(DEVICE)).toBeLessThanOrEqual(2);
        if (observation !== undefined) {
          // Invariant 1: the size limit is a limit, not a suggestion.
          expect(observation.written).toBeLessThanOrEqual(maxBytes);
          // Invariant 2: a response is either completed or destroyed, never both, never twice.
          expect(observation.finished).toBeLessThanOrEqual(1);
          expect(observation.finished + observation.failed).toBeLessThanOrEqual(1);
          writtenOverall = Math.max(writtenOverall, observation.written);
          settledOverall += observation.finished + observation.failed;
        }
      }

      expect(writtenOverall, 'kein Byte durchgereicht - der Fuzzer hat nichts erreicht').toBeGreaterThan(0);
      expect(settledOverall, 'kein Transfer abgeschlossen oder abgebrochen').toBeGreaterThan(0);
    });
  }
});
