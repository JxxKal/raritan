# Raritan AKC Headless Bridge — Phase 1 (Connect + Auth Probe)

Schlanker Container, kein X, kein WinForms-Display, kein WebBrowser. Lädt nur `rccore.dll` direkt und versucht eine echte KVM-Session aufzubauen. Wenn das durchgeht (Auth + erstes Frame), haben wir den Pfad — Phase 2 macht daraus dann einen RFB-Stream für noVNC.

## Voraussetzungen

- Debian-Host mit Docker (gleiche Box wie bisher)
- Erreichbarkeit zur DKX2 (TCP 443 sollte reichen, KVM-Port 5000 könnte zusätzlich gebraucht werden)
- Funktionierende Raritan-Login-Credentials (User + Passwort)

## Image laden

```bash
gunzip raritan-akc-bridge-phase1.tar.gz
docker load -i raritan-akc-bridge-phase1.tar
```

## Bridge ausführen

```bash
mkdir -p ./bridge-logs

docker run --rm \
    --name raritan-bridge \
    --network=host \
    -e RARITAN_IP=10.180.42.160 \
    -e RARITAN_USER=<dein-user> \
    -e RARITAN_PASS=<dein-passwort> \
    -v "$PWD/bridge-logs:/logs" \
    raritan-akc-bridge:phase1
```

Container läuft ~30 s (probiert TCP-Connect, dann startet die .NET-Bridge, versucht 30 Sekunden lang Auth + Frame-Empfang), bricht dann ab.

## Was die Logs zeigen werden

`bridge-logs/bridge-output.log` ist das Wichtige. Bei Erfolg erwartet:

```
... Loaded rccore, Version=1.0.0.0
... factory=Com.Raritan.RcCore.Impl.g, sessionIface=Com.Raritan.RcCore.l
... Got session: Com.Raritan.RcCore.Impl.f
... Calling connect: 10.180.42.160:443, ssl=true, user=...
... Trying SSL connection to 10.180.42.160:443
... Trying connection to 10.180.42.160:443
... [t=0s] connected=False authed=False size=...
... [t=1s] connected=True authed=False ...
... [t=2s] connected=True authed=True size=1920x1080 bmp=1920x1080
... === first frame received — SUCCESS ===
```

Wenn's bricht, sehen wir die Stack-Trace direkt — bring die Logs zurück, dann patche ich den nächsten Stub-Eintrag oder die nächste API-Annahme.

## Wahrscheinliche Hürden bei der echten Box

| Symptom | Diagnose | Fix |
|---|---|---|
| `Connection refused` an Port 443 | Raritan-Web-UI nicht auf 443 | `-e RARITAN_PORT=80` oder anderen Port probieren |
| `Authentication failed` | Falsches User/Pass | Credentials prüfen |
| `Unknown auth method` | Raritan erwartet zusätzlichen Auth-Step (z.B. Session-Token via Web-Login zuerst) | Wir bauen einen kleinen HTTP-Pre-Login |
| `wininet.dll/...` in Stack | Weitere P/Invoke die wir noch nicht gestubbt haben | Symbol-Name aus Stack ablesen, in `winstub.c` ergänzen |
| Bridge crashed mit `TargetInvocationException` und keinen weiteren Details | API-Annahme falsch (z.B. Connect-Signatur) | Mit `MONO_LOG_LEVEL=info MONO_LOG_MASK=dll` neu starten |
