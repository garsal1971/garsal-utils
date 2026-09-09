# CLAUDE.md — garsal-utils

Contesto per gli assistenti IA che lavorano in questo repository.

---

## Cos'è, e cosa NON è

⚠️ **Due nomi, ed è voluto**: il repository e il sito si chiamano **Garsal Utils** —
è il contenitore — mentre l'APK dentro si chiama **StepInj** (`com.garsal.stepinj`),
perché è lei che si cerca nel cassetto delle app, e «Garsal Utils» lì non direbbe cosa
fa. Il giorno che le utility saranno due, la seconda avrà il suo nome allo stesso modo.

**garsal-utils** è il contenitore delle utility di Salvatore: pagine web servite da
Netlify, eventuali Edge Function Supabase, e un'APK Android — che oggi fa una cosa
sola, 👟 correggere i passi della giornata in Health Connect. La lingua dell'interfaccia
è l'italiano, come in garsal-apps.

⚠️ **Non è garsal-apps e non è la suite AppSphere.** Non ha una riga in `cm_apps`,
quindi nessuna bolla in home — né web né nativa — nessun punteggio, e non legge le
tabelle di quel progetto. Da lì eredita **le convenzioni**, non i dati.

Ogni pagina è **un file `.html` che sta in piedi da solo**: HTML, `<style>` e
`<script>` nello stesso file, nessun build step, nessun package manager.

---

## Struttura

```
garsal-utils/
├── index.html                  # la pagina di ingresso, servita su "/"
├── netlify.toml                # pubblicazione statica + intestazioni
├── server.sh                   # server locale (python3 -m http.server)
├── releases/                   # gli APK pubblicati + la loro scheda .json
├── supabase/
│   ├── functions/              # Edge Function (vuota per ora)
│   └── migrations/             # migration SQL (VUOTA di proposito, vedi sotto)
├── privacy.html                # informativa — Health Connect pretende che esista
├── android-app/stepinj/        # l'APK StepInj: progetto Gradle standalone, Compose
└── .github/workflows/
    ├── deploy.yml              # claude/** → main (+ Supabase, se configurato)
    ├── deploy-dev.yml          # dev/** → preview Netlify
    └── build-stepinj.yml       # APK → releases/StepInj-latest.apk
```

---

## ⚠️ Supabase serve SOLO per il login, e le migration non ci sono

`index.html` fa il login Google col progetto Supabase di garsal-apps
(`jajlmmdsjlvzgcxiiypk`), ma **questo repository non ha tabelle proprie e non applica
nessuna migration**: `supabase/migrations/` è vuota e `SUPABASE_PROJECT_REF` in
`deploy.yml` è la stringa vuota.

⚠️ **Non scrivere lì il ref di garsal-apps.** `supabase db push` confronta la cartella
`supabase/migrations` di *questo* repo con lo storico delle migration applicate al
progetto: due repository che spingono sullo stesso progetto si creano placeholder a
vicenda, e la prima cosa che si rompe è il deploy dell'altro. Il giorno che serve un
database si crea un **progetto Supabase dedicato** e si scrive qui il suo ref.

Finché quel campo è vuoto, il passo *Verifica che ci sia un progetto Supabase* ferma il
deploy con una frase invece di lasciar morire la CLI dieci righe più in basso con un
errore che parla d'altro.

⚠️ **Il sito Netlify di questo repo va messo fra i Redirect URLs di Supabase Auth**
(Authentication → URL Configuration), o il login torna indietro con un errore.

---

## ⚠️ Il marchio è un DADO ESAGONALE, non i cinque cerchi di AppSphere

Il marchio a cinque cerchi vive in **cinque file** dentro garsal-apps e va cambiato in
tutti e cinque insieme — ed è già successo che divergessero. Una sesta copia, per
giunta in un altro repository, sarebbe esattamente quella che nessuno si ricorderebbe
di aggiornare.

Vale qui la regola già scritta là: le barre che dicono *Garsal Apps* e rimandano a `/`
portano il logo; le pagine con un'identità propria no. È la stessa ragione per cui
l'APK «Spese in giro» porta una bicicletta.

