def call() {
    pipeline {
        agent { label 'built-in' } // Build işlemleri DevOps sunucusunda yapılır
        
        environment {
            // --- ORTAK ARAÇLAR ---
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_BASE_URL = "http://194.99.74.2:8081/repository"
            
            // PROJECT_KEY BURADAN KALDIRILDI. AŞAĞIDA HESAPLANACAK.
        }

        stages {
            stage('🧠 Beyin: Proje Analizi') {
                steps {
                    script {
                        // =========================================================
                        // 1. KİMLİK TESPİTİ (GÜVENLİ YÖNTEM)
                        // =========================================================
                        // Jenkins Job ismine güvenmek yerine, direkt Git URL'ine bakıyoruz.
                        // Örn: https://github.com/cngzbyrm/Shell.OneHub.UI.git -> Shell.OneHub.UI
                        
                        def gitUrl = scm.getUserRemoteConfigs()[0].getUrl()
                        def repoName = gitUrl.tokenize('/').last() // Son parçayı al
                        
                        // Eğer .git ile bitiyorsa temizle
                        if (repoName.endsWith('.git')) {
                            repoName = repoName.substring(0, repoName.length() - 4)
                        }
                        
                        // Global değişkene ata
                        env.PROJECT_KEY = repoName
                        
                        echo "🕵️ URL Analizi: ${gitUrl}"
                        echo "✅ Tespit Edilen Proje Anahtarı: ${env.PROJECT_KEY}"
                        
                        // =========================================================
                        // 2. PROJE KATALOĞU (TÜM AYARLAR BURADA)
                        // =========================================================
                        def projectCatalog = [
                            
                            // --- SENARYO 1: TEKİL PROJE (Shell.OneHub.UI) ---
                            'Shell.OneHub.UI': [ 
                                type: 'single',
                                solutionPath: './OneHub.sln', 
                                projectName: 'Shell.OneHub.UI', 
                                sonarKey: 'shell-onehub-ui', 
                                deploy: true
                                // jobTest: 'Deploy-to-Shell-TEST' // Opsiyonel: Özel deploy job'ı
                            ],

                            // --- SENARYO 2: MONOREPO (NishCMS) ---
                            'NishCMS': [
                                type: 'monorepo',
                                deploy: true,
                                subProjects: [
                                    [
                                        name: 'NishCMS.BackOffice',
                                        path: './Nish.BackOffice/Nish.BackOffice.sln',
                                        sonarKey: 'NishCMS-BackOffice',
                                        repoTest: 'nexus-nabusoft-nishbackoffice-test',
                                        jobTest: 'Deploy-to-Nabusoft-TEST'
                                    ],
                                    [
                                        name: 'NishCMS.Store',
                                        path: './Nish.Store/Nish.Store.csproj', 
                                        sonarKey: 'NishCMS-Store',
                                        repoTest: 'nexus-candidates-maven', 
                                        jobTest: 'Deploy-to-Nabusoft-Store-TEST'
                                    ]
                                ]
                            ]
                        ]

                        // =========================================================
                        // 3. KARAR MEKANİZMASI
                        // =========================================================
                        def myConfig = projectCatalog[env.PROJECT_KEY]

                        if (!myConfig) {
                            echo "❌ MEVCUT KATALOG LİSTESİ: ${projectCatalog.keySet()}"
                            error "❌ HATA: '${env.PROJECT_KEY}' kataloğa eklenmemiş! Lütfen nabusoftBrain.groovy dosyasına ekle."
                        }

                        if (myConfig.type == 'monorepo') {
                            echo "✅ MOD: Monorepo (Çoklu Proje) Olarak Çalıştırılıyor..."
                            runMonorepoBuild(myConfig)
                        } else {
                            echo "✅ MOD: Single (Standart Proje) Olarak Çalıştırılıyor..."
                            runSingleBuild(myConfig)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// FONKSİYON 1: TEKİL PROJELER (Eski Usül - Shell vb.)
// =========================================================================
def runSingleBuild(config) {
    // 1. Kaynak Kod Çekme
    stage('Kaynak Kod') {
        checkout scm
    }

    // 2. SonarQube Analizi Başlat
    stage('SonarQube Analizi') {
        withSonarQubeEnv(env.SONAR_SERVER) {
            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                bat "${env.SCANNER_TOOL} begin /k:\"${config.sonarKey}\" /d:sonar.token=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://194.99.74.2:9000\""
            }
        }
    }

    // 3. Build & Publish
    stage('Build & Publish') {
        bat "dotnet restore ${config.solutionPath}"
        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
        
        // Sonar Analizini Bitir
        withSonarQubeEnv(env.SONAR_SERVER) {
             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                  bat "${env.SCANNER_TOOL} end /d:sonar.token=\"%SONAR_TOKEN%\""
             }
        }
        
        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
    }

    // 4. Paketleme ve Ortam Kararı
    stage('Paketleme ve Ortam Kararı') {
        env.ENV_TAG = ""
        env.TARGET_JOB = ""
        env.NEXUS_REPO = 'nexus-candidates-maven' // Varsayılan Repo

        // Ortam Kontrolü (Branch'e göre)
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

        // Eğer katalogda özel bir job tanımlıysa onu kullan (Örn: jobTest)
        if (env.ENV_TAG == "test" && config.jobTest) {
            env.TARGET_JOB = config.jobTest
        }

        // Zip Oluşturma
        def version = "1.0.${env.BUILD_NUMBER}"
        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
        
        if (fileExists(env.ZIP_TOOL)) {
             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
        } else {
             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
        }
        
        // Değişkenleri dışarı taşı
        env.FINAL_ZIP_NAME = zipName
        env.FINAL_VERSION = version
    }

    // 5. Nexus Upload & Deploy
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
        } else {
            echo "⚠️ Deploy adımı atlandı. (Deploy kapalı veya uygun branch değil)"
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