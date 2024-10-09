// QUESTA PIPELINE PROVA A GENERARE UNA CLASSE DI TEST PER OGNI FILE DEL QUALE SONAR CERCA LA COVERAGE.
// SE IL MODELLO GENERA UN FILE ALLORA SI PROCEDE CON LA VALIDAZIONE DI QUESTO ATTRAVERSO LE SEGUENTI FASI:
//      -BUILD
//      -MUTATION TEST
//      -NUOVA ANALISI SONAR CHE DEVE AVERE UNA COVERAGE MAGGIORE PER IL FILE IN QEUSTIONE
//
// SE SI SUPERANO TUTTE QUESTE VALIDAZIONI ALLORA VIENE APERTA UNA MR CON L'AGGIUNTA DEL FILE
def issueKey
def filePath
def linesInvolved
def ruleName
def ruleMdDesc
def fixedCode
def commitMessage
def gitlab_token = ""

pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                sh "rm -rf ./*"
                // Checkout del codice dal repository GitLab
                sh "git clone https://oauth2:${gitlab_token}@gitlab.com/[repo url].git"
                sh 'git config --global user.email "test@test.com"'
                sh 'git config --global user.name "Automation"'
            }
        }
        
        stage('Fetch SonarQube Issues') {
            steps {
                script {
                    dir('test-sonar-sql') {
                        // Codifica le credenziali in Base64 per l'autenticazione Basic Auth
                        def auth = "admin:password".bytes.encodeBase64().toString()

                        // Esegui la chiamata API e salva la risposta in un file temporaneo
                        sh "curl -s -H 'Authorization: Basic ${auth}' 'http://192.168.1.101:9000/api/measures/component_tree?component=java_demo_lel&metricKeys=coverage&qualifiers=FIL' > sonar_issues.json"
                        //sh "cat sonar_issues.json"
                        // Utilizza readJSON per leggere il file e assegnare il risultato ad una variabile
                        def sonarIssues = readJSON file: 'sonar_issues.json'


                        def coverageTot = 0.0
                        def totFile = 0
                        def testFileGenerated = 0
                        def testFileApproved = 0
                        // Itera su ciascuna issue
                        sonarIssues.components.each({   component ->
                            // Dettagli dell'issue
                            filePath = component.path
                            language = component.language

                            //esco se è un file senza coverage
                            if (component.measures.size() == 0) return
                            
                            def coverage = Float.parseFloat(component.measures[0].value)
                            coverageTot = coverageTot + coverage
                            totFile = totFile + 1

                            echo "File Path: ${filePath}"
                            echo "Coverage: ${coverage}"
                            //esco se test già presenti per il file
                            //if (coverage > 0) return

                            echo "Avvio generazione classi di test"

                            mistralResponse = callMistralApi(filePath, language )
                            if (mistralResponse != ""){
                                def testFilePath = mistralResponse.split("TEST_PATH:")[1].split("CONTENT_FILE")[0].trim()
                                def testCode = mistralResponse.split('```' + language)[1].split('```')[0]
                                echo "FIX GENERATA"

                                echo testCode
                                testFileGenerated = testFileGenerated + 1
                                
                                writeFile file: testFilePath, text: testCode

                                def testOk = runTestsAndSonarCheck(filePath)
                                if (testOk) {
                                    def newCoverage = getNewCoverage(filePath)
                                    if (newCoverage > coverage) {
                                        echo "FIX APPROVATA"
                                        pushAndOpenMR(testFilePath, gitlab_token)
                                    }else{
                                        echo "FIX NON APPROVATA, NON AUMENTA LA COVERAGE"
                                    }
                                }else{
                                    echo "FIX NON APPROVATA, FALLISCONO I TEST"
                                }
                                sh "rm -rf ${testFilePath}"
                            }else{
                                echo "FIX NON GENERATA"
                            }
                            checkoutBranch("master")
                        })
                    }
                }
            }
        }
    }
}


def runTestsAndSonarCheck(def filePath) {
    def exitCode = sh script: "mvn clean install", returnStatus: true
    if (exitCode != 0) {
        echo "FALLITI UNIT TEST"
        return false
    }
    exitCode = sh script: "mvn org.pitest:pitest-maven:mutationCoverage", returnStatus: true
    if (exitCode != 0) {
        echo "FALLITI MUTATION TEST"
        return false
    }
    sonarScan(filePath)
    return true
}

def pushAndOpenMR(def testFilePath,def gitlab_token) {
        gitlab_username = 
        def idProject = 
        def fileContent = readFile(testFilePath)
        def sourceBranchName = "fix-sonar-coverage-${testFilePath.split('/')[-1]}"
        
        sh "git push origin --delete ${sourceBranchName} || true"
        sh "git checkout -b ${sourceBranchName}"
        sh "git add ${testFilePath}"
        sh "git commit -m fix_autogenerata"
        sh "git push --set-upstream origin ${sourceBranchName}"
        
        def title = "aggiunta classe di test  ${testFilePath}"
        //MR
        curlCommand = """
        curl --request POST --header "PRIVATE-TOKEN: ${gitlab_token}" "https://gitlab.com/api/v4/projects/${idProject}/merge_requests" --form "source_branch=${sourceBranchName}" --form "target_branch=master" --form "title=${title}"
        """.trim()
        sh curlCommand
}


