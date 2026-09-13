import './styles.css';

import { ApiError, FeedbackApi } from './api.ts';
import type {
  AuditEventView,
  DeviceView,
  PairingSummary,
  SessionView,
  SystemInfoView,
} from './types.ts';

const POLL_INTERVAL_MS = 30_000;

class ControlCenterApp {
  private readonly api = new FeedbackApi();
  private session: SessionView | null = null;
  private devices: DeviceView[] = [];
  private selectedDeviceId: string | null = null;
  private pairingSummary: PairingSummary | null = null;
  private pairingCode = '';
  private systemInfo: SystemInfoView | null = null;
  private auditEvents: AuditEventView[] = [];
  private message: { kind: 'info' | 'error'; text: string } | null = null;
  private busy = false;
  private pollTimer: number | null = null;

  constructor(private readonly root: HTMLElement) {}

  async start(): Promise<void> {
    this.renderLoading('Sitzung wird geprüft …');
    try {
      this.session = await this.api.restoreSession();
      await this.refreshDevices(false);
      this.startPolling();
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) {
        this.message = { kind: 'error', text: this.errorMessage(error) };
      }
      this.session = null;
      this.api.setCsrfToken(null);
    }
    this.render();
  }

  private render(): void {
    this.root.replaceChildren();
    this.root.append(this.session === null ? this.loginView() : this.dashboardView());
  }

  private loginView(): HTMLElement {
    const page = div('auth-page');
    const card = div('auth-card');
    const eyebrow = text('div', 'Feedback', 'eyebrow');
    const title = text('h1', 'Control Center');
    const description = text(
      'p',
      'Melde dich an, um deine eigenen gekoppelten Geräte zu verwalten. Browser-Sitzungen verwenden ausschließlich HttpOnly-Cookies und CSRF-Schutz.',
      'muted',
    );

    const form = document.createElement('form');
    form.className = 'stack';
    form.autocomplete = 'on';

    const email = labeledInput('E-Mail-Adresse', 'email', 'email', 'name@example.com');
    const password = labeledInput('Passwort', 'password', 'current-password', 'Passwort');
    const submit = button('Anmelden', 'primary');
    submit.type = 'submit';

    form.append(email.wrapper, password.wrapper, submit);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      void this.login(email.input.value, password.input.value);
    });

    card.append(eyebrow, title, description);
    if (this.message !== null) {
      card.append(messageBanner(this.message));
    }
    card.append(form);
    page.append(card);
    return page;
  }

  private dashboardView(): HTMLElement {
    const shell = div('shell');
    shell.append(this.topbarView());

    const main = document.createElement('main');
    main.className = 'main-grid';

    const sidebar = document.createElement('aside');
    sidebar.className = 'sidebar';
    sidebar.append(this.pairingView(), this.deviceListView());

    const content = document.createElement('section');
    content.className = 'content';
    if (this.message !== null) {
      content.append(messageBanner(this.message, () => {
        this.message = null;
        this.render();
      }));
    }
    content.append(this.selectedDeviceView());

    main.append(sidebar, content);
    shell.append(main);
    return shell;
  }

  private topbarView(): HTMLElement {
    const header = document.createElement('header');
    header.className = 'topbar';

    const brand = div('brand');
    brand.append(text('div', 'Feedback', 'eyebrow'), text('div', 'Control Center', 'brand-title'));

    const account = div('account');
    const identity = div('account-copy');
    const name = this.session?.user.displayName?.trim() || this.session?.user.email || 'Konto';
    identity.append(text('strong', name), text('span', 'Geschützte Browser-Sitzung', 'muted small'));
    const logout = button('Abmelden', 'ghost');
    logout.addEventListener('click', () => void this.logout());
    account.append(identity, logout);

    header.append(brand, account);
    return header;
  }

  private pairingView(): HTMLElement {
    const section = card('Koppeln', 'Neues Android-Gerät bestätigen');

    const form = document.createElement('form');
    form.className = 'stack compact';
    const field = labeledInput('6-stelliger Code', 'text', 'one-time-code', '123 456');
    field.input.inputMode = 'numeric';
    field.input.maxLength = 7;
    field.input.value = this.pairingCode.length === 6
      ? `${this.pairingCode.slice(0, 3)} ${this.pairingCode.slice(3)}`
      : this.pairingCode;
    field.input.addEventListener('input', () => {
      const digits = field.input.value.replace(/\D/g, '').slice(0, 6);
      this.pairingCode = digits;
      field.input.value = digits.length > 3 ? `${digits.slice(0, 3)} ${digits.slice(3)}` : digits;
    });

    const lookup = button('Gerät prüfen', 'primary');
    lookup.type = 'submit';
    lookup.disabled = this.busy;
    form.append(field.wrapper, lookup);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      void this.lookupPairing();
    });
    section.body.append(form);

    if (this.pairingSummary !== null) {
      section.body.append(this.pairingSummaryView(this.pairingSummary));
    }
    return section.root;
  }

  private pairingSummaryView(summary: PairingSummary): HTMLElement {
    const box = div('pairing-summary');
    const heading = div('row-between');
    heading.append(
      text('strong', summary.device.deviceName),
      pill(summary.knownDevice ? 'Bekanntes Gerät' : 'Neues Gerät', summary.knownDevice ? 'neutral' : 'good'),
    );
    box.append(
      heading,
      detailRow('Device-ID', summary.device.deviceId, true),
      detailRow('Fingerprint', summary.device.fingerprint, true),
      detailRow('Android', summary.device.osVersion),
      detailRow('App', summary.device.appVersion),
      detailRow('Läuft ab', formatDate(summary.expiresAt)),
    );

    const warning = text(
      'p',
      'Prüfe Gerätename und Fingerprint. Die Bestätigung koppelt nur die Geräteidentität; Remote-Funktionen bleiben separat freigabepflichtig.',
      'muted small',
    );
    const actions = div('button-row');
    const reject = button('Ablehnen', 'danger-outline');
    const approve = button('Bestätigen', 'primary');
    reject.disabled = this.busy;
    approve.disabled = this.busy;
    reject.addEventListener('click', () => void this.decidePairing(false));
    approve.addEventListener('click', () => void this.decidePairing(true));
    actions.append(reject, approve);
    box.append(warning, actions);
    return box;
  }

  private deviceListView(): HTMLElement {
    const section = card('Geräte', `${this.devices.length} registriert`);
    const refresh = button('Aktualisieren', 'ghost small-button');
    refresh.disabled = this.busy;
    refresh.addEventListener('click', () => void this.refreshDevices());
    section.header.append(refresh);

    if (this.devices.length === 0) {
      section.body.append(text('p', 'Noch keine Geräte gekoppelt.', 'muted'));
      return section.root;
    }

    const list = div('device-list');
    for (const device of this.devices) {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = `device-item${device.id === this.selectedDeviceId ? ' selected' : ''}`;
      const top = div('row-between');
      top.append(
        text('strong', device.name),
        device.revokedAt !== null
          ? pill('Widerrufen', 'bad')
          : device.online
            ? pill('Online', 'good')
            : pill('Offline', 'neutral'),
      );
      const meta = text(
        'span',
        `${device.platform} · Android ${device.osVersion} · App ${device.appVersion}`,
        'muted small',
      );
      item.append(top, meta);
      item.addEventListener('click', () => {
        this.selectedDeviceId = device.id;
        this.systemInfo = null;
        this.auditEvents = [];
        this.message = null;
        this.render();
      });
      list.append(item);
    }
    section.body.append(list);
    return section.root;
  }

  private selectedDeviceView(): HTMLElement {
    const device = this.selectedDevice();
    if (device === null) {
      const empty = div('empty-state');
      empty.append(
        text('h2', 'Kein Gerät ausgewählt'),
        text('p', 'Kopple ein Gerät oder wähle links ein vorhandenes Gerät aus.', 'muted'),
      );
      return empty;
    }

    const wrapper = div('device-detail');
    wrapper.append(this.deviceHero(device));
    if (device.revokedAt === null) {
      wrapper.append(this.capabilityView(device), this.systemInfoView(device));
    }
    wrapper.append(this.auditView(device), this.dangerView(device));
    return wrapper;
  }

  private deviceHero(device: DeviceView): HTMLElement {
    const hero = div('hero-card');
    const title = div('row-between align-start');
    const copy = div('stack tiny-gap');
    copy.append(text('div', 'Dieses Gerät', 'eyebrow'), text('h1', device.name));
    title.append(
      copy,
      device.revokedAt !== null
        ? pill('Widerrufen', 'bad')
        : device.online
          ? pill('Online', 'good')
          : pill('Offline', 'neutral'),
    );
    hero.append(
      title,
      divWithChildren(
        'detail-grid',
        detailBlock('Device-ID', device.deviceId, true),
        detailBlock('Fingerprint', device.fingerprint, true),
        detailBlock('Android', `${device.osVersion}${device.sdkInt === null ? '' : ` · SDK ${device.sdkInt}`}`),
        detailBlock('App-Version', device.appVersion),
        detailBlock('Zuletzt gesehen', device.lastSeenAt === null ? 'Noch nie' : formatDate(device.lastSeenAt)),
        detailBlock('Gekoppelt', formatDate(device.pairedAt)),
      ),
    );
    return hero;
  }

  private capabilityView(device: DeviceView): HTMLElement {
    const section = card('Berechtigungen', 'Serverseitige Freigaben');
    const granted = device.serverGrantedCapabilities.includes('system.info');
    const row = div('permission-row');
    const copy = div('stack tiny-gap');
    copy.append(
      text('strong', 'Systeminformationen'),
      text(
        'span',
        'Erlaubt Modell, Android-Version, Akku, Speicher und Netzwerktyp. Zusätzlich muss die Freigabe lokal auf dem Android-Gerät aktiv sein.',
        'muted small',
      ),
    );
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = `toggle${granted ? ' on' : ''}`;
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', String(granted));
    toggle.setAttribute('aria-label', 'Systeminformationen serverseitig freigeben');
    toggle.disabled = this.busy;
    toggle.append(document.createElement('span'));
    toggle.addEventListener('click', () => void this.setSystemInfoCapability(device, !granted));
    row.append(copy, toggle);
    section.body.append(row);
    return section.root;
  }

  private systemInfoView(device: DeviceView): HTMLElement {
    const section = card('Systeminformationen', 'Live vom Android-Agent');
    const load = button('Live abrufen', 'secondary');
    load.disabled = this.busy || !device.online;
    load.addEventListener('click', () => void this.loadSystemInfo(device));
    section.header.append(load);

    if (!device.online) {
      section.body.append(text('p', 'Das Gerät ist derzeit offline.', 'muted'));
      return section.root;
    }

    if (this.systemInfo === null) {
      section.body.append(
        text(
          'p',
          'Die Abfrage erzeugt eine kurzlebige Remote-Session und funktioniert nur, wenn system.info auf Server und Gerät freigegeben ist.',
          'muted',
        ),
      );
      return section.root;
    }

    const info = this.systemInfo;
    section.body.append(
      divWithChildren(
        'detail-grid',
        detailBlock('Gerät', `${info.manufacturer} ${info.model}`.trim()),
        detailBlock('Android', `${info.osVersion} · SDK ${info.sdkInt}`),
        detailBlock('Akku', `${info.batteryPercent}%${info.charging ? ' · lädt' : ''}`),
        detailBlock('Speicher frei', `${formatBytes(info.storageFreeBytes)} / ${formatBytes(info.storageTotalBytes)}`),
        detailBlock('Netzwerk', info.networkType),
        detailBlock('Agent-Aktivität', formatDate(info.lastAgentActivity)),
      ),
    );
    return section.root;
  }

  private auditView(device: DeviceView): HTMLElement {
    const section = card('Audit', 'Sicherheitsrelevante Ereignisse');
    const load = button('Verlauf laden', 'ghost small-button');
    load.disabled = this.busy;
    load.addEventListener('click', () => void this.loadAudit(device));
    section.header.append(load);

    if (this.auditEvents.length === 0) {
      section.body.append(text('p', 'Noch kein Verlauf geladen.', 'muted'));
      return section.root;
    }

    const list = div('audit-list');
    for (const event of this.auditEvents) {
      const item = div('audit-item');
      const top = div('row-between');
      top.append(text('strong', event.eventType), pill(event.result, auditTone(event.result)));
      item.append(top, text('span', formatDate(event.createdAt), 'muted small'));
      if (event.sessionId !== null) {
        item.append(text('code', `Session ${event.sessionId}`, 'mono small'));
      }
      list.append(item);
    }
    section.body.append(list);
    return section.root;
  }

  private dangerView(device: DeviceView): HTMLElement {
    const section = card('Gerätezugriff', device.revokedAt === null ? 'Widerruf' : 'Status');
    if (device.revokedAt !== null) {
      section.body.append(
        text('p', `Dieses Gerät wurde am ${formatDate(device.revokedAt)} widerrufen.`, 'muted'),
      );
      return section.root;
    }

    section.body.append(
      text(
        'p',
        'Widerrufen beendet aktive Agent-Verbindungen, macht Geräte-Tokens und Remote-Sessions ungültig und entfernt serverseitige Capability-Freigaben.',
        'muted',
      ),
    );
    const revoke = button('Gerät widerrufen', 'danger');
    revoke.disabled = this.busy;
    revoke.addEventListener('click', () => void this.revokeDevice(device));
    section.body.append(revoke);
    return section.root;
  }

  private async login(email: string, password: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.message = null;
    this.renderLoading('Anmeldung läuft …');
    try {
      this.session = await this.api.login(email.trim(), password);
      await this.refreshDevices(false);
      this.startPolling();
      this.message = { kind: 'info', text: 'Anmeldung erfolgreich.' };
    } catch (error) {
      this.session = null;
      this.api.setCsrfToken(null);
      this.message = { kind: 'error', text: this.errorMessage(error) };
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async logout(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.stopPolling();
    try {
      await this.api.logout();
    } catch {
      // Local UI still discards session state. The HttpOnly cookie cannot be manipulated here.
    } finally {
      this.session = null;
      this.devices = [];
      this.selectedDeviceId = null;
      this.pairingSummary = null;
      this.pairingCode = '';
      this.systemInfo = null;
      this.auditEvents = [];
      this.message = null;
      this.busy = false;
      this.render();
    }
  }

  private async lookupPairing(): Promise<void> {
    if (this.busy) return;
    if (!/^\d{6}$/.test(this.pairingCode)) {
      this.message = { kind: 'error', text: 'Bitte den vollständigen 6-stelligen Code eingeben.' };
      this.render();
      return;
    }
    this.busy = true;
    this.message = null;
    try {
      this.pairingSummary = await this.api.lookupPairing(this.pairingCode);
    } catch (error) {
      this.pairingSummary = null;
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async decidePairing(approve: boolean): Promise<void> {
    const summary = this.pairingSummary;
    if (this.busy || summary === null || !/^\d{6}$/.test(this.pairingCode)) return;
    this.busy = true;
    try {
      if (approve) {
        await this.api.approvePairing(summary.pairingId, this.pairingCode);
        this.message = {
          kind: 'info',
          text: 'Kopplung bestätigt. Das Android-Gerät kann sein Geräte-Token jetzt einmalig abholen.',
        };
      } else {
        await this.api.rejectPairing(summary.pairingId, this.pairingCode);
        this.message = { kind: 'info', text: 'Kopplung abgelehnt.' };
      }
      this.pairingSummary = null;
      this.pairingCode = '';
      window.setTimeout(() => void this.refreshDevices(), 1_500);
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async refreshDevices(renderAfter = true): Promise<void> {
    if (this.session === null) return;
    try {
      const response = await this.api.listDevices();
      this.devices = response.devices;
      if (this.selectedDeviceId === null || !this.devices.some((device) => device.id === this.selectedDeviceId)) {
        this.selectedDeviceId = this.devices.find((device) => device.revokedAt === null)?.id
          ?? this.devices[0]?.id
          ?? null;
        this.systemInfo = null;
        this.auditEvents = [];
      }
    } catch (error) {
      this.handleApiError(error);
    }
    if (renderAfter) this.render();
  }

  private async setSystemInfoCapability(device: DeviceView, enabled: boolean): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await this.api.setCapabilities(device.id, enabled ? ['system.info'] : []);
      this.message = {
        kind: 'info',
        text: enabled
          ? 'Systeminformationen sind serverseitig freigegeben. Die lokale Android-Freigabe bleibt zusätzlich erforderlich.'
          : 'Serverseitige Freigabe für Systeminformationen entfernt.',
      };
      this.systemInfo = null;
      await this.refreshDevices(false);
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async loadSystemInfo(device: DeviceView): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const response = await this.api.systemInfo(device.id);
      this.systemInfo = response.systemInfo;
      this.message = { kind: 'info', text: 'Live-Systeminformationen aktualisiert.' };
    } catch (error) {
      this.systemInfo = null;
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async loadAudit(device: DeviceView): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const response = await this.api.audit(device.id, 50);
      this.auditEvents = response.events;
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async revokeDevice(device: DeviceView): Promise<void> {
    if (this.busy) return;
    const confirmed = window.confirm(
      `Gerät „${device.name}“ wirklich widerrufen? Aktive Sessions und Geräte-Tokens werden ungültig.`,
    );
    if (!confirmed) return;

    this.busy = true;
    try {
      await this.api.revokeDevice(device.id);
      this.message = { kind: 'info', text: 'Gerät wurde widerrufen.' };
      this.systemInfo = null;
      await this.refreshDevices(false);
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private handleApiError(error: unknown): void {
    if (error instanceof ApiError && error.status === 401) {
      this.stopPolling();
      this.session = null;
      this.api.setCsrfToken(null);
      this.devices = [];
      this.selectedDeviceId = null;
      this.message = { kind: 'error', text: 'Die Sitzung ist abgelaufen. Bitte erneut anmelden.' };
      return;
    }
    this.message = { kind: 'error', text: this.errorMessage(error) };
  }

  private errorMessage(error: unknown): string {
    if (error instanceof ApiError) {
      switch (error.code) {
        case 'RATE_LIMITED':
          return 'Zu viele Anfragen. Bitte kurz warten und erneut versuchen.';
        case 'CAPABILITY_DENIED':
          return 'Diese Funktion ist nicht vollständig freigegeben. Prüfe Server- und Android-Freigabe.';
        case 'SESSION_EXPIRED':
          return 'Das Gerät ist nicht verbunden oder die Remote-Session ist abgelaufen.';
        case 'DEVICE_REVOKED':
          return 'Dieses Gerät wurde widerrufen.';
        case 'NOT_FOUND':
          return 'Die angeforderte Ressource wurde nicht gefunden oder ist nicht mehr gültig.';
        default:
          return error.message;
      }
    }
    if (error instanceof Error) return error.message;
    return 'Unbekannter Fehler.';
  }

  private selectedDevice(): DeviceView | null {
    if (this.selectedDeviceId === null) return null;
    return this.devices.find((device) => device.id === this.selectedDeviceId) ?? null;
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && !this.busy) {
        void this.refreshDevices();
      }
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer !== null) {
      window.clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private renderLoading(label: string): void {
    const page = div('loading-page');
    page.append(text('div', 'Feedback', 'eyebrow'), text('h1', label), div('loader'));
    this.root.replaceChildren(page);
  }
}

function card(titleValue: string, subtitle: string): {
  root: HTMLElement;
  header: HTMLElement;
  body: HTMLElement;
} {
  const root = div('card');
  const header = div('card-header');
  const copy = div('stack tiny-gap');
  copy.append(text('h2', titleValue), text('span', subtitle, 'muted small'));
  header.append(copy);
  const body = div('card-body');
  root.append(header, body);
  return { root, header, body };
}

function labeledInput(
  labelValue: string,
  type: string,
  autocomplete: string,
  placeholder: string,
): { wrapper: HTMLLabelElement; input: HTMLInputElement } {
  const wrapper = document.createElement('label');
  wrapper.className = 'field';
  wrapper.append(text('span', labelValue, 'field-label'));
  const input = document.createElement('input');
  input.type = type;
  input.autocomplete = autocomplete;
  input.placeholder = placeholder;
  input.required = true;
  wrapper.append(input);
  return { wrapper, input };
}

function button(label: string, className: string): HTMLButtonElement {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = `button ${className}`;
  element.textContent = label;
  return element;
}

function messageBanner(
  message: { kind: 'info' | 'error'; text: string },
  onDismiss?: () => void,
): HTMLElement {
  const banner = div(`message ${message.kind}`);
  banner.append(text('span', message.text));
  if (onDismiss !== undefined) {
    const dismiss = button('Schließen', 'ghost small-button');
    dismiss.addEventListener('click', onDismiss);
    banner.append(dismiss);
  }
  return banner;
}

function pill(label: string, tone: 'good' | 'bad' | 'neutral'): HTMLElement {
  return text('span', label, `pill ${tone}`);
}

function detailRow(label: string, value: string, mono = false): HTMLElement {
  const row = div('detail-row');
  row.append(text('span', label, 'muted small'), text('span', value, mono ? 'mono small' : 'small'));
  return row;
}

function detailBlock(label: string, value: string, mono = false): HTMLElement {
  const block = div('detail-block');
  block.append(text('span', label, 'muted small'), text('strong', value, mono ? 'mono wrap' : 'wrap'));
  return block;
}

function auditTone(result: string): 'good' | 'bad' | 'neutral' {
  if (result === 'success') return 'good';
  if (result === 'failure' || result === 'denied') return 'bad';
  return 'neutral';
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(date);
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '–';
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

function div(className?: string): HTMLDivElement {
  const element = document.createElement('div');
  if (className !== undefined) element.className = className;
  return element;
}

function divWithChildren(className: string, ...children: Node[]): HTMLDivElement {
  const element = div(className);
  element.append(...children);
  return element;
}

function text<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  value: string,
  className?: string,
): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag);
  element.textContent = value;
  if (className !== undefined) element.className = className;
  return element;
}

const root = document.querySelector<HTMLElement>('#app');
if (root === null) {
  throw new Error('App root missing');
}

void new ControlCenterApp(root).start();
