import { describe, expect, it } from 'vitest';

import { SseParser, decodeBase64, interpretScreenEvent } from './screen.ts';

/**
 * Randomised tests for the stream parsing (Milestone 7).
 *
 * The SSE parser has one job that cannot be checked by looking at it: producing the same events
 * no matter where the network split the bytes. A hand written test picks three split points; this
 * one picks thousands, including inside a UTF-8 character's worth of ASCII, between the two
 * newlines that end an event, and in the middle of a base64 payload.
 *
 * Seeded, so a failure reproduces.
 */

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

function int(next: () => number, min: number, max: number): number {
  return min + Math.floor(next() * (max - min + 1));
}

interface Expected {
  readonly event: string;
  readonly data: string;
}

/** Builds a stream of events plus the keep-alive comments the server really sends. */
function buildStream(next: () => number): { text: string; expected: Expected[] } {
  const expected: Expected[] = [];
  let text = '';
  const count = int(next, 1, 25);
  for (let index = 0; index < count; index += 1) {
    if (next() < 0.25) {
      // A comment carries no data and must not become an event.
      text += ': ping\n\n';
      continue;
    }
    const kind = int(next, 0, 3);
    if (kind === 0) {
      const data = JSON.stringify({ state: 'pending' });
      expected.push({ event: 'status', data });
      text += `event: status\ndata: ${data}\n\n`;
    } else if (kind === 1) {
      const payload = 'A'.repeat(int(next, 0, 40) * 4);
      const data = JSON.stringify({
        sequence: index,
        keyFrame: index === 0,
        timestampUs: index * 66_000,
        data: payload,
      });
      expected.push({ event: 'frame', data });
      text += `event: frame\ndata: ${data}\n\n`;
    } else if (kind === 2) {
      const data = JSON.stringify({ reason: 'owner_stopped' });
      expected.push({ event: 'end', data });
      text += `event: end\ndata: ${data}\n\n`;
    } else {
      // Several data lines: legal SSE, and the case a naive parser gets wrong.
      expected.push({ event: 'note', data: 'one\ntwo' });
      text += 'event: note\ndata: one\ndata: two\n\n';
    }
  }
  return { text, expected };
}

describe('SseParser under random chunking', () => {
  for (const seed of [2, 13, 101, 5150, 90210]) {
    it(`produces the same events no matter where the bytes are cut, seed ${seed}`, () => {
      const next = rng(seed);
      for (let round = 0; round < 40; round += 1) {
        const { text, expected } = buildStream(next);
        const parser = new SseParser();
        const received: Expected[] = [];

        let offset = 0;
        while (offset < text.length) {
          // Chunks of one byte are as legitimate as chunks of two hundred.
          const size = int(next, 1, 200);
          received.push(...parser.push(text.slice(offset, offset + size)));
          offset += size;
        }

        expect(received).toEqual(expected);
      }
    });
  }

  it('holds nothing back once an event is complete', () => {
    // Latency, not correctness: an event must not wait for the next chunk to arrive.
    const parser = new SseParser();
    expect(parser.push('event: a\ndata: 1\n\nevent: b\ndata: 2\n')).toHaveLength(1);
  });
});

describe('interpretScreenEvent under random input', () => {
  const NAMES = ['open', 'status', 'config', 'frame', 'end', 'message', '', 'surprise'];

  for (const seed of [5, 55, 555]) {
    it(`never throws, whatever arrives, seed ${seed}`, () => {
      const next = rng(seed);
      for (let round = 0; round < 2_000; round += 1) {
        const data = int(next, 0, 3) === 0
          ? '{'.repeat(int(next, 0, 20))
          : JSON.stringify(
              int(next, 0, 2) === 0
                ? { sequence: int(next, -5, 5), keyFrame: next() < 0.5, timestampUs: -1, data: 'A!' }
                : { state: int(next, 0, 9), reason: null, width: -1, codec: 42 },
            );
        const event = interpretScreenEvent({
          event: NAMES[int(next, 0, NAMES.length - 1)] as string,
          data,
        });
        // Anything that does come back has to be a shape the viewer knows.
        if (event !== null) {
          expect(['open', 'status', 'config', 'frame', 'end']).toContain(event.kind);
        }
      }
    });
  }
});

describe('decodeBase64 under random input', () => {
  it('never throws and never returns a silently shortened buffer', () => {
    const next = rng(31337);
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=!? \n';
    for (let round = 0; round < 5_000; round += 1) {
      let value = '';
      const length = int(next, 0, 24);
      for (let index = 0; index < length; index += 1) {
        value += alphabet[int(next, 0, alphabet.length - 1)];
      }
      const decoded = decodeBase64(value);
      if (decoded !== null) {
        // The only guarantee worth having: what came back re-encodes to exactly what went in.
        // Without it, two different strings on the wire could mean the same frame.
        let binary = '';
        for (const byte of decoded) {
          binary += String.fromCharCode(byte);
        }
        expect(btoa(binary)).toBe(value);
      }
    }
  });
});
