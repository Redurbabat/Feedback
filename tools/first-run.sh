#!/usr/bin/env bash
#
# Ein Befehl bis zum ersten Test gegen ein echtes Handy.
#
# Erzeugt eine .env, legt die Datenbank an, baut Control Center und Server, und startet beides
# unter EINER Origin - damit das HttpOnly-Cookie ohne Ausnahme funktioniert, es kein CORS gibt
# und ein einziger Tunnel reicht.
#
# Das Skript richtet KEINEN Tunnel ein und startet keinen. Es sagt am Ende, welcher Befehl dran
# ist, weil die Wahl des Dienstes und das Vertrauen in ihn beim Besitzer liegen.
#
# Nichts hiervon lief je gegen echte Hardware. Was beim ersten Durchlauf zu pruefen ist, steht in
# docs/deployment/BETRIEB.md Abschnitt 3.4 und 3.5.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$ROOT/server"
WEB="$ROOT/control-web"
ENV_FILE="$SERVER/.env"

PUBLIC_URL="${1:-}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }
die() { printf '\nFEHLER: %s\n' "$*" >&2; exit 1; }

command -v node >/dev/null || die "node fehlt. Node 22 oder neuer wird gebraucht."
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
[ "$NODE_MAJOR" -ge 22 ] || die "Node $NODE_MAJOR gefunden, 22 oder neuer wird gebraucht."

if [ -z "$PUBLIC_URL" ]; then
  cat <<'USAGE'
Aufruf: tools/first-run.sh https://<dein-oeffentlicher-host>

Die Adresse ist die, unter der das Handy den Server erreicht. Sie muss HTTPS sein - die App
lehnt alles andere ab, damit Pairing-Material und Geraete-Token nie im Klartext laufen.

Noch keinen Host? Ein Tunnel genuegt fuer den ersten Test, zum Beispiel:

    cloudflared tunnel --url http://127.0.0.1:8080

Der Dienst nennt dann eine https-Adresse. Die hier einsetzen und das Skript erneut aufrufen.
USAGE
  exit 1
fi

case "$PUBLIC_URL" in
  https://*) ;;
  *) die "Die Adresse muss mit https:// beginnen. Die App weist alles andere ab." ;;
esac
PUBLIC_URL="${PUBLIC_URL%/}"

# ----------------------------------------------------------------- .env ---

if [ -f "$ENV_FILE" ]; then
  say "1/5  .env existiert bereits - unveraendert gelassen"
  note "Falls sich die Adresse geaendert hat, FEEDBACK_ALLOWED_ORIGINS dort anpassen."
else
  say "1/5  .env anlegen"
  COOKIE_SECRET="$(node -e 'console.log(require("node:crypto").randomBytes(32).toString("base64url"))')"
  cat > "$ENV_FILE" <<ENVEOF
NODE_ENV=production
FEEDBACK_HOST=127.0.0.1
FEEDBACK_PORT=8080
FEEDBACK_DATABASE_FILE=./data/feedback.db

# Pro Installation neu erzeugt. Wer ihn austauscht, macht alle offenen Sitzungen ungueltig.
FEEDBACK_COOKIE_SECRET=$COOKIE_SECRET

# Control Center und API liegen unter derselben Adresse, deshalb steht hier genau eine.
FEEDBACK_ALLOWED_ORIGINS=$PUBLIC_URL

# Das gebaute Control Center, aus derselben Origin ausgeliefert.
FEEDBACK_STATIC_DIR=../control-web/dist

FEEDBACK_LOG_LEVEL=info
ENVEOF
  chmod 600 "$ENV_FILE"
  note "$ENV_FILE geschrieben (nur fuer den Besitzer lesbar)."
fi

# ------------------------------------------------------------ abhaengig ---

say "2/5  Abhaengigkeiten"
(cd "$SERVER" && npm ci --no-audit --no-fund >/dev/null) && note "server: bereit"
(cd "$WEB" && npm ci --no-audit --no-fund >/dev/null) && note "control-web: bereit"

# --------------------------------------------------------------- bauen ---

say "3/5  Bauen"
# Ohne VITE_FEEDBACK_API_BASE_URL spricht das Control Center die eigene Origin an - genau das,
# was hier gewollt ist.
(cd "$WEB" && npm run build >/dev/null) && note "control-web/dist: gebaut"
(cd "$SERVER" && npm run build >/dev/null) && note "server/dist: gebaut"

# ---------------------------------------------------------- datenbank ---

say "4/5  Datenbank und Benutzer"
(cd "$SERVER" && npm run migrate >/dev/null) && note "Migrationen angewendet"

if (cd "$SERVER" && node -e '
  const Database = require("better-sqlite3");
  const db = new Database("./data/feedback.db", { readonly: true });
  const row = db.prepare("select count(*) as n from users").get();
  process.exit(row.n > 0 ? 0 : 1);
' 2>/dev/null); then
  note "Es existiert bereits ein Benutzer - kein neuer angelegt."
else
  note "Noch kein Benutzer. Jetzt einen anlegen:"
  note ""
  note "    cd server && npm run user:create -- --email <deine-adresse>"
  note ""
  note "Danach dieses Skript erneut aufrufen."
  exit 0
fi

# -------------------------------------------------------------- starten ---

say "5/5  Bereit"
cat <<NEXT

  Server starten:

      cd server && npm start

  In einem zweiten Terminal den Tunnel auf Port 8080 richten, falls noch nicht offen:

      cloudflared tunnel --url http://127.0.0.1:8080

  Dann im Browser:      $PUBLIC_URL
  Und auf dem Handy als Server-URL eintragen:      $PUBLIC_URL

  Was beim ersten Durchlauf zu pruefen ist - und was dabei schiefgehen kann -
  steht in docs/deployment/BETRIEB.md Abschnitt 3.4 (Kopplung, Systeminfo,
  Mobilfunk, Widerruf) und 3.5 (Bildschirm: zwei Dialoge, Benachrichtigung,
  Stop, Flugmodus, FLAG_SECURE).

  Wechselt die Tunnel-Adresse, muss FEEDBACK_ALLOWED_ORIGINS in server/.env
  mitwandern - und das Geraet neu gekoppelt werden, weil die Registrierung an
  der Adresse haengt.

NEXT
