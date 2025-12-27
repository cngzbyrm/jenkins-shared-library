def call(Map config) {
    pipeline {
        agent { label 'built-in' } // Build işlemleri DevOps sunucusunda yapılır
        
        environment {
            // --- JENKINS & ARAÇ AYARLARI ---
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            
            // Nexus Varsayılan Ayarları (Başlangıç değeri)
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates-maven' // Varsayılan repo
            
            // --- SUNUCU ADRESLERİ ---
            SONAR_HOST_URL = "http://194.99.74.2:9000"
            NEXUS_HOST_URL = "http://194.99.74.2:8081"
            
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
            
            // Araç Yolları
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
        }

        stages {
            // 1. KAYNAK KOD ÇEKME
            stage('Kaynak Kod') {
                steps {
                    git branch: "${env.BRANCH_NAME}", credentialsId: 'github-login', url: config.gitUrl
                }
            }

            // 2. SONARQUBE ANALİZİ
            stage('SonarQube Analizi') {
                steps {
                    script {
                        withSonarQubeEnv(env.SONAR_SERVER) {
                            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                bat "${env.SCANNER_TOOL} begin /k:\"${config.sonarProjectKey}\" /d:sonar.token=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"${env.SONAR_HOST_URL}\" /d:sonar.exclusions=\"**/wwwroot/lib/**,**/wwwroot/assets/**,**/node_modules/**,**/*.min.css,**/*.min.js,**/*.xml,**/*.json,**/*.png,**/*.jpg\""
                            }
                        }
                    }
                }
            }

            // 3. BUILD & PUBLISH
            stage('Build & Publish') {
                steps {
                    script {
                        // Restore & Build
                        bat "dotnet restore ${config.solutionPath}"
                        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
                        
                        // Sonar Analizini Bitir
                        withSonarQubeEnv(env.SONAR_SERVER) {
                             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                  bat "${env.SCANNER_TOOL} end /d:sonar.token=\"%SONAR_TOKEN%\""
                             }
                        }
                        
                        // Publish Al
                        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                    }
                }
            }

            // 4. PAKETLEME VE ORTAM KARARI (KRİTİK AŞAMA)
            stage('Paketleme ve Ortam Kararı') {
                steps {
                    script {
                        env.ENV_TAG = ""
                        env.TARGET_JOB = "" 

                        // Debug: Hatayı görmek için konsola yazdırıyoruz
                        echo "🔍 DEBUG: Gelen Proje İsmi: '${config.projectName}'"
                        echo "🔍 DEBUG: Çalışan Branch: '${env.CURRENT_BRANCH}'"

                        // ---------------------------------------------------------
                        // SENARYO A: NISH CMS BACKOFFICE PROJESİ
                        // ---------------------------------------------------------
                        if (config.projectName == 'NishCMS.BackOffice' || config.projectName == 'NishCMS') {
                             echo "✅ Proje Tanındı: NishCMS BackOffice"

                             // A1. TEST ORTAMI (Nabusoft Sunucusu - Özel Repo)
                             if (env.CURRENT_BRANCH == 'test') {
                                 echo "   -> Hedef: Nabusoft Sunucusu (TEST)"
                                 echo "   -> Depo Değiştiriliyor: nexus-nabusoft-nishbackoffice-test"
                                 
                                 env.ENV_TAG = "test"
                                 env.TARGET_JOB = "Deploy-to-Nabusoft-TEST" 
                                 env.NEXUS_REPO = 'nexus-nabusoft-nishbackoffice-test' // Özel Repo
                             }
                             
                             // A2. PRODUCTION ORTAMI (Nabusoft Sunucusu veya ISTS201)
                             else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                                 echo "   -> Hedef: Nabusoft Sunucusu (PROD)"
                                 env.ENV_TAG = "prod"
                                 
                                 // Prod için de özel repo varsa buraya env.NEXUS_REPO = '...' ekleyebilirsin
                                 env.TARGET_JOB = "Deploy-to-Nabusoft-PROD" 
                             }
                             
                             // A3. DİĞER (DEV)
                             else {
                                 env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                             }
                        }

                        // ---------------------------------------------------------
                        // SENARYO B: DİĞER TÜM PROJELER (ESKİ MANTIK)
                        // ---------------------------------------------------------
                        else {
                            echo "ℹ️ Standart Proje Akışı (Shell.OneHub vb.)"

                            if (env.CURRENT_BRANCH == 'test' || env.CURRENT_BRANCH == 'test1') {
                                env.ENV_TAG = "test"
                                env.TARGET_JOB = "Deploy-to-TEST"
                            } 
                            else if (env.CURRENT_BRANCH == 'uat-staging' || env.CURRENT_BRANCH == 'uat-staging1') {
                                env.ENV_TAG = "staging"
                                env.TARGET_JOB = "Deploy-to-STAGING"
                            }
                            else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                                env.ENV_TAG = "prod"
                                env.TARGET_JOB = "Deploy-to-PROD" 
                            }
                            else {
                                env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                            }
                        }

                        // ---------------------------------------------------------
                        // ORTAK PAKETLEME (ZIP)
                        // ---------------------------------------------------------
                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
                        
                        // 7-Zip varsa kullan, yoksa PowerShell
                        if (fileExists(env.ZIP_TOOL)) {
                             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
                        } else {
                             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
                        }
                        
                        env.FINAL_ARTIFACT_NAME = zipName
                    }
                }
            }

            // 5. NEXUS UPLOAD VE DEPLOY TETİKLEME
            stage('🚀 Nexus Upload & Deploy Tetikleme') {
                when {
                    // Sadece deploy edilecek bir job belirlendiyse ve deploy true ise çalış
                    expression { return env.TARGET_JOB != "" && config.deploy == true }
                }
                steps {
                    script {
                        echo "📤 Upload Hedefi: ${env.NEXUS_REPO}"
                        
                        nexusArtifactUploader(
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            nexusUrl: '194.99.74.2:8081',
                            groupId: 'com.nabusoft',
                            version: "1.0.${env.BUILD_NUMBER}",
                            repository: env.NEXUS_REPO, // Dinamik Repo Değişkeni
                            credentialsId: env.NEXUS_CRED_ID,
                            artifacts: [
                                [artifactId: config.projectName, classifier: '', file: env.FINAL_ARTIFACT_NAME, type: 'zip']
                            ]
                        )

                        echo "🚀 Tetikleniyor: ${env.TARGET_JOB}"
                        
                        build job: env.TARGET_JOB, parameters: [
                            string(name: 'VERSION', value: "1.0.${env.BUILD_NUMBER}"),
                            string(name: 'ARTIFACT_NAME', value: env.FINAL_ARTIFACT_NAME)
                        ], wait: false
                    }
                }
            }
        }
    }
}