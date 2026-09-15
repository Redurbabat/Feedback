import './styles.css';

import { ApiError, FeedbackApi } from './api.ts';
import {
  confirmationRefusalFor,
  nextCapabilities,
  refusalFor,
  type PendingGrant,
} from './elevation.ts';
import {
  currentDirectoryId,
  enterDirectory,
  entryMeta,
  formatCountdown,
  progressLabel,
  rootCrumb,
  secondsUntil,
  shareLine,
  sortEntries,
  type Crumb,
} from './files.ts';
import {
  decideQrCode,
  qrRefusalCopy,
  setupSteps,
  type QrRefusalCopy,
} from './onboarding.ts';
import { qrSvgElement } from './qr.ts';
import {
  hasVideoDecoder,
  statusLine,
  stopReasonLine,
  streamSummary,
} from './screen.ts';
import type {
  AuditEventView,
  DeviceView,
  FileEntryView,
  FileShareView,
  FilesSessionView,
  PairingSummary,
  ScreenConfigView,
  ScreenEvent,
  ScreenSessionView,
  ScreenStopReason,
  SessionView,
  SystemInfoView,
} from './types.ts';

const POLL_INTERVAL_MS = 30_000;

/** The capabilities that can govern a shared area (protocol section 8.3.1). */
const SHARE_CAPABILITIES = ['files.read', 'media.photos.read', 'media.videos.read'];

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
  private filesSession: FilesSessionView | null = null;
  private fileShares: FileShareView[] | null = null;
  private activeShare: FileShareView | null = null;
  private filePath: Crumb[] = [];
  private fileEntries: FileEntryView[] = [];
  private fileCursor: string | null = null;
  private download: {
    fileId: string;
    name: string;
    received: number;
    total: number | null;
    controller: AbortController;
  } | null = null;
  private screen: {
    session: ScreenSessionView;
    phase: 'awaiting_consent' | 'granted' | 'streaming' | 'ended';
    config: ScreenConfigView | null;
    frames: number;
    controller: AbortController;
    decoder: VideoDecoder | null;
    endedBecause: ScreenStopReason | null;
    /**
     * Set when this side gave up rather than the device.
     *
     * Without it a decoding failure in the browser was reported as "the device's encoder gave
     * up", which sends whoever reads it looking at the wrong machine.
     */
    endedLocally: string | null;
  } | null = null;
  /**
   * Held across renders rather than rebuilt with the rest of the view.
   *
   * `render()` replaces the whole tree, and a fresh canvas would be an empty one -
   * the picture would blink out on every unrelated state change. Re-appending the
   * same element moves it and keeps its content.
   */
  private screenCanvas: HTMLCanvasElement | null = null;
  private screenSummaryNode: HTMLElement | null = null;
  /**
   * A grant the server refused until the password is confirmed (THREAT_MODEL 4.5).
   *
   * The refused set is kept here and repeated verbatim, so a poll that refreshes the device
   * in the meantime cannot turn the confirmed grant into a different one. The password itself
   * is never held - it goes straight from the field into the request.
   */
  private pendingElevation: (PendingGrant & { readonly title: string }) | null = null;

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
    sidebar.append(this.addDeviceView(), this.pairingView(), this.deviceListView());

    const content = document.createElement('section');
    content.className = 'content';
    if (this.message !== null) {
      content.append(messageBanner(this.message, () => {
        this.message = null;
        this.render();
      }));
    }
    if (this.pendingElevation !== null) {
      content.append(this.elevationView(this.pendingElevation));
    }
    content.append(this.selectedDeviceView());

    main.append(sidebar, content);
    shell.append(main);
    return shell;
  }

  /**
   * The password prompt in front of a grant (THREAT_MODEL 4.5).
   *
   * It names the device and the capability, because a prompt that only says "please confirm your
   * password" teaches the owner to type it without reading - which is what an attacker who took
   * over the browser window would be counting on.
   */
  private elevationView(pending: PendingGrant & { readonly title: string }): HTMLElement {
    const section = card('Freigabe bestätigen', 'Zweiter Schritt');
    section.body.append(
      text(
        'p',
        `„${pending.title}" soll für „${pending.deviceName}" serverseitig freigegeben werden. `
          + 'Bitte einmal das Kontopasswort eingeben. Entziehen bleibt jederzeit ohne Passwort möglich.',
      ),
    );

    const form = document.createElement('form');
    form.className = 'stack';
    const password = labeledInput('Passwort', 'password', 'current-password', 'Passwort');
    password.input.autofocus = true;
    const submit = button('Freigeben', 'primary');
    submit.type = 'submit';
    submit.disabled = this.busy;
    const cancel = button('Abbrechen', 'ghost');
    cancel.disabled = this.busy;
    cancel.addEventListener('click', () => this.cancelElevation());

    const actions = div('button-row');
    actions.append(submit, cancel);
    form.append(password.wrapper, actions);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      void this.confirmElevation(password.input.value);
    });

    section.body.append(form);
    return section.root;
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

  /**
   * How a further device joins without anyone typing an address.
   *
   * The scanner is deliberately the phone's own camera app and not one inside Feedback.
   * The system camera is the surface on which the owner already sees and decides what
   * is being pointed at, and a scanner of our own would cost a camera permission for a
   * job the operating system already does.
   */
  private addDeviceView(): HTMLElement {
    const section = card('Neues Gerät hinzufügen', 'Kopplung ohne Tippen');
    const decision = decideQrCode(window.location);

    /*
     * The scheme and the host are shown apart, because only one of them is worth reading. The
     * scheme is always https - the app refuses anything else - while the host is what tells the
     * owner whether this is their server. Put together in one line they are long enough to wrap
     * mid-name in the sidebar, and `feedback.example.c / om` is the shape in which a wrong name
     * goes unnoticed.
     */
    const address = div('server-address');
    address.append(
      text('span', 'Adresse dieses Servers', 'field-label'),
      text('span', `${window.location.protocol}//`, 'address-scheme mono'),
      text('strong', window.location.host, 'address-value mono wrap'),
    );
    section.body.append(address);

    section.body.append(
      decision.kind === 'show'
        ? this.qrCodeView(decision.url)
        : noticeBlock(qrRefusalCopy(decision.reason)),
    );
    section.body.append(stepList(setupSteps(decision)));
    return section.root;
  }

  private qrCodeView(url: string): HTMLElement {
    const frame = div('qr-frame');
    try {
      const code = qrSvgElement(url, url);
      code.classList.add('qr-code');
      frame.append(code);
    } catch (error) {
      // An empty box where the code belongs would be the silent break constitution 12
      // rules out, so the failure is named and the manual way stated.
      return noticeBlock({
        headline: 'QR-Code konnte nicht erzeugt werden',
        reason: this.errorMessage(error),
        remedy: `Trage die Adresse am Gerät von Hand ein und öffne ${url} im Browser des Geräts.`,
      });
    }
    frame.append(text('span', url, 'muted small mono wrap'));
    return frame;
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
        // A files session belongs to one device. Carrying it to the next one would
        // show the previous device's shares under a different name.
        this.resetFilesState();
        this.resetScreenState();
        // A pending grant belongs to one device. Carrying it over would let a confirmation
        // land on a device the owner is no longer looking at.
        this.pendingElevation = null;
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
      wrapper.append(
        this.capabilityView(device),
        this.systemInfoView(device),
        this.filesView(device),
        this.screenView(device),
      );
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
    section.body.append(
      text(
        'p',
        'Eine Funktion wirkt nur, wenn sie hier UND lokal auf dem Android-Gerät freigegeben ist. Beides einzeln entziehbar.',
        'muted small',
      ),
      this.capabilityRow(
        device,
        'system.info',
        'Systeminformationen',
        'Modell, Android-Version, Akku, Speicher und Netzwerktyp. Keine IMEI, MAC-Adresse oder Telefonnummer.',
      ),
      this.capabilityRow(
        device,
        'files.read',
        'Dateizugriff (nur lesen)',
        'Auflisten und Herunterladen in den Ordnern und Dateien, die auf dem Gerät ausdrücklich freigegeben wurden. Kein Ändern, Löschen oder Ausführen.',
      ),
      this.capabilityRow(
        device,
        'media.photos.read',
        'Fotos (nur lesen)',
        'Nur die Bilder, die auf dem Gerät über Androids Fotoauswahl ausgewählt wurden. Videos bleiben davon unberührt - das ist ein eigener Schalter.',
      ),
      this.capabilityRow(
        device,
        'media.videos.read',
        'Videos (nur lesen)',
        'Nur die Videos, die auf dem Gerät über Androids Fotoauswahl ausgewählt wurden. Fotos bleiben davon unberührt.',
      ),
      this.capabilityRow(
        device,
        'screen.view',
        'Bildschirm sehen (nur zusehen)',
        'Erlaubt, eine Übertragung anzufragen. Jede einzelne Übertragung wird trotzdem am Gerät bestätigt und danach noch einmal von Android selbst. Kein Ton, keine Aufnahme, keine Fernsteuerung - dafür gibt es keinen Protokollbefehl.',
      ),
    );
    return section.root;
  }

  private capabilityRow(
    device: DeviceView,
    capability: string,
    title: string,
    description: string,
  ): HTMLElement {
    const granted = device.serverGrantedCapabilities.includes(capability);
    const row = div('permission-row');
    const copy = div('stack tiny-gap');
    copy.append(text('strong', title), text('span', description, 'muted small'));
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = `toggle${granted ? ' on' : ''}`;
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', String(granted));
    toggle.setAttribute('aria-label', `${title} serverseitig freigeben`);
    toggle.disabled = this.busy;
    toggle.append(document.createElement('span'));
    toggle.addEventListener('click', () => {
      void this.setCapability(device, capability, title, !granted);
    });
    row.append(copy, toggle);
    return row;
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


  private filesView(device: DeviceView): HTMLElement {
    const section = card('Dateien und Medien', 'Nur lesen, nur freigegebene Bereiche');
    const granted = SHARE_CAPABILITIES.some((capability) =>
      device.serverGrantedCapabilities.includes(capability),
    );

    section.body.append(
      text(
        'p',
        'Sichtbar ist ausschließlich, was auf dem Gerät über Androids Dateiauswahl freigegeben wurde. Es gibt kein Ändern, Löschen, Umbenennen oder Ausführen - dafür existiert nicht einmal ein Protokollbefehl.',
        'muted small',
      ),
    );

    if (!granted) {
      section.body.append(
        text(
          'p',
          'Weder Dateizugriff noch Fotos noch Videos sind serverseitig freigegeben.',
          'small',
        ),
      );
      return section.root;
    }
    if (!device.online) {
      section.body.append(text('p', 'Das Gerät ist offline.', 'small'));
      return section.root;
    }

    if (this.filesSession === null) {
      const open = button('Dateien öffnen', 'secondary');
      open.disabled = this.busy;
      open.addEventListener('click', () => void this.openFiles(device));
      section.body.append(open);
      return section.root;
    }

    const remaining = secondsUntil(this.filesSession.expiresAt, Date.now());
    const toolbar = div('row-between align-start');
    toolbar.append(
      text('span', `Sitzung läuft ab in ${formatCountdown(remaining)}`, 'muted small'),
    );
    const close = button('Sitzung beenden', 'ghost small-button');
    close.disabled = this.busy;
    close.addEventListener('click', () => void this.closeFiles(device));
    toolbar.append(close);
    section.body.append(toolbar);

    if (this.activeShare === null) {
      section.body.append(this.shareListView(device));
    } else {
      section.body.append(this.entryListView(device));
    }

    if (this.download !== null) {
      section.body.append(this.downloadView());
    }
    return section.root;
  }

  private shareListView(device: DeviceView): HTMLElement {
    const list = div('stack');
    const shares = this.fileShares ?? [];
    if (shares.length === 0) {
      list.append(
        text(
          'p',
          'Auf dem Gerät ist noch kein Bereich freigegeben. Die Freigabe erfolgt dort unter „Freigegebene Bereiche".',
          'small',
        ),
      );
      return list;
    }
    for (const share of shares) {
      const row = div('permission-row');
      const copy = div('stack tiny-gap');
      copy.append(
        text('strong', share.displayName),
        text('span', shareLine(share), 'muted small'),
      );
      const open = button('Öffnen', 'ghost small-button');
      open.disabled = this.busy;
      open.addEventListener('click', () => void this.openShare(device, share));
      row.append(copy, open);
      list.append(row);
    }
    return list;
  }

  private entryListView(device: DeviceView): HTMLElement {
    const list = div('stack');

    const crumbs = div('row-wrap tiny-gap');
    this.filePath.forEach((crumb, index) => {
      const isLast = index === this.filePath.length - 1;
      if (isLast) {
        crumbs.append(text('span', crumb.label, 'small'));
        return;
      }
      const link = button(crumb.label, 'ghost small-button');
      link.disabled = this.busy;
      link.addEventListener('click', () => void this.navigateTo(device, crumb));
      crumbs.append(link, text('span', '/', 'muted small'));
    });
    const back = button('Zurück zu den Bereichen', 'ghost small-button');
    back.disabled = this.busy;
    back.addEventListener('click', () => {
      this.activeShare = null;
      this.fileEntries = [];
      this.fileCursor = null;
      this.filePath = [];
      this.render();
    });
    list.append(crumbs, back);

    if (this.fileEntries.length === 0) {
      list.append(text('p', 'Dieser Ordner ist leer.', 'small'));
      return list;
    }

    for (const entry of sortEntries(this.fileEntries)) {
      const row = div('permission-row');
      const copy = div('stack tiny-gap');
      copy.append(text('strong', entry.name), text('span', entryMeta(entry), 'muted small'));
      const action =
        entry.kind === 'directory'
          ? button('Öffnen', 'ghost small-button')
          : button('Herunterladen', 'ghost small-button');
      action.disabled = this.busy || this.download !== null;
      if (entry.kind === 'directory') {
        action.addEventListener('click', () => {
          void this.navigateTo(device, { directoryId: entry.id, label: entry.name });
        });
      } else {
        action.addEventListener('click', () => void this.startDownload(device, entry));
      }
      row.append(copy, action);
      list.append(row);
    }

    if (this.fileCursor !== null) {
      const more = button('Mehr laden', 'secondary');
      more.disabled = this.busy;
      more.addEventListener('click', () => void this.loadEntries(device, { append: true }));
      list.append(more);
    }
    return list;
  }

  private downloadView(): HTMLElement {
    const active = this.download;
    const box = div('stack tiny-gap download-progress');
    if (active === null) {
      return box;
    }
    box.append(
      text('strong', active.name),
      text('span', progressLabel(active.received, active.total), 'muted small'),
    );
    const cancel = button('Abbrechen', 'ghost small-button');
    cancel.addEventListener('click', () => {
      this.cancelDownload();
    });
    box.append(cancel);
    return box;
  }

  // ------------------------------------------------------------- screen.view

  private screenView(device: DeviceView): HTMLElement {
    const section = card('Bildschirm', 'Nur zusehen, nur mit Zustimmung am Gerät');
    section.body.append(
      text(
        'p',
        'Eine Übertragung zeigt alles, was die Anzeige des Geräts zeigt - auch andere Apps und Benachrichtigungen. Sie beginnt erst, wenn am Gerät zugestimmt und danach Androids eigener Dialog bestätigt wurde. Es wird nichts aufgezeichnet, kein Ton übertragen und nichts ferngesteuert.',
        'muted small',
      ),
    );

    if (!device.serverGrantedCapabilities.includes('screen.view')) {
      section.body.append(text('p', 'Bildschirm ist serverseitig nicht freigegeben.', 'small'));
      return section.root;
    }
    if (!device.online) {
      section.body.append(text('p', 'Das Gerät ist offline.', 'small'));
      return section.root;
    }
    if (!hasVideoDecoder()) {
      // Saying so beats a blank canvas the owner cannot explain.
      section.body.append(
        text(
          'p',
          'Dieser Browser kann den Bildstrom nicht dekodieren: WebCodecs (VideoDecoder) fehlt. Ein aktueller Chrome, Edge oder Safari kann es.',
          'small',
        ),
      );
      return section.root;
    }

    const active = this.screen;
    if (active === null) {
      const open = button('Bildschirm anfragen', 'secondary');
      open.disabled = this.busy;
      open.addEventListener('click', () => void this.openScreen(device));
      section.body.append(open);
      return section.root;
    }

    const toolbar = div('row-between align-start');
    toolbar.append(
      text(
        'span',
        this.screenPhaseLine(active.phase, active.endedBecause, active.endedLocally),
        'muted small',
      ),
    );
    const controls = div('row-gap');
    if (active.phase === 'streaming') {
      const refresh = button('Bild auffrischen', 'ghost small-button');
      refresh.addEventListener('click', () => void this.requestScreenKeyframe(device));
      controls.append(refresh);
    }
    const stop = button(active.phase === 'ended' ? 'Schließen' : 'Beenden', 'ghost small-button');
    stop.addEventListener('click', () => void this.closeScreen(device));
    controls.append(stop);
    toolbar.append(controls);
    section.body.append(toolbar);

    if (active.phase === 'streaming' || active.config !== null) {
      section.body.append(this.screenCanvasElement(active.config));
    }
    const summary = text('p', streamSummary(active.config, active.frames), 'muted small');
    this.screenSummaryNode = summary;
    section.body.append(summary);
    return section.root;
  }

  private screenPhaseLine(
    phase: 'awaiting_consent' | 'granted' | 'streaming' | 'ended',
    endedBecause: ScreenStopReason | null,
    endedLocally: string | null,
  ): string {
    switch (phase) {
      case 'awaiting_consent':
        return statusLine('pending');
      case 'granted':
        return statusLine('granted');
      case 'streaming':
        return 'Übertragung läuft. Am Gerät ist sie sichtbar und dort jederzeit zu stoppen.';
      case 'ended':
        if (endedLocally !== null) {
          return endedLocally;
        }
        return endedBecause === null ? 'Beendet.' : stopReasonLine(endedBecause);
    }
  }

  private screenCanvasElement(config: ScreenConfigView | null): HTMLCanvasElement {
    let canvas = this.screenCanvas;
    if (canvas === null) {
      canvas = document.createElement('canvas');
      canvas.className = 'screen-canvas';
      this.screenCanvas = canvas;
    }
    if (config !== null && (canvas.width !== config.width || canvas.height !== config.height)) {
      canvas.width = config.width;
      canvas.height = config.height;
    }
    return canvas;
  }

  private async openScreen(device: DeviceView): Promise<void> {
    this.busy = true;
    this.message = null;
    this.render();
    try {
      const session = await this.api.openScreenSession(device.id);
      const controller = new AbortController();
      this.screen = {
        session,
        phase: 'awaiting_consent',
        config: null,
        frames: 0,
        controller,
        decoder: null,
        endedBecause: null,
        endedLocally: null,
      };
      this.busy = false;
      this.render();

      // Deliberately not awaited: the stream runs until it ends, and the UI has to
      // keep working while the owner is being asked on the phone.
      void this.consumeScreenStream(device, session.sessionId, controller);
    } catch (error) {
      this.busy = false;
      this.screen = null;
      this.message = { kind: 'error', text: this.errorMessage(error) };
      this.render();
    }
  }

  private async consumeScreenStream(
    device: DeviceView,
    sessionId: string,
    controller: AbortController,
  ): Promise<void> {
    try {
      await this.api.streamScreen(
        device.id,
        sessionId,
        (event) => this.handleScreenEvent(event),
        controller.signal,
      );
      // The stream ended without an `end` event: the connection dropped rather than
      // the session finishing. Saying "connection lost" is closer to the truth than
      // leaving the last status on screen.
      this.finishScreen('connection_lost');
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return;
      }
      this.message = { kind: 'error', text: this.errorMessage(error) };
      this.finishScreen('connection_lost');
    }
  }

  private handleScreenEvent(event: ScreenEvent): void {
    const active = this.screen;
    if (active === null || active.phase === 'ended') {
      return;
    }
    switch (event.kind) {
      case 'open':
        return;
      case 'status':
        if (event.state === 'granted') {
          active.phase = 'granted';
        }
        if (event.state === 'declined') {
          this.finishScreen('consent_declined');
          return;
        }
        this.render();
        return;
      case 'config':
        active.config = event.config;
        active.phase = 'streaming';
        this.startDecoder(event.config);
        this.render();
        return;
      case 'frame':
        this.decodeFrame(event.data, event.keyFrame, event.timestampUs);
        return;
      case 'end':
        this.finishScreen(event.end.reason);
        return;
    }
  }

  private startDecoder(config: ScreenConfigView): void {
    const active = this.screen;
    if (active === null) {
      return;
    }
    active.decoder?.close();

    const decoderConfig: VideoDecoderConfig = {
      codec: config.codec,
      codedWidth: config.width,
      codedHeight: config.height,
      optimizeForLatency: true,
    };

    /*
     * Asked separately, because configure() does not always complain.
     *
     * A Chromium without proprietary codecs accepts the configuration and then simply never
     * produces a frame: the canvas stays black and neither the error callback nor the summary
     * line says why. Found by actually looking at a screenshot of this view.
     */
    void VideoDecoder.isConfigSupported(decoderConfig).then(
      (support) => {
        if (support.supported === true || this.screen !== active || active.phase === 'ended') {
          return;
        }
        this.finishScreen(
          'client_cancelled',
          `Dieser Browser kann ${config.codec} nicht dekodieren. Ein aktueller Chrome oder Edge kann es.`,
        );
      },
      () => undefined,
    );
    const decoder = new VideoDecoder({
      output: (frame) => this.drawFrame(frame),
      error: () => {
        this.finishScreen('client_cancelled', 'Der Bildstrom konnte hier nicht dekodiert werden.');
      },
    });
    try {
      // No `description`: the device sends Annex-B and repeats SPS/PPS before every
      // keyframe, which is what lets a viewer resynchronise after a lost frame.
      decoder.configure(decoderConfig);
    } catch {
      // The browser has WebCodecs but not this profile. Naming the codec is the only
      // way the owner can tell that from a broken connection.
      decoder.close();
      this.finishScreen(
        'client_cancelled',
        `Dieser Browser kann ${config.codec} nicht dekodieren.`,
      );
      return;
    }
    active.decoder = decoder;
  }

  private decodeFrame(data: Uint8Array, keyFrame: boolean, timestampUs: number): void {
    const active = this.screen;
    const decoder = active?.decoder;
    if (active === null || decoder === undefined || decoder === null) {
      return;
    }
    if (decoder.state !== 'configured') {
      return;
    }
    try {
      decoder.decode(
        new EncodedVideoChunk({
          type: keyFrame ? 'key' : 'delta',
          timestamp: timestampUs,
          data: data as BufferSource,
        }),
      );
    } catch {
      // A chunk the decoder refuses usually means the stream lost its
      // synchronisation. Asking for a keyframe is cheaper than tearing everything down.
      const device = this.selectedDevice();
      if (device !== null) {
        void this.requestScreenKeyframe(device, true);
      }
      return;
    }
  }

  /**
   * Counts frames that were actually decoded and drawn.
   *
   * Counting the ones handed to the decoder instead would have the summary claim "1 Bild
   * empfangen" under a canvas that is still black - which is exactly what it did before.
   */
  private countDecodedFrame(): void {
    const active = this.screen;
    if (active === null) {
      return;
    }
    active.frames += 1;
    // Updated in place rather than through render(): at fifteen frames a second a
    // full rebuild of the page would be the most expensive thing in the browser.
    if (this.screenSummaryNode !== null) {
      this.screenSummaryNode.textContent = streamSummary(active.config, active.frames);
    }
  }

  private drawFrame(frame: VideoFrame): void {
    try {
      const canvas = this.screenCanvas;
      const context = canvas?.getContext('2d') ?? null;
      if (canvas !== null && context !== null) {
        context.drawImage(frame, 0, 0, canvas.width, canvas.height);
        this.countDecodedFrame();
      }
    } finally {
      // Not optional: an unclosed VideoFrame holds a decoder buffer, and a few
      // seconds of that stalls the whole pipeline.
      frame.close();
    }
  }

  private async requestScreenKeyframe(device: DeviceView, quiet = false): Promise<void> {
    const active = this.screen;
    if (active === null || active.phase !== 'streaming') {
      return;
    }
    try {
      await this.api.requestScreenKeyframe(device.id, active.session.sessionId);
    } catch (error) {
      if (!quiet) {
        this.message = { kind: 'error', text: this.errorMessage(error) };
        this.render();
      }
    }
  }

  private async closeScreen(device: DeviceView): Promise<void> {
    const active = this.screen;
    if (active === null) {
      return;
    }
    const sessionId = active.session.sessionId;
    const wasRunning = active.phase !== 'ended';
    this.resetScreenState();
    this.render();
    if (!wasRunning) {
      return;
    }
    try {
      await this.api.closeScreenSession(device.id, sessionId);
    } catch (error) {
      // The local stream is already gone; the server side is best effort from here.
      this.message = { kind: 'error', text: this.errorMessage(error) };
      this.render();
    }
  }

  /**
   * Ends the stream on this side and leaves the reason on screen.
   *
   * `localReason` is for the cases this side caused: the protocol still says
   * `client_cancelled`, because from the device's point of view that is what happened, but the
   * viewer is told the truth rather than being pointed at the phone.
   */
  private finishScreen(reason: ScreenStopReason, localReason: string | null = null): void {
    const active = this.screen;
    if (active === null || active.phase === 'ended') {
      return;
    }
    active.phase = 'ended';
    active.endedBecause = reason;
    active.endedLocally = localReason;
    active.decoder?.close();
    active.decoder = null;
    active.controller.abort();
    this.render();
  }

  private resetScreenState(): void {
    const active = this.screen;
    if (active !== null) {
      active.decoder?.close();
      active.controller.abort();
    }
    this.screen = null;
    this.screenCanvas = null;
    this.screenSummaryNode = null;
  }

  private resetFilesState(): void {
    this.cancelDownload();
    this.filesSession = null;
    this.fileShares = null;
    this.activeShare = null;
    this.filePath = [];
    this.fileEntries = [];
    this.fileCursor = null;
  }

  private async openFiles(device: DeviceView): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      this.filesSession = await this.api.openFilesSession(device.id);
      const response = await this.api.filesShares(device.id, this.filesSession.sessionId);
      this.fileShares = response.shares;
    } catch (error) {
      this.resetFilesState();
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async closeFiles(device: DeviceView): Promise<void> {
    const session = this.filesSession;
    if (this.busy || session === null) return;
    this.busy = true;
    try {
      await this.api.closeFilesSession(device.id, session.sessionId);
      this.message = { kind: 'info', text: 'Dateisitzung beendet.' };
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.resetFilesState();
      this.busy = false;
      this.render();
    }
  }

  private async openShare(device: DeviceView, share: FileShareView): Promise<void> {
    this.activeShare = share;
    this.filePath = [rootCrumb(share.displayName)];
    this.fileEntries = [];
    this.fileCursor = null;
    await this.loadEntries(device, { append: false });
  }

  private async navigateTo(device: DeviceView, crumb: Crumb): Promise<void> {
    this.filePath = enterDirectory(this.filePath, crumb);
    this.fileEntries = [];
    this.fileCursor = null;
    await this.loadEntries(device, { append: false });
  }

  private async loadEntries(device: DeviceView, options: { append: boolean }): Promise<void> {
    const session = this.filesSession;
    const share = this.activeShare;
    if (this.busy || session === null || share === null) return;
    this.busy = true;
    try {
      const response = await this.api.filesEntries(device.id, {
        sessionId: session.sessionId,
        shareId: share.shareId,
        directoryId: currentDirectoryId(this.filePath),
        cursor: options.append ? this.fileCursor : null,
      });
      this.fileEntries = options.append
        ? [...this.fileEntries, ...response.entries]
        : response.entries;
      this.fileCursor = response.nextCursor ?? null;
    } catch (error) {
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private async startDownload(device: DeviceView, entry: FileEntryView): Promise<void> {
    const session = this.filesSession;
    const share = this.activeShare;
    if (session === null || share === null || this.download !== null) return;

    const controller = new AbortController();
    this.download = {
      fileId: entry.id,
      name: entry.name,
      received: 0,
      total: entry.size,
      controller,
    };
    this.render();

    try {
      const blob = await this.api.downloadFile(
        device.id,
        { sessionId: session.sessionId, shareId: share.shareId, fileId: entry.id },
        {
          signal: controller.signal,
          onProgress: (received, total) => {
            if (this.download !== null && this.download.fileId === entry.id) {
              this.download.received = received;
              this.download.total = total ?? this.download.total;
              this.render();
            }
          },
        },
      );
      saveBlob(blob, entry.name);
      this.message = { kind: 'info', text: `„${entry.name}" wurde heruntergeladen.` };
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        this.message = { kind: 'info', text: 'Download abgebrochen.' };
      } else {
        this.handleApiError(error);
      }
    } finally {
      this.download = null;
      this.render();
    }
  }

  private cancelDownload(): void {
    if (this.download !== null) {
      this.download.controller.abort();
      this.download = null;
    }
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
      this.pendingElevation = null;
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

  private async setCapability(
    device: DeviceView,
    capability: string,
    title: string,
    enabled: boolean,
  ): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    const granted = nextCapabilities(device.serverGrantedCapabilities, capability, enabled);
    try {
      await this.api.setCapabilities(device.id, granted);
      this.afterCapabilityChange(capability, enabled);
      await this.refreshDevices(false);
    } catch (error) {
      const refusal = refusalFor(error, {
        deviceId: device.id,
        deviceName: device.name,
        capability,
        grants: enabled,
        grantedCapabilities: granted,
      });
      if (refusal.kind === 'ask_for_password') {
        this.pendingElevation = { ...refusal.pending, title };
        this.message = null;
      } else {
        this.handleApiError(error);
      }
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private afterCapabilityChange(capability: string, enabled: boolean): void {
    this.message = {
      kind: 'info',
      text: enabled
        ? 'Serverseitig freigegeben. Die lokale Android-Freigabe bleibt zusätzlich erforderlich.'
        : 'Serverseitige Freigabe entfernt.',
    };
    if (capability === 'system.info') {
      this.systemInfo = null;
    }
    if (SHARE_CAPABILITIES.includes(capability) && !enabled) {
      // The open session may have been opened for exactly this capability.
      this.resetFilesState();
    }
  }

  /**
   * Confirms the password and repeats the refused grant unchanged (THREAT_MODEL 4.5).
   *
   * A wrong password leaves the prompt standing so the owner can try again; the grant is only
   * repeated after the server accepted the confirmation.
   */
  private async confirmElevation(password: string): Promise<void> {
    const pending = this.pendingElevation;
    if (this.busy || pending === null) return;
    this.busy = true;
    try {
      await this.api.reauthenticate(password);
    } catch (error) {
      this.busy = false;
      if (confirmationRefusalFor(error) === 'wrong_password') {
        this.message = { kind: 'error', text: 'Passwort ist falsch. Bitte erneut eingeben.' };
      } else {
        this.pendingElevation = null;
        this.handleApiError(error);
      }
      this.render();
      return;
    }

    try {
      await this.api.setCapabilities(pending.deviceId, [...pending.grantedCapabilities]);
      this.pendingElevation = null;
      this.afterCapabilityChange(pending.capability, true);
      await this.refreshDevices(false);
    } catch (error) {
      this.pendingElevation = null;
      this.handleApiError(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private cancelElevation(): void {
    this.pendingElevation = null;
    this.message = { kind: 'info', text: 'Freigabe abgebrochen. Es wurde nichts geändert.' };
    this.render();
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
      this.pendingElevation = null;
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
  autocomplete: HTMLInputElement['autocomplete'],
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

/** A short block that says what is not possible here, why, and what to do instead. */
function noticeBlock(copy: QrRefusalCopy): HTMLElement {
  const notice = div('notice');
  notice.append(
    text('strong', copy.headline),
    text('p', copy.reason, 'muted small'),
    text('p', copy.remedy, 'muted small'),
  );
  return notice;
}

function stepList(steps: readonly string[]): HTMLElement {
  const list = document.createElement('ol');
  list.className = 'steps';
  for (const step of steps) {
    list.append(text('li', step, 'small'));
  }
  return list;
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

/**
 * Hands the finished file to the browser.
 *
 * The object URL is released immediately after the click: it holds the whole blob
 * in memory, and a download page that stays open would otherwise keep every file
 * the user ever fetched.
 */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
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
