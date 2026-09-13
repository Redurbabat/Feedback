import { z } from 'zod';

import {
  DEVICE_NAME_MAX,
  FILE_CHUNK_BYTES,
  SCREEN_CHUNK_BYTES,
  SCREEN_MAX_DIMENSION,
  SCREEN_MAX_FPS,
} from '../constants.js';

/**
 * The wire schemas of protocol v1.
 *
 * A module of their own rather than internals of the agent route, for one reason: they are the
 * only machine readable statement of what the device is allowed to send, so they have to be
 * reachable from a test. `test/protocol/fixtures.test.ts` checks them against
 * `protocol/fixtures/`, which the Android tests read as well - that shared file is what keeps
 * two independent implementations of the same protocol from drifting apart in silence.
 *
 * Every schema is `.strict()`: an unknown field is a refusal, not a curiosity.
 */

export const MESSAGE_ID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const MAX_MESSAGE_IDS_PER_CONNECTION = 8192;

export const capabilitySchema = z.enum([
  'system.info',
  'files.read',
  'media.photos.read',
  'media.videos.read',
  'screen.view',
  'screen.control',
  'clipboard.read',
  'clipboard.write',
]);

export function safeText(max: number) {
  return z
    .string()
    .min(1)
    .max(max)
    .refine((value) => !/\p{Cc}/u.test(value), {
      message: 'enthaelt unzulaessige Steuerzeichen',
    });
}

export function utcTimestamp() {
  return z
    .string()
    .min(20)
    .max(40)
    .refine((value) => value.endsWith('Z') && Number.isFinite(Date.parse(value)), {
      message: 'muss ein UTC-Zeitstempel sein',
    });
}

export const envelopeSchema = z
  .object({
    version: z.number().int(),
    type: z.string().min(1).max(64),
    messageId: z.string().regex(MESSAGE_ID_V4, 'muss UUID v4 sein'),
    sessionId: z.string().uuid().nullable(),
    // Message level correlation (protocol section 7). Present on answers, absent on requests.
    relatesTo: z.string().regex(MESSAGE_ID_V4, 'muss UUID v4 sein').nullish(),
    timestamp: z.string().min(20).max(40),
    payload: z.unknown(),
  })
  .strict();

export const FILES_RESPONSE_TYPES = new Set([
  'files.shares.response',
  'files.list.response',
  'files.metadata.response',
]);

export const transferIdSchema = z.string().uuid();

export const downloadChunkSchema = z
  .object({
    transferId: transferIdSchema,
    sequence: z.number().int().min(0),
    data: z.string().max(Math.ceil((FILE_CHUNK_BYTES * 4) / 3) + 8),
    last: z.boolean(),
  })
  .strict();

export const downloadCompleteSchema = z
  .object({
    transferId: transferIdSchema,
    totalBytes: z.number().int().min(0),
    sha256: z.string().regex(/^[0-9a-f]{64}$/iu, 'muss ein 64-stelliger Hex-Digest sein'),
  })
  .strict();

export const downloadCancelSchema = z
  .object({
    transferId: transferIdSchema,
    reason: z.string().max(32),
  })
  .strict();

export const streamIdSchema = z.string().uuid();

export const screenConsentSchema = z
  .object({
    streamId: streamIdSchema,
    state: z.enum(['pending', 'granted', 'declined']),
  })
  .strict();

/**
 * What the encoder actually produced.
 *
 * Every number is bounded by the protocol limit rather than trusted: a device that
 * reports 8000x8000 at 120 fps is either broken or lying, and both are answered the
 * same way.
 */
export const screenStartedSchema = z
  .object({
    streamId: streamIdSchema,
    width: z.number().int().min(16).max(SCREEN_MAX_DIMENSION),
    height: z.number().int().min(16).max(SCREEN_MAX_DIMENSION),
    codec: z
      .string()
      .min(4)
      .max(32)
      .regex(/^[A-Za-z0-9.-]+$/u, 'muss ein Codec-String wie avc1.42E01E sein'),
    fps: z.number().int().min(1).max(SCREEN_MAX_FPS),
    config: z.string().max(4096),
  })
  .strict();

export const screenFrameSchema = z
  .object({
    streamId: streamIdSchema,
    sequence: z.number().int().min(0),
    chunkIndex: z.number().int().min(0),
    chunkCount: z.number().int().min(1),
    keyFrame: z.boolean(),
    timestampUs: z.number().int().min(0),
    data: z.string().max(Math.ceil((SCREEN_CHUNK_BYTES * 4) / 3) + 8),
  })
  .strict();

export const screenStopSchema = z
  .object({
    streamId: streamIdSchema,
    reason: z.string().max(32),
  })
  .strict();

/**
 * Decodes a chunk strictly.
 *
 * Buffer.from accepts sloppy base64 and silently drops what it cannot read, so the
 * only way to know the device sent what it meant to is to re-encode and compare.
 */
export function decodeChunk(data: string): Buffer | undefined {
  const decoded = Buffer.from(data, 'base64');
  return decoded.toString('base64') === data ? decoded : undefined;
}

export const helloPayloadSchema = z
  .object({
    appVersion: safeText(32),
    osVersion: safeText(32),
    sdkInt: z.number().int().min(1).max(10_000),
    deviceName: safeText(DEVICE_NAME_MAX),
    grantedCapabilities: z.array(capabilitySchema).max(8),
  })
  .strict();

export const heartbeatPayloadSchema = z.object({}).strict();
export const capabilityStatePayloadSchema = z
  .object({ grantedCapabilities: z.array(capabilitySchema).max(8) })
  .strict();

export const systemInfoPayloadSchema = z
  .object({
    manufacturer: safeText(64),
    model: safeText(128),
    osVersion: safeText(32),
    sdkInt: z.number().int().min(1).max(10_000),
    appVersion: safeText(32),
    batteryPercent: z.number().int().min(0).max(100),
    charging: z.boolean(),
    storageTotalBytes: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    storageFreeBytes: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    networkType: z.enum(['wifi', 'cellular', 'ethernet', 'other', 'none']),
    deviceTime: utcTimestamp(),
    lastAgentActivity: utcTimestamp(),
  })
  .strict()
  .superRefine((value, ctx) => {
    if (value.storageFreeBytes > value.storageTotalBytes) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['storageFreeBytes'],
        message: 'darf nicht groesser als storageTotalBytes sein',
      });
    }
  });
