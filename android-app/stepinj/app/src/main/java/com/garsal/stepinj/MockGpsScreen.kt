package com.garsal.stepinj

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

@Composable
fun MockGpsScreen() {
    val ctx = LocalContext.current

    val liste = remember { mutableStateListOf<Lista>().apply { addAll(MockGps.leggiListe(ctx)) } }
    var lista by remember { mutableStateOf(MockGps.listaAttiva(ctx)) }
    val punti = remember { mutableStateListOf<Punto>().apply { addAll(MockGps.leggiPunti(ctx, lista)) } }
    // ⚠️ Spunte e tempi si RILEGGONO dalle preferenze e non ripartono da zero: sono
    // quello che userà anche il pulsantone ⚡, che questa schermata non la apre affatto.
    // Le chiavi si filtrano su quelle che esistono ancora — una coordinata cancellata
    // lascerebbe una spunta invisibile che restringe il giro senza dirlo.
    fun spunteVive(l: Lista): List<String> {
        val vive = MockGps.leggiPunti(ctx, l).map { it.chiave() }.toSet()
        return MockGps.leggiScelti(ctx, l).filter { it in vive }
    }
    val scelti = remember { mutableStateListOf<String>().apply { addAll(spunteVive(lista)) } }

    /** Passa a un'altra lista: la rende attiva e ne ricarica coordinate e spunte. */
    fun carica(l: Lista) {
        MockGps.scegliLista(ctx, l.id)
        lista = l
        punti.clear()
        punti.addAll(MockGps.leggiPunti(ctx, l))
        scelti.clear()
        scelti.addAll(spunteVive(l))
    }

    var modifica by remember { mutableStateOf(MockGps.leggiModifica(ctx)) }
    var metri by remember { mutableStateOf(MockGps.leggiMetri(ctx).toString()) }
    var dialogo by remember { mutableStateOf<Dialogo?>(null) }
    var nomeNuovo by remember { mutableStateOf("") }

    var daAggiungere by remember { mutableStateOf("") }
    val tempi = remember { MockGps.leggiTempi(ctx) }
    var secondi by remember { mutableStateOf(tempi.secondi.toString()) }
    var minuti by remember { mutableStateOf(tempi.minuti.toString()) }
    var avviso by remember { mutableStateOf("") }

    val attivo by MockStato.attivo.collectAsState()
    val indice by MockStato.indice.collectAsState()
    val restanti by MockStato.restanti.collectAsState()
    val messaggio by MockStato.messaggio.collectAsState()
    val inGiro by MockStato.punti.collectAsState()

    /**
     * A mock acceso, ogni modifica della tabella vale subito anche per il giro,
     * con la stessa regola dell'avvio (`MockGps.daUsare`) e senza riavviarlo: il
     * servizio rilegge `MockStato.punti` a ogni secondo, e riavviarlo azzererebbe
     * il countdown. Svuotato l'elenco, il servizio si ferma da sé.
     *
     * ⚠️ La chiamano `salva()` e `salvaScelte()`, cioè TUTTE le modifiche: fino
     * alla v1.5.3 la facevano solo frecce, Cancella e Duplica, e un punto aggiunto
     * o una spunta messa a mock acceso restavano fuori dal giro senza dirlo.
     */
    fun aggiornaGiro() {
        if (attivo) MockStato.punti.value = MockGps.daUsare(ctx)
    }

    fun salva() { MockGps.salvaPunti(ctx, lista, punti.toList()); aggiornaGiro() }
    fun salvaScelte() { MockGps.salvaScelti(ctx, lista, scelti.toList()); aggiornaGiro() }

    /**
     * Sposta le spuntate e le SOSTITUISCE, nell'elenco e nel giro in corso.
     *
     * ⚠️ Anche nel giro: il servizio rilegge `MockStato.punti` a ogni secondo, e
     * il telefono si sposta subito senza riavviare il mock — che azzererebbe il
     * countdown. ⚠️ Le spunte seguono il punto spostato: perderle vorrebbe dire
     * che la seconda pressione della stessa freccia non sposta più niente.
     */
    fun sposta(d: Direzione) {
        val m = metri.toIntOrNull()?.takeIf { it > 0 } ?: run {
            avviso = "Scrivi di quanti metri spostare."
            return
        }
        val nuovi = punti.filter { scelti.contains(it.chiave()) }
            .associate { it.chiave() to MockGps.sposta(it, d, m) }
        if (nuovi.isEmpty()) return
        // Un doppione romperebbe le spunte, che vanno per chiave: due righe uguali
        // si spunterebbero e si sposterebbero sempre insieme.
        val fermi = punti.map { it.chiave() }.filter { it !in nuovi.keys }.toSet()
        val arrivi = nuovi.values.map { it.chiave() }
        if (arrivi.any { it in fermi } || arrivi.toSet().size < arrivi.size) {
            avviso = "Lo spostamento finirebbe su una coordinata che c'è già."
            return
        }
        for (i in punti.indices) nuovi[punti[i].chiave()]?.let { punti[i] = it }
        for (i in scelti.indices) nuovi[scelti[i]]?.let { scelti[i] = it.chiave() }
        salva()
        salvaScelte()
        avviso = "Spostate ${nuovi.size} di $m m verso ${d.name.lowercase()}" +
            (if (attivo) ", anche nel giro in corso." else ".")
    }

    /**
     * Copia ogni spuntata subito sotto di sé, spostata di [MockGps.METRI_DUPLICA].
     *
     * ⚠️ Spostata e non identica: le spunte vanno per chiave, e due righe uguali si
     * spunterebbero e si sposterebbero sempre insieme. Si prova a nord, poi a est,
     * sud e ovest, finché la copia non cade su una coordinata che non c'è ancora.
     * Le spunte restano sugli originali: la copia nasce da sistemare a parte.
     */
    fun duplica() {
        val presenti = punti.map { it.chiave() }.toMutableSet()
        val nuovi = mutableListOf<Punto>()
        var saltate = 0
        var i = 0
        while (i < punti.size) {
            val p = punti[i]
            if (scelti.contains(p.chiave())) {
                val copia = listOf(Direzione.NORD, Direzione.EST, Direzione.SUD, Direzione.OVEST)
                    .map { MockGps.sposta(p, it, MockGps.METRI_DUPLICA) }
                    .firstOrNull { it.chiave() !in presenti }
                if (copia == null) saltate++ else {
                    punti.add(i + 1, copia)
                    presenti.add(copia.chiave())
                    nuovi.add(copia)
                    i++
                }
            }
            i++
        }
        if (nuovi.isEmpty() && saltate == 0) return
        salva()
        avviso = "Duplicate ${nuovi.size} (spostate di ${MockGps.METRI_DUPLICA} m)" +
            (if (saltate > 0) "; $saltate avevano già vicini su tutti i lati." else ".")
    }

    /**
     * Rimette TUTTA la lista nell'ordine del percorso aperto più corto
     * ([MockGps.riordina]), partenza e arrivo liberi. Le spunte vanno per chiave,
     * quindi restano sui loro punti; a mock acceso vale subito per il giro.
     */
    fun riordina() {
        val prima = MockGps.lunghezza(punti)
        val nuovi = MockGps.riordina(punti.toList())
        val dopo = MockGps.lunghezza(nuovi)
        if (nuovi == punti.toList()) {
            avviso = "L'ordine era già il più corto che ho trovato (${prima.roundToInt()} m)."
            return
        }
        punti.clear()
        punti.addAll(nuovi)
        salva()
        avviso = "Percorso: ${prima.roundToInt()} m → ${dopo.roundToInt()} m" +
            (if (nuovi.size > MockGps.MAX_ESATTO) " (approssimato)" else "") +
            (if (attivo) ", anche nel giro in corso." else ".")
    }

    fun salvaTempi() = MockGps.salvaTempi(
        ctx,
        secondi.toIntOrNull() ?: MockGps.SECONDI_DI_PARTENZA,
        minuti.toIntOrNull() ?: MockGps.MINUTI_DI_PARTENZA,
    )

    // Il permesso delle notifiche serve alla notifica del servizio in primo
    // piano. ⚠️ Negato, il mock parte lo stesso — ma senza notifica sparisce il
    // tasto Ferma da fuori dall'app, e va detto invece di lasciarlo scoprire.
    val chiediNotifiche = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ⚠️ Il permesso di posizione serve a DUE cose diverse, e la seconda non è
    // ovvia: a «Leggi posizione GPS», e a far partire il servizio. Da Android 14
    // un servizio in primo piano di tipo `location` pretende che l'app abbia il
    // permesso **concesso in quel momento**, anche se il mock non legge niente —
    // e `startForeground` risponde altrimenti con una SecurityException che
    // chiude l'app. Chi ha premuto Avvia si aspetta che parta, quindi il
    // launcher riprende l'azione invece di lasciarlo ripremere.
    var dopoPermesso by remember { mutableStateOf<(() -> Unit)?>(null) }
    val chiediPosizione = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concesso ->
        val azione = dopoPermesso
        dopoPermesso = null
        if (concesso) azione?.invoke()
        else avviso = "Senza il permesso di posizione Android non lascia partire il mock."
    }

    fun conPosizione(azione: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            azione()
        } else {
            dopoPermesso = azione
            chiediPosizione.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(messaggio) { if (messaggio.isNotEmpty()) avviso = messaggio }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ── Quel che sta succedendo ───────────────────────────────────────
        Riquadro(if (attivo) "IN CORSO" else "FERMO") {
            if (attivo) {
                // L'indice è nel GIRO, non nell'elenco: con le spunte i due differiscono.
                val p = inGiro.getOrNull(indice)
                Text(
                    "Coordinata ${indice + 1} di ${inGiro.size}" + (p?.let { "\n${it.testo()}" } ?: ""),
                    fontWeight = FontWeight.Bold,
                )
                Text("Si ferma da sé fra ${restanti / 60} min ${restanti % 60} s",
                     style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Nessuna posizione finta in corso.",
                     style = MaterialTheme.typography.bodyMedium)
            }
            if (avviso.isNotEmpty()) {
                Text(avviso, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ── Le liste ──────────────────────────────────────────────────────
        Riquadro("LISTA") {
            // ⚠️ Riga che SCORRE e non va a capo, come tutte le righe di pulsanti:
            // coi caratteri di sistema grandi tre nomi su 360 px non ci stanno.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                liste.forEach { l ->
                    if (l.id == lista.id) {
                        Button(onClick = {}) { Text(l.nome) }
                    } else {
                        OutlinedButton(
                            // ⚠️ A mock acceso la lista non si cambia: il giro sta usando
                            // quella attiva, e le frecce sposterebbero punti di una lista
                            // mentre il telefono gira su un'altra.
                            enabled = !attivo,
                            onClick = { carica(l); avviso = "" },
                        ) { Text(l.nome) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(enabled = !attivo, onClick = {
                    nomeNuovo = ""; dialogo = Dialogo.NUOVA
                }) { Text("➕ Nuova") }
                OutlinedButton(enabled = !attivo, onClick = {
                    nomeNuovo = lista.nome + " (copia)"; dialogo = Dialogo.CLONA
                }) { Text("⧉ Clona") }
                OutlinedButton(onClick = {
                    nomeNuovo = lista.nome; dialogo = Dialogo.RINOMINA
                }) { Text("✏️ Rinomina") }
                OutlinedButton(enabled = !attivo && liste.size > 1, onClick = {
                    dialogo = Dialogo.ELIMINA
                }) { Text("🗑 Elimina") }
            }
            if (attivo) {
                Text("Mentre il mock è acceso la lista non si cambia.",
                     style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── L'elenco ──────────────────────────────────────────────────────
        Riquadro("COORDINATE (${punti.size})") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = modifica,
                    onCheckedChange = { modifica = it; MockGps.salvaModifica(ctx, it); aggiornaGiro() },
                )
                Text(
                    "  Modalità modifica",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (punti.isEmpty()) {
                Text("Nessuna. Scrivile qui sotto, o leggi quella dove sei adesso.",
                     style = MaterialTheme.typography.bodyMedium)
            } else {
                punti.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // ⚠️ Le spunte si vedono solo in modifica, ma spenta RESTANO
                        // scritte: riaccendendola si ritrova la scelta di prima.
                        if (modifica) {
                            Checkbox(
                                checked = scelti.contains(p.chiave()),
                                onCheckedChange = { su ->
                                    if (su) scelti.add(p.chiave()) else scelti.remove(p.chiave())
                                    salvaScelte()
                                },
                            )
                        }
                        Text(p.testo(), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                }

                if (modifica) {
                    // ⚠️ La riga dei pulsanti SCORRE e non va a capo: coi caratteri
                    // di sistema grandi tre pulsanti su 360 px non ci stanno.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = {
                            if (scelti.size == punti.size) scelti.clear()
                            else { scelti.clear(); punti.forEach { scelti.add(it.chiave()) } }
                            salvaScelte()
                        }) { Text(if (scelti.size == punti.size) "Nessuno" else "Tutti") }

                        OutlinedButton(
                            enabled = scelti.isNotEmpty(),
                            onClick = {
                                val p = punti.firstOrNull { scelti.contains(it.chiave()) }
                                if (p != null && !MockGps.apriInMappa(ctx, p))
                                    avviso = "Non ho trovato un'app di mappe da aprire."
                            },
                        ) { Text("🗺 Mappa") }

                        OutlinedButton(
                            enabled = scelti.isNotEmpty(),
                            onClick = {
                                punti.removeAll { scelti.contains(it.chiave()) }
                                scelti.clear()
                                salva()
                                salvaScelte()
                            },
                        ) { Text("🗑 Cancella") }

                        OutlinedButton(
                            enabled = scelti.isNotEmpty(),
                            onClick = { duplica() },
                        ) { Text("📑 Duplica") }

                        OutlinedButton(
                            enabled = punti.size >= 3,
                            onClick = { riordina() },
                        ) { Text("🔀 Riordina") }
                    }

                    // ── Spostare le spuntate ──────────────────────────────
                    OutlinedTextField(
                        value = metri,
                        onValueChange = {
                            metri = it.filter { c -> c.isDigit() }.take(5)
                            metri.toIntOrNull()?.takeIf { m -> m > 0 }?.let { m -> MockGps.salvaMetri(ctx, m) }
                        },
                        label = { Text("Metri per ogni freccia") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Come una bussola: N in alto, O ed E in mezzo, S in basso.
                    // Due pulsanti per riga ci stanno anche coi caratteri grandi.
                    @Composable
                    fun Freccia(d: Direzione) { OutlinedButton(
                        enabled = scelti.isNotEmpty(),
                        onClick = { sposta(d) },
                    ) { Text(d.etichetta) } }
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Freccia(Direzione.NORD)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Freccia(Direzione.OVEST)
                            Freccia(Direzione.EST)
                        }
                        Freccia(Direzione.SUD)
                    }
                }

                // ⚠️ Le spuntate SONO quelle che si useranno, e non c'è un
                // pulsante «Usa» che lo confermi: sarebbero due verità sulla
                // stessa cosa, e il giorno che divergono non si saprebbe quale
                // ha usato il giro. La regola vive in `MockGps.daUsare`.
                Text(
                    when {
                        !modifica -> "Modifica spenta: si useranno tutte, a giro."
                        scelti.isEmpty() -> "Nessuna spuntata: si useranno tutte."
                        scelti.size == 1 -> "Una sola spuntata: il telefono resta fermo lì."
                        else -> "Si useranno le ${scelti.size} spuntate, in quest'ordine."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ── Aggiungerne ───────────────────────────────────────────────────
        Riquadro("AGGIUNGI COORDINATE") {
            Text("Una per riga: lat,lon", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = daAggiungere,
                onValueChange = { daAggiungere = it },
                placeholder = { Text("44.5072,11.3621") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val nuovi = daAggiungere.lines().mapNotNull { MockGps.puntoDa(it) }
                    val scartate = daAggiungere.lines().count { it.isNotBlank() } - nuovi.size
                    if (nuovi.isEmpty()) {
                        avviso = "Nessuna riga leggibile. Servono due numeri: latitudine e longitudine."
                    } else {
                        // Un doppione non si aggiunge due volte: l'elenco è un
                        // giro, e la stessa coordinata due volte vorrebbe dire
                        // starci il doppio del tempo senza che si veda perché.
                        val giaCi = punti.map { it.chiave() }.toMutableSet()
                        var messi = 0
                        for (p in nuovi) if (giaCi.add(p.chiave())) { punti.add(p); messi++ }
                        salva()
                        daAggiungere = ""
                        avviso = "Aggiunte $messi" +
                            (if (messi < nuovi.size) ", ${nuovi.size - messi} già c'erano" else "") +
                            (if (scartate > 0) ", $scartate righe non leggibili" else "") + "."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Aggiungi alla tabella") }

            OutlinedButton(
                onClick = {
                    if (attivo) {
                        // ⚠️ A mock acceso il telefono risponderebbe con la
                        // posizione FINTA: leggerla e salvarla come «dove sono»
                        // riempirebbe l'elenco di coordinate inventate.
                        avviso = "Ferma prima il mock: adesso il telefono risponderebbe con la posizione finta."
                        return@OutlinedButton
                    }
                    conPosizione {
                        val p = posizioneAttuale(ctx)
                        if (p == null) {
                            avviso = "Nessuna posizione nota. Apri una mappa per farla agganciare, poi riprova."
                        } else {
                            daAggiungere = if (daAggiungere.isBlank()) p.chiave()
                                           else daAggiungere.trimEnd() + "\n" + p.chiave()
                            avviso = "Letta ${p.testo()} — premi «Aggiungi alla tabella» per tenerla."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Leggi posizione GPS") }
        }

        // ── I tempi ───────────────────────────────────────────────────────
        Riquadro("TEMPI") {
            OutlinedTextField(
                value = secondi,
                onValueChange = { secondi = it.filter { c -> c.isDigit() }.take(4); salvaTempi() },
                label = { Text("Secondi su ogni coordinata") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = minuti,
                onValueChange = { minuti = it.filter { c -> c.isDigit() }.take(4); salvaTempi() },
                label = { Text("Durata totale (minuti)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "L'elenco si ripete in giro finché i minuti non finiscono. Questi due tempi " +
                    "restano scritti: li riusa anche il pulsantone della scheda ⚡.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // ── Avvia / Ferma ─────────────────────────────────────────────────
        Button(
            enabled = !attivo && punti.isNotEmpty(),
            onClick = {
                conPosizione {
                    val guasto = MockGps.perche(ctx)
                    if (guasto != null) {
                        avviso = guasto
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                            chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        // ⚠️ Le coordinate le sceglie `MockGps.daUsare`, non questa
                        // riga: la regola «nessuna spunta = tutte» scritta anche qui
                        // sarebbero due giri diversi il giorno che una delle due cambia,
                        // e il pulsantone ⚡ ne userebbe di sue.
                        MockGpsService.avvia(
                            ctx, MockGps.daUsare(ctx),
                            secondi.toIntOrNull() ?: MockGps.SECONDI_DI_PARTENZA,
                            minuti.toIntOrNull() ?: MockGps.MINUTI_DI_PARTENZA,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("▶ Avvia") }

        OutlinedButton(
            enabled = attivo,
            onClick = { MockGpsService.ferma(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("■ Ferma") }

        OutlinedButton(
            onClick = {
                if (!MockGps.apriOpzioniSviluppatore(ctx))
                    avviso = "Non riesco ad aprire le Opzioni sviluppatore da qui."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("⚙️ Opzioni sviluppatore") }

        Text(
            "Perché funzioni, StepInj dev'essere scelta in Opzioni sviluppatore → " +
                "«App per posizioni fittizie». È l'unica cosa da fare a mano, e va fatta una volta.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // ── Le finestre delle liste ───────────────────────────────────────────
    when (val d = dialogo) {
        null -> {}
        Dialogo.ELIMINA -> AlertDialog(
            onDismissRequest = { dialogo = null },
            title = { Text("Eliminare «${lista.nome}»?") },
            text = { Text("Se ne vanno anche le sue ${punti.size} coordinate, e non si recuperano.") },
            confirmButton = {
                TextButton(onClick = {
                    val via = lista.nome
                    if (MockGps.eliminaLista(ctx, lista.id)) {
                        liste.clear(); liste.addAll(MockGps.leggiListe(ctx))
                        carica(MockGps.listaAttiva(ctx))
                        avviso = "Eliminata «$via»."
                    }
                    dialogo = null
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { dialogo = null }) { Text("Annulla") } },
        )
        else -> AlertDialog(
            onDismissRequest = { dialogo = null },
            title = {
                Text(when (d) {
                    Dialogo.NUOVA -> "Nuova lista vuota"
                    Dialogo.CLONA -> "Nuova lista, con le coordinate di «${lista.nome}»"
                    else -> "Rinomina «${lista.nome}»"
                })
            },
            text = {
                OutlinedTextField(
                    value = nomeNuovo,
                    onValueChange = { nomeNuovo = it.replace('\n', ' ').replace('\t', ' ').take(40) },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nomeNuovo.isNotBlank(),
                    onClick = {
                        when (d) {
                            Dialogo.RINOMINA -> {
                                MockGps.rinominaLista(ctx, lista.id, nomeNuovo)
                                lista = lista.copy(nome = nomeNuovo.trim())
                            }
                            else -> carica(MockGps.nuovaLista(ctx, nomeNuovo, if (d == Dialogo.CLONA) lista else null))
                        }
                        liste.clear(); liste.addAll(MockGps.leggiListe(ctx))
                        dialogo = null
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialogo = null }) { Text("Annulla") } },
        )
    }
}

/** Quale finestra delle liste è aperta. */
private enum class Dialogo { NUOVA, CLONA, RINOMINA, ELIMINA }

/**
 * L'ultima posizione nota, GPS o rete.
 *
 * ⚠️ È l'**ultima nota** e non una lettura fresca: chiedere un aggancio vero
 * vorrebbe dire tenere aperto un ascolto e aspettare, con l'utente fermo
 * davanti a un pulsante che non risponde. Quando non c'è niente, la schermata
 * lo dice e suggerisce come farne comparire una, invece di restituire un punto
 * vecchio di giorni facendolo passare per «dove sei».
 */
private fun posizioneAttuale(ctx: Context): Punto? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return runCatching {
        val fonti = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        fonti.asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Punto(it.latitude, it.longitude) }
    }.getOrNull()
}