Il dado sta in due posti e vanno cambiati insieme:

| Dove | File |
|---|---|
| Barra delle pagine | l'SVG in linea dentro `#garsal-top-bar` in `index.html` |
| Icona di lancio dell'APK | `android-app/stepinj/app/src/main/res/drawable/ic_launcher_foreground.xml` |

⚠️ **Il raggio dell'icona non è scelto a occhio**: la tela adattiva è 108 dp ma il
launcher garantisce solo il **cerchio centrale da 36 dp di raggio**. Col raggio a 28 dp
e la giunzione tonda, l'angolo più esterno cade a 34,5 dp dal centro — dentro quel
cerchio. Allargandolo per riempire la maschera quadrata, quella **tonda** taglierebbe
le punte, e non si vedrebbe finché non lo si prova su un launcher che la usa.

---

## ⚠️ Sul telefono i caratteri di sistema sono molto grandi

Non è un caso limite da verificare alla fine: **è la condizione normale in cui queste
app vengono usate.** Vale identico a quanto scritto in garsal-apps:

- **niente altezze fisse attorno al testo**: `heightIn(min = …)` in Compose e
  `min-height` in CSS, mai `height`;
- **una riga sola è un'ipotesi, non un dato**: titoli ed etichette vanno a capo;
- **le icone in `dp` non crescono col testo**: accanto a una scritta ingrandita vanno
  scalate con `fontScale`, con un tetto;
- **le righe di pulsanti non vanno a capo**: scorrono col dito, con le larghezze
  misurate e non costanti in `dp`;
- **`overflow-wrap: anywhere` su `body`**: a quella dimensione una parola sola può
  essere più larga del riquadro, e spezzarla è meglio che tagliarla.

---

## Sviluppo

```bash
bash server.sh          # http://localhost:8080
bash server.sh 3000
```

Da `localhost` (e dai preview `dev--*.netlify.app`) le pagine rilevano `_IS_DEV = true`
e passano al progetto Supabase dev. La scelta si fa **dall'hostname**, così non esiste
nessun interruttore da ricordarsi di spostare prima di pubblicare.

### Branch

| Branch | Cosa succede |
|---|---|
| `claude/<descrizione>` | `deploy.yml` lo merge su `main`, Netlify pubblica |
| `dev/<descrizione>` | preview `dev--<branch>--<sito>.netlify.app`, **mai** su main |
| `main` | produzione |

⚠️ **Il push su main si riprova fino a cinque volte, e non è prudenza generica**: la
build dell'APK parte dallo stesso push e committa il pacchetto su main, quindi il
merge può trovare un main più avanti — `! [rejected] (fetch first)`. Un **conflitto
vero invece non si riprova**: lo risolve una persona, non un ciclo.

### Prefissi dei commit
`feat:` · `fix:` · `ui:` · `refactor:` · `chore:`

---

## Versioning — regola obbligatoria

**Ad ogni modifica**, nello stesso commit:

- **file HTML**: `APP_VERSION` (patch +1), `BUILD_TIME` col timestamp UTC, e la versione
  nel `<title>`, nel `console.log` e nel badge in barra;
- **APK**: `versionName` e `versionCode` in `android-app/stepinj/app/build.gradle`.
  La versione a schermo si legge dal **pacchetto installato**
  (`Rilascio.installata`), non riscritta a mano: scritta due volte, prima o poi una
  delle due resta indietro — e quella che si legge è proprio la sbagliata.

---

## L'APK

Progetto Gradle **standalone** in `android-app/stepinj/` (Kotlin + Compose),
`applicationId` `com.garsal.stepinj`, pubblicato in `releases/StepInj-latest.apk`.

⚠️ **L'`applicationId` non si cambia più dopo la prima installazione**: cambiarlo ne
farebbe *un'app diversa*, che si installerebbe accanto alla vecchia invece di
aggiornarla, lasciando indietro dati e permessi. Per la stessa ragione il nome del file
in `releases/` resta quello: gli APK già installati interrogano **quel** percorso per
sapere se c'è un aggiornamento, e rinominarlo li lascerebbe su un 404 per sempre.

