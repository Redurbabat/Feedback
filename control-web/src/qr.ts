import qrcode from 'qrcode-generator';

/**
 * The QR code for the setup link.
 *
 * The encoder is a dependency on purpose. Reed-Solomon blocks, mask selection and
 * version choice are the kind of code that is wrong in a way no reviewer notices and
 * only a phone that refuses to scan reports - and "it scanned on my phone" is not a
 * test. `qrcode-generator` is one file with no dependencies of its own.
 */

/**
 * Blank modules around the symbol, as ISO/IEC 18004 requires.
 *
 * Without the margin a reader has no reference for the finder patterns against
 * whatever the page happens to put next to the code, and scanning gets noticeably
 * worse rather than failing outright.
 */
export const QR_QUIET_ZONE_MODULES = 4;

/** Edge length of one module in the SVG's own units. */
const MODULE_UNITS = 8;

/**
 * Error correction level.
 *
 * M is the usual default and buys roughly 15 % recoverable modules. The code is read
 * off a screen at arm's length, not from a printed label that collects scratches, so
 * the larger symbol that H would produce buys nothing here.
 */
const ERROR_CORRECTION = 'M';

export interface QrSymbol {
  /** Edge length in modules, quiet zone included. */
  readonly size: number;
  readonly quietZone: number;
  /** Dark modules by `[row][column]`, quiet zone included. */
  readonly modules: readonly (readonly boolean[])[];
}

/**
 * Encodes one string into a module matrix.
 *
 * The result carries the quiet zone so that everything downstream - markup, tests,
 * anything that measures the symbol - works on one square and cannot forget the
 * margin.
 */
export function buildQrSymbol(
  value: string,
  quietZone: number = QR_QUIET_ZONE_MODULES,
): QrSymbol {
  if (value === '') {
    throw new RangeError('QR-Inhalt ist leer');
  }
  if (quietZone < 0 || !Number.isInteger(quietZone)) {
    throw new RangeError('Ruhezone muss eine nicht-negative ganze Zahl sein');
  }
  // The bundled byte encoder truncates every character to its low eight bits, so a
  // non-ASCII character would end up as a *different* byte with nothing failing. A
  // setup link that silently points somewhere else is precisely what must not happen,
  // and a real origin never triggers this: browsers hand out hostnames in ASCII and
  // punycode internationalised names before they are ever read here.
  if (/[^ -~]/u.test(value)) {
    throw new RangeError('QR-Inhalt enthält Zeichen ausserhalb von ASCII');
  }

  const code = qrcode(0, ERROR_CORRECTION);
  code.addData(value);
  code.make();

  const count = code.getModuleCount();
  const size = count + quietZone * 2;
  const modules: boolean[][] = [];
  for (let row = 0; row < size; row += 1) {
    const line: boolean[] = [];
    for (let column = 0; column < size; column += 1) {
      const inside =
        row >= quietZone &&
        row < quietZone + count &&
        column >= quietZone &&
        column < quietZone + count;
      line.push(inside && code.isDark(row - quietZone, column - quietZone));
    }
    modules.push(line);
  }
  return { size, quietZone, modules };
}

/**
 * The symbol as SVG markup.
 *
 * Inline SVG rather than `<img src="data:...">`: inline markup becomes ordinary DOM
 * and is therefore not governed by `img-src`, which a control center that tightens its
 * own CSP would otherwise have to loosen for a picture it generated itself.
 */
export function qrSvgMarkup(symbol: QrSymbol, label: string): string {
  const edge = symbol.size * MODULE_UNITS;
  // The two colours deliberately do not follow the page theme. Readers expect dark
  // modules on a light ground, and an inverted code is one that many phone cameras
  // simply do not see - the panel would stop working the moment the owner's system
  // switched to dark mode.
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${symbol.size} ${symbol.size}"` +
    ` width="${edge}" height="${edge}" shape-rendering="crispEdges" role="img">` +
    `<title>${escapeXml(label)}</title>` +
    `<rect width="${symbol.size}" height="${symbol.size}" fill="#ffffff"/>` +
    `<path fill="#000000" d="${pathData(symbol)}"/>` +
    '</svg>'
  );
}

/**
 * The finished element, ready to append.
 *
 * Parsing the same markup the tests check keeps one renderer instead of two that drift
 * apart, and it avoids `innerHTML` on an element that is about to be shown to the
 * owner.
 */
export function qrSvgElement(value: string, label: string): Element {
  const markup = qrSvgMarkup(buildQrSymbol(value), label);
  const parsed = new DOMParser().parseFromString(markup, 'image/svg+xml');
  // A malformed document is reported as a `parsererror` element rather than by
  // throwing, and appending that would put a parser's error message where the code
  // belongs.
  if (parsed.getElementsByTagName('parsererror').length > 0) {
    throw new Error('QR-Code konnte nicht erzeugt werden');
  }
  return document.importNode(parsed.documentElement, true);
}

/**
 * Dark modules as one path.
 *
 * Neighbouring modules in a row are emitted as a single run rather than one rectangle
 * each: it is the same picture with a fraction of the nodes, and the seams between
 * adjacent rectangles that some renderers show as hairlines disappear with them.
 */
function pathData(symbol: QrSymbol): string {
  const parts: string[] = [];
  for (let row = 0; row < symbol.size; row += 1) {
    const line = symbol.modules[row] ?? [];
    let column = 0;
    while (column < symbol.size) {
      if (line[column] !== true) {
        column += 1;
        continue;
      }
      let run = 1;
      while (column + run < symbol.size && line[column + run] === true) {
        run += 1;
      }
      parts.push(`M${column} ${row}h${run}v1h-${run}z`);
      column += run;
    }
  }
  return parts.join('');
}

function escapeXml(value: string): string {
  return value
    .replace(/&/gu, '&amp;')
    .replace(/</gu, '&lt;')
    .replace(/>/gu, '&gt;')
    .replace(/"/gu, '&quot;');
}
