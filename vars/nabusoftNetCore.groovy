def call(Map config) {
    pipeline {
        agent { label 'built-in' } // Build sunucusu (DevOps)
        
        environment {
            // --- JENKINS AYARLARI ---
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates-maven'
            
            // --- SUNUCU ADRESLERİ ---
            SONAR_HOST_URL = "http://194.99.74.2:9000"
            NEXUS_HOST_URL = "http://194.99.74.2:8081"
            
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
            
            // Araç Yolları
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
        }

        stages {
            stage('Kaynak Kod') {
                steps {
                    git branch: "${env.BRANCH_NAME}", credentialsId: 'github-login', url: config.gitUrl
                }
            }

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

            stage('Build & Publish') {
                steps {
                    script {
                        bat "dotnet restore ${config.solutionPath}"
                        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
                        
                        withSonarQubeEnv(env.SONAR_SERVER) {
                             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                  bat "${env.SCANNER_TOOL} end /d:sonar.token=\"%SONAR_TOKEN%\""
                             }
                        }
                        
                        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                    }
                }
            }

            // *** ORTAM KARARI AŞAMASI ***
            stage('Paketleme ve Ortam Kararı') {
                steps {
                    script {
                        env.ENV_TAG = ""
                        env.TARGET_JOB = "" 

                        // 1. MEVCUT PROJELER (Shell.OneHub.UI vb.)
                        // NishCMS DIŞINDAKİ tüm projeler için genel kurallar
                        if (config.projectName != 'NishCMS.BackOffice') {
                            if (env.CURRENT_BRANCH == 'test' || env.CURRENT_BRANCH == 'test1') {
                                echo "Ortam Tespit Edildi: TEST (Genel)"
                                env.ENV_TAG = "test"
                                env.TARGET_JOB = "Deploy-to-TEST"
                            } 
                            else if (env.CURRENT_BRANCH == 'uat-staging' || env.CURRENT_BRANCH == 'uat-staging1') {
                                echo "Ortam Tespit Edildi: STAGING (Genel)"
                                env.ENV_TAG = "staging"
                                env.TARGET_JOB = "Deploy-to-STAGING"
                            }
                            else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                                echo "Ortam Tespit Edildi: PRODUCTION (Genel)"
                                env.ENV_TAG = "prod"
                                env.TARGET_JOB = "PROD-DEPLOY-MANUEL" 
                            }
                            else {
                                env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                            }
                        }
                        
                        // 2. ÖZEL PROJE: NishCMS BackOffice
                        else if (config.projectName == 'NishCMS.BackOffice') {
                             
                             // A) TEST ORTAMI (Nabusoft Sunucusu)
                             if (env.CURRENT_BRANCH == 'test') {
                                 echo "✅ NishCMS BackOffice -> TEST Ortamına (Nabusoft) Gidiyor"
                                 env.ENV_TAG = "test"
                                 env.TARGET_JOB = "Deploy-to-Nabusoft-TEST" // Nabusoft Job Adı
                             }
                             
                             // B) PRODUCTION ORTAMI (ISTS201 Sunucusu veya Özel Prod)
                             else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                                 echo "✅ NishCMS BackOffice -> PROD Ortamına Gidiyor"
                                 env.ENV_TAG = "prod"
                                 
                                 // Eğer bu proje için özel bir Prod job'ı varsa onun adını yaz.
                                 // Yoksa ve aynı sunucuya (ISTS201) gidecekse genel job'ı kullanabilirsin.
                                 // Örn: "NishCMS-PROD-DEPLOY" adında yeni bir job oluşturabilirsin.
                                 env.TARGET_JOB = "Deploy-to-Nabusoft-PROD" 
                             }
                             
                             else {
                                 env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                             }
                        }

                        // --- Ortak Paketleme ---
                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
                        
                        if (fileExists(env.ZIP_TOOL)) {
                             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
                        } else {
                             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
                        }
                        
                        env.FINAL_ARTIFACT_NAME = zipName
                    }
                }
            }

            stage('🚀 Nexus Upload & Deploy Tetikleme') {
                when {
                    expression { return env.TARGET_JOB != "" && config.deploy == true }
                }
                steps {
                    script {
                        nexusArtifactUploader(
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            nexusUrl: '194.99.74.2:8081',
                            groupId: 'com.nabusoft',
                            version: "1.0.${env.BUILD_NUMBER}",
                            repository: env.NEXUS_REPO,
                            credentialsId: env.NEXUS_CRED_ID,
                            artifacts: [
                                [artifactId: config.projectName, classifier: '', file: env.FINAL_ARTIFACT_NAME, type: 'zip']
                            ]
                        )

                        // Hedef Job'ı Tetikle
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