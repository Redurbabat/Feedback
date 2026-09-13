import { describe, expect, it } from 'vitest';

import {
  capabilityLabel,
  currentDirectoryId,
  downloadPercent,
  enterDirectory,
  entryMeta,
  formatCountdown,
  progressLabel,
  rootCrumb,
  secondsUntil,
  shareKindLabel,
  shareLine,
  sortEntries,
} from './files.ts';
import type { FileEntryView, FileShareView } from './types.ts';

function entry(overrides: Partial<FileEntryView> & { name: string }): FileEntryView {
  return {
    id: overrides.name,
    name: overrides.name,
    mimeType: overrides.mimeType ?? null,
    size: overrides.size ?? null,
    modifiedAt: overrides.modifiedAt ?? null,
    kind: overrides.kind ?? 'file',
  };
}

describe('breadcrumbs', () => {
  it('starts at the share root', () => {
    const path = [rootCrumb('Dokumente')];
    expect(currentDirectoryId(path)).toBeNull();
    expect(path[0]?.label).toBe('Dokumente');
  });

  it('descends into folders', () => {
    let path = [rootCrumb('Dokumente')];
    path = enterDirectory(path, { directoryId: 'a', label: 'Rechnungen' });
    path = enterDirectory(path, { directoryId: 'b', label: '2026' });
    expect(path.map((crumb) => crumb.label)).toEqual(['Dokumente', 'Rechnungen', '2026']);
    expect(currentDirectoryId(path)).toBe('b');
  });

  it('jumps back instead of stacking a folder twice', () => {
    let path = [rootCrumb('Dokumente')];
    path = enterDirectory(path, { directoryId: 'a', label: 'Rechnungen' });
    path = enterDirectory(path, { directoryId: 'b', label: '2026' });
    // Clicking an earlier crumb must shorten the path, not extend it.
    path = enterDirectory(path, { directoryId: 'a', label: 'Rechnungen' });
    expect(path.map((crumb) => crumb.label)).toEqual(['Dokumente', 'Rechnungen']);
    expect(currentDirectoryId(path)).toBe('a');

    path = enterDirectory(path, { directoryId: null, label: 'Dokumente' });
    expect(path).toHaveLength(1);
    expect(currentDirectoryId(path)).toBeNull();
  });
});

describe('entry listing', () => {
  it('puts folders first and sorts the rest naturally', () => {
    const sorted = sortEntries([
      entry({ name: 'bild10.png' }),
      entry({ name: 'Archiv', kind: 'directory' }),
      entry({ name: 'bild2.png' }),
      entry({ name: 'anhang', kind: 'directory' }),
    ]);
    expect(sorted.map((item) => item.name)).toEqual([
      'anhang',
      'Archiv',
      'bild2.png',
      'bild10.png',
    ]);
  });

  it('does not modify the array it was given', () => {
    const original = [entry({ name: 'b' }), entry({ name: 'a' })];
    sortEntries(original);
    expect(original.map((item) => item.name)).toEqual(['b', 'a']);
  });

  it('describes a folder without inventing a size', () => {
    expect(entryMeta(entry({ name: 'Archiv', kind: 'directory', size: null }))).toBe('Ordner');
  });

  it('describes a file with what the device actually reported', () => {
    const meta = entryMeta(
      entry({ name: 'a.pdf', size: 2048, modifiedAt: '2026-09-13T10:00:00.000Z' }),
    );
    expect(meta).toContain('Datei');
    expect(meta).toContain('2 KB');

    // A device that reports no size or date must not produce placeholders.
    expect(entryMeta(entry({ name: 'b.bin' }))).toBe('Datei');
  });

  it('ignores a modification date the device could not format', () => {
    expect(entryMeta(entry({ name: 'c.txt', modifiedAt: 'nonsense' }))).toBe('Datei');
  });
});

describe('download progress', () => {
  it('reports percent when the total is known', () => {
    expect(downloadPercent(0, 100)).toBe(0);
    expect(downloadPercent(50, 100)).toBe(50);
    expect(downloadPercent(100, 100)).toBe(100);
  });

  it('never exceeds one hundred percent', () => {
    // A device that sends more than it announced must not produce 140 %.
    expect(downloadPercent(140, 100)).toBe(100);
  });

  it('reports no percentage when the size is unknown', () => {
    // An invented percentage would be worse than none.
    expect(downloadPercent(500, null)).toBeNull();
    expect(downloadPercent(500, 0)).toBeNull();
    expect(progressLabel(2048, null)).toBe('2 KB geladen');
  });

  it('labels a known total with both numbers', () => {
    const label = progressLabel(512, 1024);
    expect(label).toContain('50 %');
    expect(label).toContain('512 B');
    expect(label).toContain('1 KB');
  });
});

describe('session countdown', () => {
  it('counts down and stops at zero', () => {
    const now = Date.parse('2026-09-13T12:00:00.000Z');
    expect(secondsUntil('2026-09-13T12:05:00.000Z', now)).toBe(300);
    expect(secondsUntil('2026-09-13T11:59:00.000Z', now)).toBe(0);
    expect(secondsUntil('kaputt', now)).toBe(0);
  });

  it('formats as minutes and seconds', () => {
    expect(formatCountdown(300)).toBe('05:00');
    expect(formatCountdown(61)).toBe('01:01');
    expect(formatCountdown(0)).toBe('00:00');
    expect(formatCountdown(-5)).toBe('00:00');
  });
});

describe('shared areas', () => {
  it('names every kind of area distinctly', () => {
    const labels = (['tree', 'file', 'collection'] as const).map(shareKindLabel);
    expect(new Set(labels).size).toBe(3);
    expect(shareKindLabel('collection')).toBe('Auswahl');
  });

  it('names every governing capability distinctly', () => {
    const labels = (
      ['files.read', 'media.photos.read', 'media.videos.read'] as const
    ).map(capabilityLabel);
    expect(new Set(labels).size).toBe(3);
    // Photos and videos must never read the same: they are separate switches.
    expect(capabilityLabel('media.photos.read')).not.toBe(capabilityLabel('media.videos.read'));
  });

  it('says which switch governs an area', () => {
    const share: FileShareView = {
      shareId: 'fotos1',
      displayName: 'Fotoauswahl (3)',
      kind: 'collection',
      capability: 'media.photos.read',
      addedAt: '2026-09-13T10:00:00.000Z',
    };
    const line = shareLine(share);
    expect(line).toContain('Auswahl');
    expect(line).toContain('Fotos');
    // Without the capability the owner could not tell which toggle would close it.
    expect(line).not.toContain('Dateizugriff');
  });
});
