# Boulder-Server

Ein kleiner Server für den eigenen Rechner. Boulder spiegelt seine Gesundheitsdaten dorthin, und
Claude liest sie über MCP (Connector in der Claude-App, im Web oder in Claude Code). Die Werkzeuge
lesen nur:

- `stand`: Zeitzone, letzte Lieferung, Firmware, Ziele, vorhandene Tage
- `tage`: Tageswerte (höchstens 366 Tage je Abfrage)
- `nacht`: eine Nacht mit Phasen und HRV
- `trainings`: Trainings
- `puls`: Puls in 5-Minuten-Blöcken (höchstens 24 h)
- `minuten`: Rohminuten (höchstens 6 h, nur wenn Boulder sie schickt)

Der Server läuft als Podman-Pod zusammen mit einem Cloudflare Tunnel. Einen Port öffnet er
nicht: Erreichbar ist er nur über den Tunnel.

## Was wohin geht

- **Gespeichert** wird alles auf dem eigenen Server, im Volume `boulder-daten` (SQLite).
  Minutendaten bleiben 90 Tage, alles andere bleibt ganz.
- **Cloudflare** beendet TLS an seinem Rand. Lieferungen und Abfragen sind dort also lesbar.
- **Claude (Anthropic)** bekommt nur, was ein Werkzeug auf eine Frage liefert.
- An Core Devices oder Google geht nichts.

## Voraussetzungen

- Linux mit systemd und **Podman 5.0 oder neuer** (`podman --version`). Ältere Fassungen kennen
  die Pods in Quadlet nicht.
- Ein Cloudflare-Konto mit einer eigenen Domain.
- Boulder 0.55.0 oder neuer.

Der Server braucht rund 150 bis 250 MB Arbeitsspeicher; das Image ist etwa 370 MB gross. Alle
Befehle laufen als gewöhnlicher Nutzer, ohne root.

## 1. Image bauen

```sh
tar xzf boulder-server-<fassung>.tar.gz
cd boulder-server-<fassung>
podman build -t boulder-server .
```

## 2. Tunnel anlegen

1. Im Cloudflare-Dashboard **Networking > Tunnels** öffnen und **Create Tunnel** wählen. Als
   Namen etwa `boulder` eingeben.
2. Unter **Install and Run** steht ein Befehl mit einem langen Token (`eyJ…`). Nur dieses Token
   kopieren; cloudflared selbst installieren muss man nicht, es läuft im Pod.
3. Im Tunnel unter **Routes** den Eintrag **Add route > Published application** wählen:
   - **Hostname**: etwa `boulder` und die eigene Domain, also `boulder.example.ch`
   - **Service URL**: `http://localhost:8080`

## 3. Tokens anlegen

Es gibt drei Geheimnisse:

- Das **Sync-Token** trägt man in Boulder ein. Nur damit darf jemand Daten schreiben.
- Mit dem **MCP-Token** meldet man Claude an. Es gibt nur Lesezugriff.
- Das **Tunnel-Token** ist das von Cloudflare aus Schritt 2.

```sh
SYNC=$(openssl rand -hex 32)
MCP=$(openssl rand -hex 32)
printf %s "$SYNC" | podman secret create boulder-sync -
printf %s "$MCP" | podman secret create boulder-mcp -
read -rs -p 'Tunnel-Token: ' TUNNEL && printf %s "$TUNNEL" | podman secret create boulder-tunnel -
printf '\nSync-Token: %s\nMCP-Token:  %s\n' "$SYNC" "$MCP"
```

Sync- und MCP-Token in den Passwortmanager übernehmen.

## 4. Starten

```sh
mkdir -p ~/.config/containers/systemd
cp quadlet/* ~/.config/containers/systemd/
```

In `~/.config/containers/systemd/boulder-server.container` die Zeile `BOULDER_ADRESSE` auf den
Hostnamen aus Schritt 2 setzen, etwa `https://boulder.example.ch`. Die Adresse steht ohne `/mcp`
und ohne Schrägstrich am Ende.

