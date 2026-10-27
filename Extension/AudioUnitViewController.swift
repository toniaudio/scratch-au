import CoreAudioKit
import UIKit

/// Finestra del plugin dentro AUM. Crea anche l'Audio Unit (AUAudioUnitFactory).
public class AudioUnitViewController: AUViewController, AUAudioUnitFactory {
    private var audioUnit: ScratchAudioUnit?
    private var tokenOsservatore: AUParameterObserverToken?
    private var collegata = false

    private let crossfader = UISlider()
    private let curva = UISegmentedControl(items: ["Taglio", "Lineare", "Potenza cost."])
    private let inverti = UISwitch()

    // MARK: AUAudioUnitFactory

    public func createAudioUnit(with componentDescription: AudioComponentDescription) throws -> AUAudioUnit {
        let au = try ScratchAudioUnit(componentDescription: componentDescription, options: [])
        audioUnit = au
        DispatchQueue.main.async { self.collegaInterfaccia() }
        return au
    }

    // MARK: interfaccia

    public override func viewDidLoad() {
        super.viewDidLoad()
        preferredContentSize = CGSize(width: 600, height: 280)
        view.backgroundColor = UIColor(white: 0.1, alpha: 1)

        let titolo = etichetta("ScratchAU", dimensione: 24, peso: .bold)
        let nota = etichetta("Fase 1 – l'audio in ingresso passa attraverso il crossfader. La traccia da scratchare arriverà nel prossimo passo.",
                             dimensione: 13, peso: .regular)
        nota.numberOfLines = 0
        nota.textColor = UIColor(white: 0.7, alpha: 1)

        crossfader.minimumValue = 0
        crossfader.maximumValue = 1
        crossfader.addTarget(self, action: #selector(crossfaderMosso), for: .valueChanged)
        let rigaCrossfader = UIStackView(arrangedSubviews: [etichetta("TRACCIA", dimensione: 12, peso: .semibold),
                                                            crossfader,
                                                            etichetta("INGRESSO", dimensione: 12, peso: .semibold)])
        rigaCrossfader.spacing = 12
        rigaCrossfader.alignment = .center

        curva.addTarget(self, action: #selector(curvaCambiata), for: .valueChanged)
        inverti.addTarget(self, action: #selector(invertiCambiato), for: .valueChanged)
        let rigaOpzioni = UIStackView(arrangedSubviews: [etichetta("Curva", dimensione: 13, peso: .regular), curva,
                                                         UIView(),
                                                         etichetta("Inverti", dimensione: 13, peso: .regular), inverti])
        rigaOpzioni.spacing = 10
        rigaOpzioni.alignment = .center

        let colonna = UIStackView(arrangedSubviews: [titolo, nota, rigaCrossfader, rigaOpzioni])
        colonna.axis = .vertical
        colonna.spacing = 18
        colonna.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(colonna)
        NSLayoutConstraint.activate([
            colonna.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor, constant: 8),
            colonna.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor, constant: -8),
            colonna.topAnchor.constraint(equalTo: view.layoutMarginsGuide.topAnchor, constant: 8),
        ])

        collegaInterfaccia()
    }

    private func etichetta(_ testo: String, dimensione: CGFloat, peso: UIFont.Weight) -> UILabel {
        let l = UILabel()
        l.text = testo
        l.font = .systemFont(ofSize: dimensione, weight: peso)
        l.textColor = .white
        l.setContentHuggingPriority(.required, for: .horizontal)
        return l
    }

    /// Collega i controlli ai parametri quando ci sono sia la finestra sia l'Audio Unit.
    private func collegaInterfaccia() {
        guard !collegata, isViewLoaded, let albero = audioUnit?.parameterTree else { return }
        collegata = true
        // I cambi che arrivano da AUM (MIDI learn, automazioni) aggiornano i controlli
        tokenOsservatore = albero.token(byAddingParameterObserver: { [weak self] _, _ in
            DispatchQueue.main.async { self?.aggiornaControlli() }
        })
        aggiornaControlli()
    }

    private func parametro(_ indirizzo: AUParameterAddress) -> AUParameter? {
        audioUnit?.parameterTree?.parameter(withAddress: indirizzo)
    }

    private func aggiornaControlli() {
        if let p = parametro(ScratchParamCrossfader), !crossfader.isTracking { crossfader.value = p.value }
        if let p = parametro(ScratchParamCurva) { curva.selectedSegmentIndex = Int(p.value.rounded()) }
        if let p = parametro(ScratchParamInverti) { inverti.isOn = p.value >= 0.5 }
    }

    private func imposta(_ indirizzo: AUParameterAddress, _ valore: AUValue) {
        guard let p = parametro(indirizzo) else { return }
        if let token = tokenOsservatore {
            p.setValue(valore, originator: token)
        } else {
            p.value = valore
        }
    }

    @objc private func crossfaderMosso() { imposta(ScratchParamCrossfader, crossfader.value) }
    @objc private func curvaCambiata() { imposta(ScratchParamCurva, AUValue(curva.selectedSegmentIndex)) }
    @objc private func invertiCambiato() { imposta(ScratchParamInverti, inverti.isOn ? 1 : 0) }
}