⚠️ **StepInj ha una chiave di firma PROPRIA**, non quella di garsal-apps: quel
keystore era andato perso e un secret di GitHub non si rilegge — si scrive e basta,
nemmeno il proprietario lo rivede. Non è un ripiego: la stessa chiave serve solo per
**aggiornare un'app già installata**, e StepInj è nata dopo, quindi non c'era niente
sopra cui installarsi. Generata il 9 settembre 2026, RSA 2048, valida 10.000 giorni,
alias `stepinj`, impronta SHA-1 `93:80:0E:4F:B2:1C:8F:67:04:99:7B:62:15:40:84:99:C8:66:83:15`.

⚠️ **Quel keystore è ora insostituibile.** Perso lui, StepInj non si aggiorna più: il
telefono rifiuta un pacchetto firmato con un'altra chiave, e l'unica via sarebbe
disinstallare e reinstallare. Va tenuto dove si tengono le cose che non si possono
riavere — cioè nel Forziere, non nella cartella dei download. ⚠️ **Rigenerarne uno per
comodità è la cosa da non fare**: costa una disinstallazione a chiunque l'abbia già
installata.

### La scheda della build

Il workflow scrive accanto all'APK una `releases/StepInj-latest.json` con **sette
chiavi** (`app`, `version`, `versionCode`, `builtAt`, `commit`, `bytes`, `sha256`), che
`Aggiornamento.kt` legge da **⚙️ → 📱 Versione app**. Serve perché il nome dell'APK è
fisso: da fuori una build vale l'altra, e scaricare quella di ieri è indistinguibile da
un aggiornamento riuscito.

⚠️ **La scheda si aggiorna solo insieme all'APK**: `builtAt` cambia a ogni run, e
commetterla da sola annuncerebbe una build nuova per un pacchetto identico. E
`netlify.toml` la tiene fuori dalla cache, o la pagina annuncerebbe una versione e ne
farebbe scaricare un'altra.

⚠️ **`Aggiornamento.kt` è il gemello** di quelli di garsal-apps (AppSphere nativa, APK
WebView, Smart Blocker, SOS, Spese in giro). Cambiando la forma della scheda in un
workflow, va cambiata **in tutti** — ora anche di là.

⚠️ **`Rilascio.SITO` è `https://garsal-utils.netlify.app`**, il sito di *questo*
repository, e ci deve restare: puntando a quello di garsal-apps
`StepInj-latest.apk` lì non esiste, e il download finirebbe su un 404 che dal
telefono si legge come «la connessione non funziona». Lo stesso indirizzo sta in
`health_privacy_policy_url` nel manifest, e vanno cambiati insieme.

⚠️ **Non aggiungere `material-icons-extended`**: migliaia di icone compilate come
codice Kotlin che senza minificazione finiscono tutte nel DEX — in garsal-apps da sola
aveva portato un APK da 10 a 51 MB, e un pacchetto così si installa male. Il workflow
avvisa oltre i 25 MB, così non può succedere in silenzio.

---

## 👟 Passi — l'unica cosa che l'APK fa oggi

Correggere i passi della giornata **aggiungendone**, scritti in **Health Connect**.
Vive in `Passi.kt` (il ponte con Health Connect) e `MainActivity.kt` (le tre schede:
leggi, aggiungi, disfa).

### ⚠️ Health Connect e NON Google Fit, ed è tutto il punto

La vecchia **FitStepsInjector** passava dalle API Android di Google Fit, che vogliono
un login Google, un progetto Cloud, uno scope OAuth e l'impronta SHA-1 della chiave di
firma registrata là. Il **9 settembre 2026** ha smesso di funzionare da un giorno
all'altro con `ApiException: 12500` (`SIGN_IN_FAILED`) **senza che l'APK fosse
cambiata**: il guasto stava tutto dalla parte di Google — consenso, client OAuth o
progetto. Quelle API sono per giunta supportate **solo fino a fine 2026** e le
iscrizioni di nuovi sviluppatori sono chiuse da maggio 2024.