def checkoutBranch(String branch) {
    // Pulisce lo stato del workspace eliminando modifiche non commitate e resetta eventuali commit locali
        sh "git checkout ${branch} || true"
}


def callMistralApi(def filePath, def language){
    def authToken = ""
    def fileLines = readFile filePath
    def prompt = """
[INST]Ti fornirò il percorso di un file Java (versione 1.8) e il suo contenuto. La tua missione è di creare uno unit test utilizzando JUnit 4.11. È cruciale che il file di test sia compilabile e che tu includa tutti gli import necessari, **incluso un import esplicito del file Java target** specificato da ${filePath}.

Quando crei il file di test:
- Assicurati di importare esplicitamente la classe Java target usando il percorso fornito (${filePath}). Questo è essenziale per garantire che il file di test possa accedere alla classe che deve testare.
- Fornisci il percorso del file di test generato e includi il contenuto del file di test, seguendo le convenzioni di denominazione standard per i test (ad esempio, se il file originale è `MyClass.java`, il file di test dovrebbe essere `MyClassTest.java`).
- Copri tutti i metodi pubblici della classe target con almeno un caso di test ciascuno.

Path file originale: ./${filePath}

Contenuto del file originale:
${fileLines}

Fornisci il file di test nel seguente formato:

TEST_PATH: [Inserisci qui il percorso del file di test]

CONTENT_FILE:
```${language}
[Inserisci qui il contenuto del file di test, assicurandoti di includere l'import del file Java target come indicato sopra]
```
[/INST]
"""
    print prompt
    def myJson=[:]
    myJson.put("temperature", 0.2)
    myJson.put("input", prompt)
    writeJSON file: "input-${filePath.split('/')[-1]}.json", json: myJson
    // Esegue la chiamata API
    sh """
    curl --fail -s -X POST "[endpoint modello]" \\
       -H "Content-Type: application/json" \\
       -H "Authorization: Bearer ${authToken}" \\
       -d @input-${filePath.split('/')[-1]}.json \\
       -o mistralai_response-${filePath.split('/')[-1]}.json
    """
    // Stampa la risposta ottenuta
    //sh "cat mistralai_response.json"
    def mistralResponseJson = readJSON file: "mistralai_response-${filePath.split('/')[-1]}.json"
    def mistralResponse = mistralResponseJson.results[0].generated_text
    if (!mistralResponse.contains('```' + language)) return ""
    if (!mistralResponse.contains('TEST_PATH:')) return ""
    if (!mistralResponse.contains('CONTENT_FILE:')) return ""
    return mistralResponse
}

def sonarScan(def filePath){
    def sonarTmpProjectName = "sonar-tmp-project-" + filePath.split('/')[-1] 

    deleteProjectIfExists(sonarTmpProjectName)
    createProjectIfNotExists(sonarTmpProjectName)

    def scannerHome = tool 'SonarScanner';
    withSonarQubeEnv('sonar') { // Sostituisci con il nome della configurazione SonarQube in Jenkins
        sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${sonarTmpProjectName} -Dsonar.projectName=${sonarTmpProjectName} -Dsonar.host.url=http://192.168.1.101:9000 -Dsonar.login=admin -Dsonar.password=password"
        sleep(30)
    }
}


def getNewCoverage(def filePath){
    def sonarTmpProjectName = "sonar-tmp-project-" + filePath.split('/')[-1]
    def auth = "admin:password".bytes.encodeBase64().toString()
    sh "curl -s -H 'Authorization: Basic ${auth}' 'http://192.168.1.101:9000/api/measures/component_tree?component=${sonarTmpProjectName}&metricKeys=coverage&qualifiers=FIL' > sonar_tmp_project_coverage${filePath.split('/')[-1]}.json"
    // Utilizza readJSON per leggere il file e assegnare il risultato ad una variabile
    def sonarTmpCoverage = readJSON file: "sonar_tmp_project_coverage${filePath.split('/')[-1]}.json"
    deleteProjectIfExists(sonarTmpProjectName)
    def newCoverage = 0
    sonarTmpCoverage.components.each({ component ->
        if (component.path == filePath) newCoverage = Float.parseFloat(component.measures[0].value)
    })
    return newCoverage
}


// Funzione per eliminare un progetto SonarQube
def deleteProjectIfExists(projectKey) {
    def auth = "admin:password".bytes.encodeBase64().toString()
    sh "curl -H 'Authorization: Basic ${auth}' -X POST 192.168.1.101:9000/api/projects/delete?project=${projectKey}"
}

def createProjectIfNotExists(projectKey){
    def auth = "admin:password".bytes.encodeBase64().toString()
    sh "curl -H 'Authorization: Basic ${auth}' -X POST '192.168.1.101:9000/api/projects/create' -d 'name=${projectKey}' -d 'project=${projectKey}'"
}
