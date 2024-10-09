// QUESTA PIPELINE PROVA A GENERARE UNA FIX PER OGNI ISSUE SU SONAR.
// SE IL MODELLO GENERA UN FILE ALLORA SI PROCEDE CON LA VALIDAZIONE DI QUESTO ATTRAVERSO LE SEGUENTI FASI:
//      -BUILD
//      -TEST
//      -NUOVA ANALISI SONAR CHE NON DEVE CONTENERE LA ISSUE DA RISOLVERE E NON NE DEVE AGGIUNGERE ALTRE
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
                        sh "curl -s -H 'Authorization: Basic ${auth}' 'http://192.168.1.101:9000/api/issues/search?components=java_demo_lel&s=FILE_LINE&issueStatuses=OPEN%2CCONFIRMED&ps=100&facets=cleanCodeAttributeCategories%2CimpactSoftwareQualities%2CcodeVariants&additionalFields=_all&timeZone=Europe%2FRome' > sonar_issues.json"
                        //sh "cat sonar_issues.json"
                        // Utilizza readJSON per leggere il file e assegnare il risultato ad una variabile
                        def sonarIssues = readJSON file: 'sonar_issues.json'


                        def effort = 0
                        def totEffort = sonarIssues.effortTotal
                        def totalIssueAnalized = 0
                        def generatedFix = 0
                        def approvedFix = 0
                        // Itera su ciascuna issue
                        sonarIssues.issues.each({   issue ->
                            // Dettagli dell'issue
                            issueKey = issue.key
                            
                            //se è una issue a livello di progetto e non di file esco
                            if (!issue.component.contains(":")) return

                            filePath = issue.component.split(":")[1]

                            // Informazioni sulla regola violata
                            def ruleKey = URLEncoder.encode(issue.rule, "UTF-8")
                            def ruleApiUrl = "http://192.168.1.101:9000/api/rules/show?key=${ruleKey}"
                            sh "curl -s -H 'Authorization: Basic ${auth}' '${ruleApiUrl}' > rule_details_${ruleKey}.json"
                            //sh "cat rule_details_${ruleKey}.json"
                            def ruleDetails = readJSON file: "rule_details_${ruleKey}.json"

                            ruleName = ruleDetails.rule.name
                            ruleMdDesc = ruleDetails.rule.mdDesc
                            ruleMdDesc = ruleMdDesc.replaceAll(/<[^>]+>/, '').trim()

                            // Stampa le informazioni raccolte
                            echo "Issue: ${issueKey}"
                            echo "File Path: ${filePath}"
                            echo "Rule violated: ${ruleName}"
                            echo "Rule description: ${ruleMdDesc}"
                            totalIssueAnalized = totalIssueAnalized + 1
                            fixedCode = callMistralApi(filePath, ruleName, ruleMdDesc, "java", issueKey)
                            if (fixedCode != ""){
                                echo "FIX GENERATA"
                                generatedFix = generatedFix + 1
                                fixCode(filePath, fixedCode)
                                def testOk = runTestsAndSonarCheck(sonarIssues, issueKey, effort)
                                if (testOk){
                                    echo "FIX APPROVATA"
                                    approvedFix = approvedFix + 1
                                    def sonarTmpIssues = readJSON file: "sonar_tmp_project_issues-${issueKey}.json"
                                    effort = effort + totEffort - sonarTmpIssues.effortTotal
                                    pushAndOpenMR(filePath, issueKey, gitlab_token, ruleName)
                                }else{
                                    echo "FIX NON APPROVATA"
                                }
                            }else{
                                echo "FIX NON GENERATA"
                            }
                            checkoutBranch("master")
                        })

                        echo "EFFORT RIDOTTO DI ${effort} MINUTI SU UN TOTALE DI ${totEffort}"
                        echo "ANALIZZATE ${totalIssueAnalized} ISSUE, GENERATA SOLUZIONE PER ${generatedFix}, DI QUESTE ${approvedFix} HANNO SUPERATO I TEST E SONO STATE PUSHATE"
                    }
                }
            }
        }
    }
}


def fixCode(def filePath, def fixedCode) {

    sh "rm -rf ${filePath}"
    writeFile file: filePath, text: fixedCode

}

def runTestsAndSonarCheck(def sonarIssues, def issueKey, def effort) {
    def exitCode = sh script: "mvn clean install", returnStatus:true
    if (exitCode != 0) return false
    sonarScan(issueKey)
    if (isSonarOk(sonarIssues, issueKey, effort)) return true
    else return false
}

