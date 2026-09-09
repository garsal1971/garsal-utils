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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun MockGpsScreen() {
    val ctx = LocalContext.current

    val punti = remember { mutableStateListOf<Punto>().apply { addAll(MockGps.leggiPunti(ctx)) } }
    val scelti = remember { mutableStateListOf<String>() }
    var daAggiungere by remember { mutableStateOf("") }
    var secondi by remember { mutableStateOf("5") }
    var minuti by remember { mutableStateOf("5") }
    var avviso by remember { mutableStateOf("") }

    val attivo by MockStato.attivo.collectAsState()
    val indice by MockStato.indice.collectAsState()
    val restanti by MockStato.restanti.collectAsState()
    val messaggio by MockStato.messaggio.collectAsState()

    fun salva() = MockGps.salvaPunti(ctx, punti.toList())

    // Il permesso delle notifiche serve alla notifica del servizio in primo
    // piano. ⚠️ Negato, il mock parte lo stesso — ma senza notifica sparisce il
    // tasto Ferma da fuori dall'app, e va detto invece di lasciarlo scoprire.
    val chiediNotifiche = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val chiediPosizione = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concesso ->
        avviso = if (concesso) "Permesso concesso: ripremi «Leggi posizione GPS»."
                 else "Senza il permesso di posizione non posso leggere dove sei."
    }

    LaunchedEffect(messaggio) { if (messaggio.isNotEmpty()) avviso = messaggio }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        // ── Quel che sta succedendo ───────────────────────────────────────
        Riquadro(if (attivo) "IN CORSO" else "FERMO") {
            if (attivo) {
                val p = punti.getOrNull(indice)
                Text(
                    "Coordinata ${indice + 1} di ${punti.size}" + (p?.let { "\n${it.testo()}" } ?: ""),
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

        // ── L'elenco ──────────────────────────────────────────────────────
        Riquadro("COORDINATE SALVATE (${punti.size})") {
            if (punti.isEmpty()) {
                Text("Nessuna. Scrivile qui sotto, o leggi quella dove sei adesso.",
                     style = MaterialTheme.typography.bodyMedium)
            } else {
                punti.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = scelti.contains(p.chiave()),
                            onCheckedChange = { su ->
                                if (su) scelti.add(p.chiave()) else scelti.remove(p.chiave())
                            },
                        )
                        Text(p.testo(), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                }

                // ⚠️ La riga dei pulsanti SCORRE e non va a capo: coi caratteri
                // di sistema grandi tre pulsanti su 360 px non ci stanno.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = {
                        if (scelti.size == punti.size) scelti.clear()
                        else { scelti.clear(); punti.forEach { scelti.add(it.chiave()) } }
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
                        enabled = scelti.isNotEmpty() && !attivo,
                        onClick = {
                            punti.removeAll { scelti.contains(it.chiave()) }
                            scelti.clear()
                            salva()
                        },
                    ) { Text("🗑 Cancella") }
                }

                // ⚠️ Le spuntate SONO quelle che si useranno, e non c'è un
                // pulsante «Usa» che lo confermi: sarebbero due verità sulla
                // stessa cosa, e il giorno che divergono non si saprebbe quale
                // ha usato il giro. Nessuna spunta = si usano tutte, che è il
                // caso più frequente e non merita una spunta per riga.
                Text(
                    if (scelti.isEmpty()) "Nessuna spuntata: si useranno tutte."
                    else "Si useranno le ${scelti.size} spuntate, in quest'ordine.",
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
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        chiediPosizione.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        return@OutlinedButton
                    }
                    val p = posizioneAttuale(ctx)
                    if (p == null) {
                        avviso = "Nessuna posizione nota. Apri una mappa per farla agganciare, poi riprova."
                    } else {
                        daAggiungere = if (daAggiungere.isBlank()) p.chiave()
                                       else daAggiungere.trimEnd() + "\n" + p.chiave()
                        avviso = "Letta ${p.testo()} — premi «Aggiungi alla tabella» per tenerla."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Leggi posizione GPS") }
        }

        // ── I tempi ───────────────────────────────────────────────────────
        Riquadro("TEMPI") {
            OutlinedTextField(
                value = secondi,
                onValueChange = { secondi = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Secondi su ogni coordinata") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = minuti,
                onValueChange = { minuti = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Durata totale (minuti)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "L'elenco si ripete in giro finché i minuti non finiscono.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // ── Avvia / Ferma ─────────────────────────────────────────────────
        Button(
            enabled = !attivo && punti.isNotEmpty(),
            onClick = {
                val guasto = MockGps.perche(ctx)
                if (guasto != null) { avviso = guasto; return@Button }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                val daUsare = if (scelti.isEmpty()) punti.toList()
                              else punti.filter { scelti.contains(it.chiave()) }
                MockGpsService.avvia(
                    ctx, daUsare,
                    secondi.toIntOrNull() ?: 5,
                    minuti.toIntOrNull() ?: 5,
                )
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
}

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
