def call() {
    pipeline {
        agent { label 'built-in' } // Build işlemleri DevOps sunucusunda
        
        environment {
            // --- ORTAK ARAÇLAR ---
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_BASE_URL = "http://194.99.74.2:8081/repository"
            
            // Proje Adını Git URL veya Job isminden yakala
            // Örn: "NishCMS" veya "Shell.OneHub"
            PROJECT_KEY = "${env.JOB_NAME.tokenize('/')[0]}" 
        }

        stages {
            stage('🧠 Beyin: Proje Analizi') {
                steps {
                    script {
                        echo "🕵️ Kimlik Tespiti: ${env.PROJECT_KEY}"
                        
                        // =========================================================
                        // PROJE KATALOĞU (TÜM AYARLAR BURADA)
                        // =========================================================
                        def projectCatalog = [
                            
                            // 1. ESKİ USÜL (TEKİL) PROJE ÖRNEĞİ
                         'Shell.OneHub.UI': [ // <-- Repo ismin bu olduğu için anahtarı değiştirdim
                                type: 'single',
                                solutionPath: './OneHub.sln', // <-- Verdiğin yeni solution yolu
                                projectName: 'Shell.OneHub.UI', // <-- Verdiğin yeni Artifact ID
                                sonarKey: 'shell-onehub-ui', // <-- Verdiğin yeni Sonar Key
                                deploy: true,
                                
                                // Eğer test ortamı için özel bir job ismi varsa buraya ekle:
                                // jobTest: 'Deploy-to-Shell-TEST' 
                                // Eklemezsen varsayılan 'Deploy-to-TEST' çalışır.
                            ],

                            // 2. YENİ USÜL (MONOREPO) PROJE ÖRNEĞİ
                            'NishCMS': [
                                type: 'monorepo',
                                deploy: true,
                                subProjects: [
                                    [
                                        name: 'NishCMS.BackOffice',
                                        path: './Nish.BackOffice/Nish.BackOffice.sln',
                                        sonarKey: 'NishCMS-BackOffice',
                                        // Özel Repo ve Job Tanımı
                                        repoTest: 'nexus-nabusoft-nishbackoffice-test',
                                        jobTest: 'Deploy-to-Nabusoft-TEST'
                                    ],
                                   [
                                        name: 'NishCMS.Store',
                                        path: './Nish.Store/Nish.Store.csproj', 
                                        sonarKey: 'NishCMS-Store',
                                        repoTest: 'nexus-nabusoft-nishstore-test',
                                        jobTest: 'Deploy-to-Nabusoft-Store-TEST'
                                    ],
                                ]
                            ]
                        ]

                        // --- KARAR MEKANİZMASI ---
                        def myConfig = projectCatalog[env.PROJECT_KEY]

                        if (!myConfig) {
                            error "❌ HATA: '${env.PROJECT_KEY}' kataloğa eklenmemiş! Lütfen nabusoftBrain.groovy dosyasına ekle."
                        }

                        if (myConfig.type == 'monorepo') {
                            echo "✅ MOD: Monorepo (Çoklu Proje)"
                            runMonorepoBuild(myConfig)
                        } else {
                            echo "✅ MOD: Single (Standart Proje)"
                            runSingleBuild(myConfig)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// FONKSİYON 1: TEKİL PROJELER (Senin Eski Kodun Mantığıyla)
// =========================================================================
def runSingleBuild(config) {
    // Stage içinde değil, script bloğu içinde çağırıyoruz
    // Kod çekme işlemi
    stage('Kaynak Kod') {
        checkout scm
    }

    stage('SonarQube Analizi') {
        withSonarQubeEnv(env.SONAR_SERVER) {
            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                bat "${env.SCANNER_TOOL} begin /k:\"${config.sonarKey}\" /d:sonar.token=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://194.99.74.2:9000\""
            }
        }
    }

    stage('Build & Publish') {
        bat "dotnet restore ${config.solutionPath}"
        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
        
        withSonarQubeEnv(env.SONAR_SERVER) {
             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                  bat "${env.SCANNER_TOOL} end /d:sonar.token=\"%SONAR_TOKEN%\""
             }
        }
        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
    }

    stage('Paketleme ve Ortam Kararı') {
        env.ENV_TAG = ""
        env.TARGET_JOB = ""
        env.NEXUS_REPO = 'nexus-candidates-maven' // Varsayılan

        // --- SENİN ESKİ IF/ELSE MANTIĞIN ---
        if (env.BRANCH_NAME == 'test' || env.BRANCH_NAME == 'test1') {
            env.ENV_TAG = "test"
            env.TARGET_JOB = "Deploy-to-TEST"
        } 
        else if (env.BRANCH_NAME == 'uat-staging') {
            env.ENV_TAG = "staging"
            env.TARGET_JOB = "Deploy-to-STAGING"
        }
        else if (env.BRANCH_NAME == 'production') {
            env.ENV_TAG = "prod"
            env.TARGET_JOB = "Deploy-to-PROD" 
        }
        else {
            env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
        }

        // Zip Oluşturma
        def version = "1.0.${env.BUILD_NUMBER}"
        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
        
        if (fileExists(env.ZIP_TOOL)) {
             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
        } else {
             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
        }
        
        // Değişkenleri dışarı taşı (Scope için)
        env.FINAL_ZIP_NAME = zipName
        env.FINAL_VERSION = version
    }

    stage('Nexus Upload & Deploy') {
        if (env.TARGET_JOB != "" && config.deploy == true) {
            nexusArtifactUploader(
                nexusVersion: 'nexus3', protocol: 'http', nexusUrl: '194.99.74.2:8081',
                groupId: 'com.nabusoft', version: env.FINAL_VERSION, repository: env.NEXUS_REPO,
                credentialsId: env.NEXUS_CRED_ID,
                artifacts: [[artifactId: config.projectName, classifier: '', file: env.FINAL_ZIP_NAME, type: 'zip']]
            )
            
            echo "🚀 Tetikleniyor: ${env.TARGET_JOB}"
            build job: env.TARGET_JOB, parameters: [
                string(name: 'VERSION', value: env.FINAL_VERSION),
                string(name: 'ARTIFACT_NAME', value: env.FINAL_ZIP_NAME)
            ], wait: false
        }
    }
}

// =========================================================================
// FONKSİYON 2: MONOREPO PROJELER (NishCMS - Paralel & Özel Repolu)
// =========================================================================
def runMonorepoBuild(config) {
    stage('Kaynak Kod') {
        checkout scm
    }

    stage('Projeleri İşle (Paralel)') {
        def builders = [:]

        config.subProjects.each { proj ->
            builders["Build: ${proj.name}"] = {
                stage("Süreç: ${proj.name}") {
                    
                    // 1. SONAR
                    withSonarQubeEnv('SonarQube') {
                        withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                            bat "${env.SCANNER_TOOL} begin /k:\"${proj.sonarKey}\" /d:sonar.token=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://194.99.74.2:9000\""
                        }
                    }

                    // 2. BUILD
                    def outputDir = "./publish_output_${proj.name.replace('.', '_')}"
                    bat "dotnet restore ${proj.path}"
                    bat "dotnet build ${proj.path} -c Release --no-restore"
                    
                    withSonarQubeEnv('SonarQube') {
                         withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                              bat "${env.SCANNER_TOOL} end /d:sonar.token=\"%SONAR_TOKEN%\""
                         }
                    }
                    bat "dotnet publish ${proj.path} -c Release -o ${outputDir}"

                    // 3. ZIP
                    def version = "1.0.${env.BUILD_NUMBER}"
                    def zipName = "${proj.name}-${env.BRANCH_NAME}-v${version}.zip"
                    if (fileExists(env.ZIP_TOOL)) {
                         bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ${outputDir}/*"
                    } else {
                         powershell "Compress-Archive -Path ${outputDir}/* -DestinationPath ./${zipName} -Force"
                    }

                    // 4. UPLOAD (Özel Repo Ayarı Burada)
                    def targetRepo = proj.repoTest ? proj.repoTest : 'nexus-candidates-maven'
                    
                    nexusArtifactUploader(
                        nexusVersion: 'nexus3', protocol: 'http', nexusUrl: '194.99.74.2:8081',
                        groupId: 'com.nabusoft', version: version, repository: targetRepo,
                        credentialsId: env.NEXUS_CRED_ID,
                        artifacts: [[artifactId: proj.name, classifier: '', file: zipName, type: 'zip']]
                    )

                    // 5. DEPLOY (Özel Job Ayarı Burada)
                    if (config.deploy == true && env.BRANCH_NAME == 'test' && proj.jobTest) {
                        echo "🚀 ${proj.name} -> Tetikleniyor: ${proj.jobTest}"
                        build job: proj.jobTest, parameters: [
                            string(name: 'VERSION', value: version),
                            string(name: 'ARTIFACT_NAME', value: zipName)
                        ], wait: false
                    }
                }
            }
        }
        parallel builders
    }
}