def pushAndOpenMR(def filePath, def issueKey, def gitlab_token, def ruleName) {
        gitlab_username = 
        def idProject = 
        def branchName = "sonar-fix"
        def fileContent = readFile(filePath)
        def sourceBranchName = "fix-sonar-${issueKey}-${ruleName.replace(" ","_")}"
        
        sh "git push origin --delete ${sourceBranchName} || true"
        sh "git checkout -b ${sourceBranchName}"
        sh "git add ${filePath}"
        sh "git commit -m fix_autogenerata"
        sh "git push --set-upstream origin ${sourceBranchName}"
        
        def title = "fix issue ${issueKey} ${ruleName}"
        //MR
        curlCommand = """
        curl --request POST --header "PRIVATE-TOKEN: ${gitlab_token}" "https://gitlab.com/api/v4/projects/${idProject}/merge_requests" --form "source_branch=${sourceBranchName}" --form "target_branch=master" --form "title=${title}"
        """.trim()
        sh curlCommand
}


def checkoutBranch(String branch) {
    // Pulisce lo stato del workspace eliminando modifiche non commitate e resetta eventuali commit locali
    def gitlab_token = ""
    sh "cd .. ; rm -rf test-sonar-sql; git clone https://oauth2:${gitlab_token}@gitlab.com/[repo url].git"
    sh "git checkout ${branch} || true"
}


def callMistralApi(def filePath, def ruleName, def ruleMdDesc, def language, def issueKey){
    def authToken = ""
    def fileLines = readFile filePath
    def prompt = """
[INST] fix the code (${language} version 1.8) below to respect the rule.
Rule violated: ${ruleName}

Rule description: ${ruleMdDesc}







entire code to be fixed:
${fileLines}





fix the code modifing only the code that is violating the rule, don't change anything else. Give me in the output the entire code in this way (respect the output format):

 ```${language} 
 FIXED CODE 
 ```
[/INST]
"""
    print prompt
    def myJson=[:]
    myJson.put("temperature", 0.2)
    myJson.put("input", prompt)
    myJson.put("max_new_tokens", 1024)
    writeJSON file: "input-${issueKey}.json", json: myJson
    // Esegue la chiamata API
    sh """
    curl --fail -s -X POST [endpoint modello generativo] \\
       -H "Content-Type: application/json" \\
       -H "Authorization: Bearer ${authToken}" \\
       -d @input-${issueKey}.json \\
       -o mistralai_response-${issueKey}.json
    """
    // Stampa la risposta ottenuta
    //sh "cat mistralai_response.json"
    def mistralResponseJson = readJSON file: "mistralai_response-${issueKey}.json"
    def mistralResponse = mistralResponseJson.results[0].generated_text
    if (!mistralResponse.contains('```' + language)) return mistralResponse
    return mistralResponse.split('```' + language)[1].split('```')[0]
}

def sonarScan(def issueKey){
    def sonarTmpProjectName = "sonar-tmp-project-" + issueKey 

    deleteProjectIfExists(sonarTmpProjectName)
    createProjectIfNotExists(sonarTmpProjectName)

    def scannerHome = tool 'SonarScanner';
    withSonarQubeEnv('sonar') { // Sostituisci con il nome della configurazione SonarQube in Jenkins
        sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${sonarTmpProjectName} -Dsonar.projectName=${sonarTmpProjectName} -Dsonar.host.url=http://192.168.1.101:9000 -Dsonar.login=admin -Dsonar.password=password"
        sleep(30)
    }
}


def isSonarOk(def sonarIssues, def issueKey, def effort){
    def sonarTmpProjectName = "sonar-tmp-project-" + issueKey 
    def auth = "admin:password".bytes.encodeBase64().toString()
    sh "curl -s -H 'Authorization: Basic ${auth}' 'http://192.168.1.101:9000/api/issues/search?components=${sonarTmpProjectName}&s=FILE_LINE&issueStatuses=OPEN%2CCONFIRMED&ps=100&facets=cleanCodeAttributeCategories%2CimpactSoftwareQualities%2CcodeVariants&additionalFields=_all&timeZone=Europe%2FRome' > sonar_tmp_project_issues-${issueKey}.json"
    //sh "cat sonar_issues.json"
    // Utilizza readJSON per leggere il file e assegnare il risultato ad una variabile
    def sonarTmpIssues = readJSON file: "sonar_tmp_project_issues-${issueKey}.json"
    deleteProjectIfExists(sonarTmpProjectName)
    if (sonarTmpIssues.issues.size() < sonarIssues.issues.size()){
        
        return true
    } 
    else return false
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
