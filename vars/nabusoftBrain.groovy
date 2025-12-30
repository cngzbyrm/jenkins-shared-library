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
            SONAR_SCANNER_OPTS = "-Xmx2048m"
            // Taranmayacak Dosyalar (HIZ İÇİN ÇOK ÖNEMLİ)
            // publish_output: Derlenmiş dosyalar
            // wwwroot/lib, assets/plugins: Hazır kütüphaneler
            // min.js, min.css: Sıkıştırılmış dosyalar
            SONAR_EXCLUSIONS = "**/publish_output/**,**/bin/**,**/obj/**,**/wwwroot/lib/**,**/assets/plugins/**,**/*.min.js,**/*.min.css,**/jquery*.js,**/bundleconfig.json"
        }

        stages {
            stage('Beyin: Proje Analizi') {
                steps {
                    script {
                        // =========================================================
                        // 1. KİMLİK TESPİTİ (GÜVENLİ YÖNTEM)
                        // =========================================================
                        def gitUrl = scm.getUserRemoteConfigs()[0].getUrl()
                        def repoName = gitUrl.tokenize('/').last()
                        if (repoName.endsWith('.git')) {
                            repoName = repoName.substring(0, repoName.length() - 4)
                        }
                        env.PROJECT_KEY = repoName
                        
                        echo "URL Analizi: ${gitUrl}"
                        echo "Tespit Edilen Proje: ${env.PROJECT_KEY}"
                        
                        // =========================================================
                        // 2. PROJE KATALOĞU (TÜM AYARLAR BURADA)
                        // =========================================================
                        def projectCatalog = [
                            'Shell.OneHub.UI': [ 
                                type: 'single',
                                solutionPath: './OneHub.sln', 
                                projectName: 'Shell.OneHub.UI', 
                                sonarKey: 'shell-onehub-ui', 
                                deploy: true
                            ],
                            'NishCMS': [
                                type: 'monorepo',
                                deploy: true,
                                subProjects: [
                                    [
                                        name: 'NishCMS.BackOffice',
                                        path: './Nish.BackOffice/Nish.BackOffice.sln',
                                        sonarKey: 'NishCMS-BackOffice',
                                        repoTest: 'nexus-nabusoft-nishbackoffice-test',
                                        jobTest: 'Deploy-to-Nabusoft-NishBackoffice-TEST'
                                    ],
                                    [
                                        name: 'NishCMS.Store',
                                        path: './Nish.Store/Nish.Store.csproj', 
                                        sonarKey: 'NishCMS-Store',
                                        repoTest: 'nexus-nabusoft-store-test', 
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
                            error "❌ HATA: '${env.PROJECT_KEY}' kataloğa eklenmemiş!"
                        }

                        if (myConfig.type == 'monorepo') {
                            runMonorepoBuild(myConfig)
                        } else {
                            runSingleBuild(myConfig)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// FONKSİYON 1: TEKİL PROJELER (SINGLE - OPTİMİZE EDİLDİ)
// =========================================================================
def runSingleBuild(config) {
    stage('Kaynak Kod') {
        checkout scm
    }

    stage('Build & Analiz') {
        // Sonar Başlat (EXCLUSIONS EKLENDİ)
        withSonarQubeEnv(env.SONAR_SERVER) {
            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                bat """
                    "${env.SCANNER_TOOL}" begin /k:"${config.sonarKey}" ^
                    /d:sonar.token="%SONAR_TOKEN%" ^
                    /d:sonar.host.url="http://194.99.74.2:9000" ^
                    /d:sonar.exclusions="${env.SONAR_EXCLUSIONS}"
                """
            }
        }

        // Build (Restore dahil)
        bat "dotnet restore ${config.solutionPath}"
        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
        
        // Sonar Bitir
        withSonarQubeEnv(env.SONAR_SERVER) {
             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                  bat "\"${env.SCANNER_TOOL}\" end /d:sonar.token=\"%SONAR_TOKEN%\""
             }
        }
        
        // Publish (TEKRAR BUILD ETMEDEN - HIZ KAZANCI)
        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output --no-build"
    }

    stage('Paketleme ve Ortam Kararı') {
        env.ENV_TAG = ""
        env.TARGET_JOB = ""
        env.NEXUS_REPO = 'nexus-candidates-maven' 

        if (env.BRANCH_NAME == 'test' || env.BRANCH_NAME == 'test1') {
            env.ENV_TAG = "test"
            env.TARGET_JOB = "Deploy-to-TEST"
        } 
        else if (env.BRANCH_NAME == 'uat-staging' || env.BRANCH_NAME == 'uat-staging1') {
            env.ENV_TAG = "staging"
            env.TARGET_JOB = "Deploy-to-STAGING"
        }
        else if (env.BRANCH_NAME == 'production' ||  env.BRANCH_NAME == 'production1') {
            env.ENV_TAG = "prod"
            env.TARGET_JOB = "Deploy-to-PROD" 
        }
        else {
            env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
        }

        if (env.ENV_TAG == "test" && config.jobTest) {
            env.TARGET_JOB = config.jobTest
        }

        def version = "1.0.${env.BUILD_NUMBER}"
        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
        
        if (fileExists(env.ZIP_TOOL)) {
             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
        } else {
             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
        }
        
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
// FONKSİYON 2: MONOREPO PROJELER (TURBO MOD: Incremental + Stash + NoBuild)
// =========================================================================
def runMonorepoBuild(config) {
    
    def changedFiles = ""
    def isManualBuild = currentBuild.getBuildCauses().toString().contains('UserIdCause')
    
    stage('Değişiklik Analizi ve Hazırlık') {
        // 1. Kodu SADECE BURADA İNDİRİYORUZ (HIZ İÇİN)
        checkout scm
        
        try {
            changedFiles = bat(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()
        } catch (Exception e) {
            echo "⚠️ Git geçmişi okunamadı. Güvenlik için her şey derlenecek."
            changedFiles = "ALL"
        }
        
        echo "📝 Değişen Dosyalar:\n${changedFiles}"
        if (isManualBuild) { echo "👤 Manuel tetikleme: Tüm projeler derlenecek." }
        
        // 2. İndirilen kodu Jenkins içinde paketle (Diğer aşamalarda indirmemek için)
        stash name: 'source-code', includes: '**', useDefaultExcludes: false
    }

    stage('Projeleri İşle (Paralel)') {
        def builders = [:]

        config.subProjects.each { proj ->
            def projFolder = proj.path.split('/')[1] 
            
            def shouldBuild = isManualBuild || 
                              changedFiles.contains("ALL") || 
                              changedFiles.contains(projFolder) ||
                              env.BRANCH_NAME == 'production'

            if (shouldBuild) {
                builders["🚀 ${proj.name}"] = {
                    stage("Süreç: ${proj.name}") {
                        // İzole çalışma alanı
                        ws("workspace/${proj.name}") {
                            
                            // 3. İndirmek yerine, paketlenmiş kodu aç (ÇOK HIZLI)
                            unstash 'source-code'
                            
                            // SONAR (EXCLUSIONS EKLENDİ)
                            withSonarQubeEnv('SonarQube') {
                                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                                    bat """
                                        "${env.SCANNER_TOOL}" begin /k:"${proj.sonarKey}" ^
                                        /d:sonar.token="%SONAR_TOKEN%" ^
                                        /d:sonar.host.url="http://194.99.74.2:9000" ^
                                        /d:sonar.exclusions="${env.SONAR_EXCLUSIONS}"
                                    """
                                }
                            }

                            // BUILD (Optimize Edildi)
                            def outputDir = "./publish_output_${proj.name.replace('.', '_')}"
                            bat "dotnet restore ${proj.path}"
                            bat "dotnet build ${proj.path} -c Release --no-restore"
                            
                            withSonarQubeEnv('SonarQube') {
                                 withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                                      bat "\"${env.SCANNER_TOOL}\" end /d:sonar.token=\"%SONAR_TOKEN%\""
                                 }
                            }
                            
                            // PUBLISH (Tekrar derlemeyi engelle: --no-build)
                            bat "dotnet publish ${proj.path} -c Release -o ${outputDir} --no-build"

                            // ZIP
                            def version = "1.0.${env.BUILD_NUMBER}"
                            def zipName = "${proj.name}-${env.BRANCH_NAME}-v${version}.zip"
                            if (fileExists(env.ZIP_TOOL)) {
                                 bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ${outputDir}/*"
                            } else {
                                 powershell "Compress-Archive -Path ${outputDir}/* -DestinationPath ./${zipName} -Force"
                            }

                            // UPLOAD
                            def targetRepo = proj.repoTest ? proj.repoTest : 'nexus-candidates-maven'
                            nexusArtifactUploader(
                                nexusVersion: 'nexus3', protocol: 'http', nexusUrl: '194.99.74.2:8081',
                                groupId: 'com.nabusoft', version: version, repository: targetRepo,
                                credentialsId: env.NEXUS_CRED_ID,
                                artifacts: [[artifactId: proj.name, classifier: '', file: zipName, type: 'zip']]
                            )

                            // DEPLOY TETİKLEME
                            if (config.deploy == true && env.BRANCH_NAME == 'test' && proj.jobTest) {
                                echo "🚀 ${proj.name} -> Tetikleniyor: ${proj.jobTest}"
                                build job: proj.jobTest, parameters: [
                                    string(name: 'VERSION', value: version),
                                    string(name: 'ARTIFACT_NAME', value: zipName)
                                ], wait: false
                            }
                        } // ws sonu
                    }
                }
            } else {
                builders["💤 ${proj.name} (Atlandı)"] = {
                    stage("Atlandı: ${proj.name}") {
                        echo "🛑 ${proj.name} için değişiklik yok. Build atlandı."
                    }
                }
            }
        }
        parallel builders
    }
}