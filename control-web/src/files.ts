import type { FileEntryView } from './types.ts';

/**
 * Presentation rules for the files browser.
 *
 * Kept free of the DOM so they can be tested: what a user is told about a file is
 * part of the consent surface, and "read only" has to be visible rather than
 * implied.
 */

/** One step of the path inside a share. The share root is the first crumb. */
export interface Crumb {
  /** Null for the share root, otherwise the opaque directory id. */
  readonly directoryId: string | null;
  readonly label: string;
}

export function rootCrumb(shareName: string): Crumb {
  return { directoryId: null, label: shareName };
}

/**
 * Appends a folder to the path, or jumps back to it when it is already there.
 *
 * Navigating back must not stack a second copy of a folder the user already
 * visited, otherwise the breadcrumb grows forever on the way in and out.
 */
export function enterDirectory(path: readonly Crumb[], crumb: Crumb): Crumb[] {
  const existing = path.findIndex((entry) => entry.directoryId === crumb.directoryId);
  if (existing >= 0) {
    return path.slice(0, existing + 1);
  }
  return [...path, crumb];
}

export function currentDirectoryId(path: readonly Crumb[]): string | null {
  return path.length === 0 ? null : (path[path.length - 1]?.directoryId ?? null);
}

/**
 * Directories first, then by name. The device answers in provider order, which is
 * not guaranteed to be stable or sorted.
 */
export function sortEntries(entries: readonly FileEntryView[]): FileEntryView[] {
  return [...entries].sort((left, right) => {
    if (left.kind !== right.kind) {
      return left.kind === 'directory' ? -1 : 1;
    }
    return left.name.localeCompare(right.name, 'de-DE', { numeric: true, sensitivity: 'base' });
  });
}

export function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) {
    return '–';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let amount = value;
  let index = 0;
  while (amount >= 1024 && index < units.length - 1) {
    amount /= 1024;
    index += 1;
  }
  const unit = units[index] ?? 'B';
  return `${amount.toLocaleString('de-DE', { maximumFractionDigits: index === 0 ? 0 : 1 })} ${unit}`;
}

/** The single line under a file or folder name. */
export function entryMeta(entry: FileEntryView): string {
  const parts: string[] = [entry.kind === 'directory' ? 'Ordner' : 'Datei'];
  if (entry.kind === 'file' && entry.size !== null) {
    parts.push(formatBytes(entry.size));
  }
  if (entry.modifiedAt !== null) {
    const date = new Date(entry.modifiedAt);
    if (Number.isFinite(date.getTime())) {
      parts.push(new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium' }).format(date));
    }
  }
  return parts.join(' · ');
}

/**
 * Download progress in percent, or null when the total length is unknown.
 *
 * A file whose size the device could not report streams without a total, and an
 * invented percentage would be worse than none.
 */
export function downloadPercent(received: number, total: number | null): number | null {
  if (total === null || total <= 0 || received < 0) {
    return null;
  }
  return Math.min(100, Math.round((received / total) * 100));
}

export function progressLabel(received: number, total: number | null): string {
  const percent = downloadPercent(received, total);
  if (percent === null) {
    return `${formatBytes(received)} geladen`;
  }
  return `${percent} % · ${formatBytes(received)} von ${formatBytes(total ?? 0)}`;
}

/** Seconds left on a files session, floored at zero. */
export function secondsUntil(expiresAt: string, nowMillis: number): number {
  const end = new Date(expiresAt).getTime();
  if (!Number.isFinite(end)) {
    return 0;
  }
  return Math.max(0, Math.floor((end - nowMillis) / 1000));
}

export function formatCountdown(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(safe / 60);
  const rest = safe % 60;
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}