```sh
systemctl --user daemon-reload
systemctl --user start boulder-pod
loginctl enable-linger "$USER"                           # läuft auch ohne Anmeldung, startet mit dem Rechner
systemctl --user enable --now podman-auto-update.timer   # hält cloudflared aktuell
```

Zur Prüfung `curl https://boulder.example.ch/gesund` aufrufen; die Antwort ist `ok`. Die
Protokolle zeigt `journalctl --user -u boulder-server -u boulder-tunnel`.

## 5. Boulder verbinden

In Boulder unter **Settings > Boulder server** die Adresse (`https://boulder.example.ch`) und das
Sync-Token eintragen, die Kategorien wählen und **Send now** tippen. Danach sendet Boulder nach
jedem Abgleich mit der Uhr, sonst alle 30 Minuten.

## 6. Claude verbinden

**Claude-App und claude.ai:** Unter **Customize > Connectors** den Punkt **Add custom connector**
wählen:

- **Name**: Boulder
- **URL**: `https://boulder.example.ch/mcp`

Fragt der Dialog nach der Anmeldung: **Sign in now** und, falls gefragt, **Register automatically**.
Nach **Add** und **Connect** öffnet sich eine Seite von Boulder. Dort das MCP-Token eingeben und
**Allow** wählen. Danach in einem Chat unter **+ > Connectors** Boulder einschalten und fragen,
etwa „Wie habe ich diese Woche geschlafen?“.

Zeigt der Dialog den Abschnitt **Request headers** (eine Beta, nicht bei jedem Konto), geht es
auch ohne Anmeldeseite: **No sign-in** wählen und den Header `authorization` mit dem Wert
`Bearer <MCP-Token>` eintragen.

**Claude Code:**

```sh
claude mcp add --transport http boulder https://boulder.example.ch/mcp --header "Authorization: Bearer <MCP-Token>"
```

## Abmelden, Tokens wechseln

Ein neues MCP-Token meldet jede Claude-Verbindung ab. Danach den Connector in Claude neu
verbinden.

```sh
printf %s "$(openssl rand -hex 32)" | podman secret create --replace boulder-mcp -
systemctl --user restart boulder-pod
```

Für das Sync-Token gilt dasselbe mit `boulder-sync`; das neue Token kommt danach in Boulder.

Wer zehnmal in 15 Minuten ein falsches MCP-Token eingibt, sperrt die Anmeldeseite für 15 Minuten.
Bestehende Verbindungen laufen weiter.

## Nur Claudes Server zulassen

In `boulder-server.container` die Zeile `#Environment=BOULDER_MCP_NETZE=160.79.104.0/21`
einschalten, dann `systemctl --user daemon-reload` und `systemctl --user restart boulder-pod`.
Danach erreichen `/mcp` nur noch Anthropics Server, also Claude-App und claude.ai. Claude Code
von zu Hause aus wird abgewiesen.

## Aktualisieren

Das neue Bündel entpacken und wie in Schritt 1 bauen. Danach `systemctl --user restart
boulder-pod`. Die Daten bleiben im Volume.

## Sichern

Der Server ist ein Spiegel: Geht er verloren, schickt Boulder alles neu (**Send everything again**
in den Einstellungen). Eine Sicherung des Volumes geht so:

```sh
systemctl --user stop boulder-pod
podman volume export boulder-daten -o boulder-daten.tar
systemctl --user start boulder-pod
```

## Wenn es nicht geht

- **`/gesund` antwortet nicht:** `systemctl --user status boulder-pod` und die Protokolle prüfen.
  Im Cloudflare-Dashboard muss der Tunnel `Healthy` zeigen.
- **Claude meldet „Couldn't reach the MCP server“:** Die Sicherheitsfunktionen von Cloudflare
  (Bot Fight Mode, Block AI bots, WAF-Challenges) können Claudes Server abweisen. Für diesen
  Hostnamen ausnehmen.
- **Die Anmeldung klappt nicht oder endet wieder bei „Connect“:** `BOULDER_ADRESSE` muss genau
  die Adresse aus Claude sein, ohne `/mcp`. Nach einer Änderung `daemon-reload` und Neustart.
