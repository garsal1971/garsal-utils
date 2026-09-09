package com.garsal.stepinj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Quante righe si disegnano al massimo: vedi il commento in fondo. */
private const val MAX_RIGHE = 40

@Composable
fun ScorciatoiaScreen() {
    val ctx = LocalContext.current

    var tutte by remember { mutableStateOf<List<AppInstallata>>(emptyList()) }
    var caricate by remember { mutableStateOf(false) }
    var filtro by remember { mutableStateOf("") }
    var scelta by remember { mutableStateOf(Scorciatoia.scelta(ctx)) }
    var avviso by remember { mutableStateOf("") }

    // L'elenco si legge una volta sola: leggere l'etichetta di duecento app
    // costa, e rifarlo a ogni carattere digitato renderebbe la ricerca a scatti.
    LaunchedEffect(Unit) {
        tutte = Scorciatoia.elenco(ctx)
        caricate = true
    }

    val nomeScelta: String? = scelta?.let { Scorciatoia.nomeDi(ctx, it) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        Riquadro("APP SCELTA") {
            when {
                scelta == null ->
                    Text("Nessuna. Scegline una dall'elenco qui sotto.",
                         style = MaterialTheme.typography.bodyMedium)
                // ⚠️ Disinstallata: la scelta resta scritta e lo si DICE. Toglierla
                // da sé cancellerebbe in silenzio una scelta che l'utente aveva
                // fatto, e che torna buona se l'app viene reinstallata.
                nomeScelta == null -> Text(
                    "«$scelta» non risulta più installata.",
                    fontWeight = FontWeight.Bold,
                )
                else -> Text(nomeScelta, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }

            Button(
                enabled = scelta != null,
                onClick = {
                    val p = scelta
                    avviso = when {
                        p == null -> ""
                        Scorciatoia.apri(ctx, p) -> ""
                        Scorciatoia.nomeDi(ctx, p) == null ->
                            "Quell'app non è più installata."
                        else ->
                            "Quell'app non ha una schermata da aprire."
                    }
                },
                // ⚠️ Il pulsante è alto: è la sola cosa che si fa in questa
                // schermata dopo averla configurata una volta, e si preme al volo.
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text("🚀 Apri", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

            if (scelta != null) {
                OutlinedButton(
                    onClick = { Scorciatoia.scegli(ctx, null); scelta = null; avviso = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Togli la scelta") }
            }

            if (avviso.isNotEmpty()) {
                Text(avviso, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Riquadro("SCEGLI UN'APP") {
            if (!caricate) {
                Text("Sto leggendo le app installate…",
                     style = MaterialTheme.typography.bodyMedium)
            } else if (tutte.isEmpty()) {
                Text(
                    "Non vedo nessuna app. Se succede su Android 11 o più recente, " +
                        "manca il blocco <queries> nel manifest.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                OutlinedTextField(
                    value = filtro,
                    onValueChange = { filtro = it },
                    label = { Text("Cerca fra ${tutte.size} app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val trovate = tutte.filter { it.nome.contains(filtro, ignoreCase = true) }
                if (trovate.isEmpty()) {
                    Text("Nessuna app col nome «$filtro».",
                         style = MaterialTheme.typography.bodyMedium)
                } else {
                    trovate.take(MAX_RIGHE).forEach { app ->
                        Text(
                            app.nome,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Scorciatoia.scegli(ctx, app.pacchetto)
                                    scelta = app.pacchetto
                                    filtro = ""
                                    avviso = ""
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                    // ⚠️ L'elenco è troncato e NON è una LazyColumn: questa
                    // schermata scorre già tutta (`verticalScroll`), e una lista
                    // pigra dentro un contenitore che scorre riceve un'altezza
                    // infinita — Compose non la disegna, si chiude con
                    // un'eccezione. Duecento righe disegnate tutte insieme
                    // costerebbero, quindi si tagliano e lo si dice: chi cerca
                    // un'app in fondo all'alfabeto la trova col filtro.
                    if (trovate.size > MAX_RIGHE) {
                        Text(
                            "…e altre ${trovate.size - MAX_RIGHE}. Scrivi qualche lettera per restringere.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Text(
            "Questa scheda apre l'app scelta e nient'altro: non fa partire i passi " +
                "né la posizione finta.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
