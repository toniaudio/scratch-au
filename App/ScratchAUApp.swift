import SwiftUI

// App contenitore: serve solo a installare il plugin e a spiegare come usarlo.
@main
struct ScratchAUApp: App {
    var body: some Scene {
        WindowGroup {
            Istruzioni()
        }
    }
}

struct Istruzioni: View {
    private let versione = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""

    var body: some View {
        NavigationView {
            List {
                Section("Come si usa") {
                    Text("1. Apri AUM.")
                    Text("2. Su un canale audio tocca uno slot e scegli Audio Unit Extensions › Toniaudio: ScratchAU.")
                    Text("3. Tocca il plugin per aprirne la finestra.")
                }
                Section("Crossfader con l'X-Session") {
                    Text("In AUM tocca lo slot del plugin › MIDI Control, scegli il parametro Crossfader, tocca Learn e muovi il crossfader dell'X-Session.")
                    Text("A sinistra si sente la traccia, a destra l'ingresso del canale. Curva \"Taglio\" = taglio netto da scratch.")
                }
                Section("Stato") {
                    Text("Fase 1: plugin \"vuoto\" che fa passare l'audio. Il caricamento della traccia e il piatto touch arrivano nei prossimi aggiornamenti.")
                    Text("Versione \(versione)").foregroundColor(.secondary)
                }
            }
            .navigationTitle("ScratchAU")
        }
        .navigationViewStyle(.stack)
    }
}
