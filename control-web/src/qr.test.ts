import { describe, expect, it } from 'vitest';

import { QR_QUIET_ZONE_MODULES, buildQrSymbol, qrSvgMarkup, type QrSymbol } from './qr.ts';

const URL_UNDER_TEST = 'https://feedback.example.com/pair';

function rows(symbol: QrSymbol): readonly (readonly boolean[])[] {
  return symbol.modules;
}

function darkCount(symbol: QrSymbol): number {
  return rows(symbol)
    .flatMap((line) => [...line])
    .filter((module) => module).length;
}

describe('buildQrSymbol', () => {
  it('produces a square matrix', () => {
    const symbol = buildQrSymbol(URL_UNDER_TEST);
    expect(symbol.modules).toHaveLength(symbol.size);
    for (const line of symbol.modules) {
      expect(line).toHaveLength(symbol.size);
    }
  });

  it('sizes the symbol as a real QR version plus the quiet zone', () => {
    const symbol = buildQrSymbol(URL_UNDER_TEST);
    const count = symbol.size - 2 * symbol.quietZone;
    expect(symbol.quietZone).toBe(QR_QUIET_ZONE_MODULES);
    // Every QR version is 4 * version + 17 modules wide.
    expect(count % 4).toBe(1);
    expect(count).toBeGreaterThanOrEqual(21);
  });

  it('keeps the quiet zone free on all four sides', () => {
    const symbol = buildQrSymbol(URL_UNDER_TEST);
    const last = symbol.size - 1;
    for (let index = 0; index < symbol.size; index += 1) {
      for (let offset = 0; offset < symbol.quietZone; offset += 1) {
        expect(symbol.modules[offset]?.[index]).toBe(false);
        expect(symbol.modules[last - offset]?.[index]).toBe(false);
        expect(symbol.modules[index]?.[offset]).toBe(false);
        expect(symbol.modules[index]?.[last - offset]).toBe(false);
      }
    }
  });

  it('puts the finder pattern immediately inside the quiet zone', () => {
    // The first module of the symbol proper is the corner of a finder pattern; if the
    // margin were an off-by-one this is where it would show.
    const symbol = buildQrSymbol(URL_UNDER_TEST);
    const first = symbol.quietZone;
    expect(symbol.modules[first]?.[first]).toBe(true);
    expect(symbol.modules[first]?.[first + 6]).toBe(true);
    expect(symbol.modules[first + 1]?.[first + 1]).toBe(false);
  });

  it('actually encodes something', () => {
    const symbol = buildQrSymbol(URL_UNDER_TEST);
    expect(darkCount(symbol)).toBeGreaterThan(50);
  });

  it('is deterministic for the same input', () => {
    expect(buildQrSymbol(URL_UNDER_TEST)).toEqual(buildQrSymbol(URL_UNDER_TEST));
  });

  it('encodes a different address differently', () => {
    const one = buildQrSymbol('https://feedback.example.com/pair');
    const other = buildQrSymbol('https://feedback.example.org/pair');
    expect(one.modules).not.toEqual(other.modules);
  });

  it('honours a wider quiet zone', () => {
    const wide = buildQrSymbol(URL_UNDER_TEST, 8);
    const normal = buildQrSymbol(URL_UNDER_TEST);
    expect(wide.size).toBe(normal.size + 8);
    expect(wide.modules[7]?.[7]).toBe(false);
  });

  it('refuses input it cannot encode faithfully', () => {
    expect(() => buildQrSymbol('')).toThrow(RangeError);
    // Latin-1 truncation would turn this into a different byte without failing.
    expect(() => buildQrSymbol('https://münchen.example/pair')).toThrow(RangeError);
    expect(() => buildQrSymbol(URL_UNDER_TEST, -1)).toThrow(RangeError);
  });
});

describe('qrSvgMarkup', () => {
  const symbol = buildQrSymbol(URL_UNDER_TEST);
  const markup = qrSvgMarkup(symbol, URL_UNDER_TEST);

  it('is a square inline svg, not an image reference', () => {
    expect(markup.startsWith('<svg ')).toBe(true);
    expect(markup).toContain(`viewBox="0 0 ${symbol.size} ${symbol.size}"`);
    const width = /width="(\d+)"/u.exec(markup)?.[1];
    const height = /height="(\d+)"/u.exec(markup)?.[1];
    expect(width).toBeDefined();
    expect(width).toBe(height);
    expect(markup).not.toContain('<img');
    expect(markup).not.toContain('data:');
  });

  it('paints a light ground under the whole square, quiet zone included', () => {
    expect(markup).toContain(
      `<rect width="${symbol.size}" height="${symbol.size}" fill="#ffffff"/>`,
    );
  });

  it('draws no module inside the quiet zone', () => {
    const data = /<path fill="#000000" d="([^"]+)"/u.exec(markup)?.[1] ?? '';
    const runs = [...data.matchAll(/M(\d+) (\d+)h(\d+)/gu)];
    expect(runs.length).toBeGreaterThan(0);
    const limit = symbol.size - symbol.quietZone;
    for (const run of runs) {
      const column = Number(run[1]);
      const row = Number(run[2]);
      const length = Number(run[3]);
      expect(row).toBeGreaterThanOrEqual(symbol.quietZone);
      expect(row).toBeLessThan(limit);
      expect(column).toBeGreaterThanOrEqual(symbol.quietZone);
      expect(column + length).toBeLessThanOrEqual(limit);
    }
  });

  it('names the target so the code is not a blank square to a screen reader', () => {
    expect(markup).toContain('role="img"');
    expect(markup).toContain(`<title>${URL_UNDER_TEST}</title>`);
  });

  it('escapes the label instead of letting it close the title', () => {
    const hostile = qrSvgMarkup(symbol, 'https://a.example/?a=1&b=2</title><script/>');
    expect(hostile).toContain('&amp;');
    expect(hostile).toContain('&lt;/title&gt;');
    expect(hostile).not.toContain('<script');
  });

  it('is deterministic for the same input', () => {
    expect(qrSvgMarkup(buildQrSymbol(URL_UNDER_TEST), URL_UNDER_TEST)).toBe(markup);
  });
});
