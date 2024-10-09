# devsecops2024
Repository demo per l'evento DevSecOps day 2024

In questo repository sono presenti le slide e le due pipeline Jenkins che sono state utilizzate per generare Unit Test e fix alle Issue.

Gli strumenti utilizzati sono i seguenti:

Sonar: per le metriche sulle issue di qa/sicurezza del progetto

Gitlab: per il repository del progetto

Jenkins: per l'esecuzione dei job

DeepInfra: per i modelli self-hostable, rimpiazzabile con strumenti es GPT4all se non si vogliono usare servizi esterni

## Generazione Unit Test

la pipeline si effettua i seguenti passaggi:

  - recupera un repository da gitlab
  - recupera le metriche di coverage da sonar
  - per ogni classe, chiede al modello di generare uno unit test
  - valida il codice ottenuto dal modello
  - se la validazione va a buon fine allora apre una MR sul progetto su gitlab

le validazioni comprendono:

  - build del progetto
  - esecuzione di test
  - esecuzione mutation test (pitest)
  - analisi sonar e controllo di aumento coverage

## Generazione Fix Issue

la pipeline si effettua i seguenti passaggi:

  - recupera un repository da gitlab
  - recupera le metriche sulle issue da sonar
  - per ogni issue, chiede al modello di generare una fix
  - valida il codice ottenuto dal modello
  - se la validazione va a buon fine allora apre una MR sul progetto su gitlab

le validazioni comprendono:

  - build del progetto
  - esecuzione di test
  - analisi sonar e controllo di risoluzione della issue
