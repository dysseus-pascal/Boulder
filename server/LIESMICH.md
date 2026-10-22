# Boulder-Server

Ein kleiner Server für den eigenen Rechner. Boulder spiegelt seine Gesundheitsdaten dorthin, dazu
alles, was in Health Connect steht, aus jeder App. Claude liest sie über MCP (Connector in der
Claude-App, im Web oder in Claude Code). Die Werkzeuge lesen nur:

- `stand`: Zeitzone, letzte Lieferung, Firmware, Ziele, vorhandene Tage, die Satzarten aus Health
  Connect mit ihren Apps
- `tage`: Tageswerte (höchstens 366 Tage je Abfrage), davor die Vorgeschichte aus Kiesel-Helper und
  älteren Sicherungen, als solche gekennzeichnet
- `nacht`: eine Nacht mit Phasen und HRV
- `trainings`: Trainings
- `puls`: Puls in 5-Minuten-Blöcken (höchstens 24 h)
- `minuten`: Rohminuten (höchstens 6 h, nur wenn Boulder sie schickt)
- `spo2`: SpO2-Messungen der Uhr (höchstens 31 Tage)
- `akte`: die Sätze einer Art aus Health Connect, etwa Gewicht oder Blutdruck (höchstens 500)
- `akte_eintrag`: ein Satz ganz, mit Schlafphasen, Abschnitten und Messreihe
- `akte_tage`: eine Art je Tag und App zusammengefasst
- `akte_verlauf`: Messreihen wie Puls oder Leistung in Blöcken (höchstens 7 Tage)

Der Server läuft als Podman-Pod zusammen mit einem Cloudflare Tunnel. Einen Port öffnet er
nicht: Erreichbar ist er nur über den Tunnel.

## Was wohin geht

- **Gespeichert** wird alles auf dem eigenen Server, im Volume `boulder-daten` (SQLite).
  Minutendaten bleiben 90 Tage, alles andere bleibt. Ein Training, das Boulder nicht mehr führt
  (gelöscht oder einem Doppel aus einer anderen App gewichen), verschwindet auch hier.
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
Sync-Token eintragen und **Save** tippen; Boulder sendet gleich. Darunter lässt sich wählen, was
gesendet wird: Tage, Nächte und Trainings sind an, die Minutendaten aus (gut 130 KB je Tag).
Danach sendet Boulder eine Minute nach jedem Abgleich mit der Uhr, sonst alle 30 Minuten, und nur,
was sich geändert hat. Der Stand und ein Fehler stehen auf derselben Seite, Fehler auch im
Protokoll.

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

Ein neues MCP-Token meldet jede Claude-Verbindung ab:

```sh
MCP=$(openssl rand -hex 32)
printf %s "$MCP" | podman secret create --replace boulder-mcp -
systemctl --user restart boulder-pod
printf 'Neues MCP-Token: %s\n' "$MCP"
```

Das neue Token in den Passwortmanager übernehmen. Danach in Claude den Connector **entfernen und
neu hinzufügen** (wie in Schritt 6) und auf der Seite von Boulder das neue MCP-Token eingeben. Ein
blosses **Connect** genügt nicht: Die Anmeldung von Claude ist mit dem alten Token unterschrieben,
Boulder antwortet darauf mit „Unknown client“. Beim festen Header genügt der neue Wert, in Claude
Code `claude mcp remove boulder` und neu hinzufügen.

Für das Sync-Token gilt dasselbe mit `boulder-sync` (etwa `SYNC=$(openssl rand -hex 32)`); das
neue Token kommt danach in Boulder unter **Settings > Boulder server**.

Ein gespeichertes Token zeigt `podman secret inspect --showsecret --format '{{.SecretData}}' boulder-mcp`
(ebenso `boulder-sync`).

Wer zehnmal in 15 Minuten ein falsches MCP-Token eingibt, sperrt die Anmeldeseite für 15 Minuten,
aber nur für seinen eigenen Anschluss (dieselbe IPv4-Adresse oder dasselbe IPv6-Netz /64). Wer
von woanders kommt, meldet sich weiter an, und bestehende Verbindungen laufen weiter.

## Nur Claudes Server zulassen

In `boulder-server.container` die Zeile `#Environment=BOULDER_MCP_NETZE=160.79.104.0/21`
einschalten, dann `systemctl --user daemon-reload` und `systemctl --user restart boulder-pod`.
Danach erreichen `/mcp` nur noch Anthropics Server, also Claude-App und claude.ai. Claude Code
von zu Hause aus wird abgewiesen.

## Aktualisieren

Das neue Bündel liegt beim Release von Boulder (`boulder-server-<fassung>.tar.gz`). Entpacken und
wie in Schritt 1 bauen, danach `systemctl --user restart boulder-pod`. Die Daten bleiben im Volume.

Server und App gehören zusammen: **erst den Server erneuern, dann Boulder**. Ein älterer Server
ersetzte Tage, die eine neuere App ohne Wasser und Koffein schickt, und vergässe diese damit.

## Sichern

Der Server ist ein Spiegel, aber kein vollständiger: Geht er verloren, schickt Boulder neu, was
es noch hat (**Send everything again** auf der Seite Boulder server). Das sind

- alle Nächte,
- die Tageswerte (Schritte, Schlaf, Ruhepuls, HRV, Präparate) aller Tage, die Boulder selbst gemessen hat,
- Wasser und Koffein nur der letzten 180 Tage,
- Trainings nur der letzten 90 Tage,
- Minutendaten nur der letzten 90 Tage.

Was älter ist, gibt es danach nur noch in einer Sicherung des Volumes. Sie geht so:

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