Health Connect non ha niente di tutto questo: è un **permesso di sistema** sul
telefono. Sparisce l'intera famiglia di guasti di cui il 12500 fa parte — e sullo
stesso telefono Health Connect è già in uso, è da lì che «Ti pisasti?» legge le pesate
della Renpho.

⚠️ **Che i passi si vedano dentro l'app Google Fit dipende da lei**, non da noi: Fit
deve avere la sincronizzazione con Health Connect accesa. È l'unica incognita del
passaggio, e non è una cosa che il codice possa garantire.

### Le tre regole che sono la funzionalità

- ⚠️ **Le finestre non si sovrappongono mai.** Ogni aggiunta scrive un `StepsRecord` in
  una finestra che finisce *adesso* e che **non parte mai prima della fine di quella
  scritta l'ultima volta** (preferenza `ultimo_fine_passi`). Due righe sovrapposte non
  sono come due affiancate: chi le legge deve decidere se sommarle o sceglierne una, e
  le due risposte differiscono proprio del numero che si sta cercando di correggere.
- ⚠️ **Il `clientRecordId` è ricavato dalla finestra.** Se la scrittura riesce ma la
  risposta si perde per strada e si ripreme, Health Connect **sostituisce** la riga
  invece di aggiungerne una seconda. Senza, un tocco ripetuto per incertezza
  raddoppierebbe i passi in silenzio.
- ⚠️ **Si può disfare solo quel che si è scritto.** Health Connect non lascia cancellare
  i dati di un'altra app. Quindi da qui i passi si **aggiungono** e si **disfa la
  propria aggiunta** — non si tolgono i passi contati dal telefono o dall'orologio. La
  schermata lo dice **prima** di premere, non dopo aver visto il totale fermo.

### Altre due cose che non sono dettagli

- ⚠️ **Si legge con `aggregate` e non con `readRecords`**: sommando le righe a mano si
  conterebbero due volte i tratti che due sorgenti hanno registrato insieme (il telefono
  e l'orologio), mentre l'aggregazione applica le priorità fra sorgenti che Health
  Connect già conosce.
- ⚠️ **`Metadata.manualEntry()` e non il costruttore `Metadata`**: dalla versione 1.1.0
  della libreria quel costruttore **non esiste più**. La dipendenza è fissata a
  `1.1.0-rc01`, la stessa di garsal-apps: cambiarla senza guardare `Passi.kt` rompe la
  scrittura.
- ⚠️ **Il `<queries>` nel manifest non è burocrazia**: senza, da Android 11 l'app non
  vede il pacchetto di Health Connect e `getSdkStatus` risponde «non disponibile» anche
  dove è installato.
- ⚠️ **`health_privacy_policy_url` punta a `privacy.html` di questo repo**, che deve
  esistere davvero: Health Connect quell'indirizzo lo pretende.

---

## I secret da configurare (Settings → Secrets and variables → Actions)

| Secret | Serve a | Senza |
|---|---|---|
| `KEYSTORE_BASE64` | Firmare l'APK | La build **si ferma e lo dice** |
| `KEYSTORE_PASSWORD` | idem | idem |
| `KEY_ALIAS` | idem | idem |
| `KEY_PASSWORD` | idem | idem |
| `SUPABASE_ACCESS_TOKEN` | Migration ed Edge Function | Servono solo quando ci saranno |
| `SUPABASE_DEV_PROJECT_REF` | Il progetto dev per i branch `dev/**` | `deploy-dev.yml` lo dice e si ferma |

⚠️ **I quattro della firma NON sono quelli di garsal-apps**: StepInj ha un keystore
suo (vedi sopra). E **non si rileggono da GitHub** — un secret si scrive e basta —
quindi l'unica copia dei loro valori è quella che sta fuori di qui: il file
`stepinj.keystore` e la sua password. Perduta quella, si perde la possibilità di
aggiornare l'app.

---

## Regola obbligatoria — Modifiche a tabelle o campi JSON

**Prima** di modificare la struttura di una tabella Supabase o di un campo JSON/JSONB
esistente, avvisare esplicitamente l'utente e attendere conferma. Se il codice ha
bisogno di un campo che non esiste, **proporre la migration e non inventare campi**.
