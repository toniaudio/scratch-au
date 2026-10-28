package com.toniaudio.djaaay.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.EFFETTI
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.testoFx

/** Scheda FX del mixer: un'unità per deck (FX · DECK A a sinistra, FX · DECK B a destra). */
@Composable
fun PannelloFx(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 0..1) UnitaFx(app, d, c, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun UnitaFx(app: StatoApp, d: Int, c: Controllore, modifier: Modifier = Modifier) {
    val fx = app.deck[d].fx
    val t = fx.tipo
    val descr = EFFETTI[t]
    val forma = RoundedCornerShape(9.dp)
    Column(
        modifier.border(1.dp, Colori.Linea, forma).padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Etichetta("FX · DECK ${app.deck[d].lettera}", Colori.Testo, 8.5.sp)
            Spacer(Modifier.weight(1f))
            Tasto(Modifier.width(30.dp).height(16.dp), acceso = fx.acceso, onTocco = { c.fxOnOff(d) }) {
                Etichetta(if (fx.acceso) "ON" else "OFF", if (fx.acceso) Colori.Sfondo else Colori.Spento, 7.5.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        // scelta dell'effetto: 2 × 2
        for (riga in 0..1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (colonna in 0..1) {
                    val i = riga * 2 + colonna
                    val scelto = i == t
                    Tasto(
                        Modifier.weight(1f).height(17.dp),
                        bordo = if (scelto) Colori.Neutro else null,
                        onTocco = { c.fxTipo(d, i) },
                    ) {
                        Etichetta(EFFETTI[i].nome, if (scelto) Colori.Testo else Colori.Spento, 7.sp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // manopole: DRY/WET grande, poi i parametri dell'effetto
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            ManopolaFx(fx.dryWet[t], "DRY/WET", testoFx(t, 0, fx.dryWet[t]), 24) { c.fxManopola(d, 0, it) }
            descr.parametri.forEachIndexed { k, nome ->
                val valore = if (k == 0) fx.param1[t] else fx.param2[t]
                ManopolaFx(valore, nome, testoFx(t, k + 1, valore), 18) { c.fxManopola(d, k + 1, it) }
            }
        }
        Spacer(Modifier.height(2.dp))
        Etichetta(descr.titoloRiga, Colori.Spento, 6.5.sp)
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            descr.voci.forEachIndexed { k, voce ->
                val scelta = descr.indici[k] == fx.scelta[t]
                Tasto(Modifier.weight(1f).height(17.dp), acceso = scelta, onTocco = { c.fxScelta(d, k) }) {
                    Text(
                        voce, color = if (scelta) Colori.Sfondo else Colori.Spento, fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManopolaFx(valore: Float, nome: String, testo: String, diametro: Int, onCambia: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Manopola(valore, onCambia, diametro = diametro.dp)
        Text(testo, color = Colori.Testo, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Etichetta(nome, Colori.Spento, 6.5.sp)
    }
}
