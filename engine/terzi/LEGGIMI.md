# Librerie di terzi

- `signalsmith-stretch.h` — Signalsmith Stretch 1.3.2 (licenza MIT), https://github.com/Signalsmith-Audio/signalsmith-stretch
  Usata per il keylock (cambiare velocità senza cambiare il tono). Inserita il 27/10/2026 con il consenso dell'utente.
- `signalsmith-linear/` — Signalsmith Linear (licenza MIT), https://github.com/Signalsmith-Audio/linear
  (dipendenza della precedente). Mancano di proposito i file `platform/` di IPP, Accelerate, CMSIS, pffft e xsimd:
  sono acceleratori opzionali per altre piattaforme, inclusi solo se attivati, e non servono su Android/Linux.

Le licenze sono nei file LICENSE accanto. Non modificare questi file: per aggiornarli si riscaricano dagli originali.
