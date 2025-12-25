def call(Map config) {
    pipeline {
        // Build işlemi müsait olan herhangi bir ajanda (Genelde ISTS201) çalışsın
        agent any 
        
        environment {
            // --- JENKINS AYARLARI ---
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates-maven'
            
            // --- YENİ SUNUCU ADRESLERİ (Dış IP) ---
            // Ajan sunucular localhost'u göremez, o yüzden ana sunucu IP'sini veriyoruz
            SONAR_HOST_URL = "http://194.99.74.2:9000"
            NEXUS_HOST_URL = "http://194.99.74.2:8081"
            
            // Mevcut Branch Adı
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
            
            // Araç Yolları 
            // NOT: ISTS201 sunucusunda bu yolların dolu olduğundan emin ol!
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
        }

        stages {
            stage('📥 Kaynak Kod') {
                steps {
                    // GitHub Token ID'sini buraya yazdım, Jenkins'te oluşturduğun ID bu olmalı
                    git branch: "${env.BRANCH_NAME}", credentialsId: 'github-login', url: config.gitUrl
                }
            }

            stage('🔍 SonarQube Analizi') {
                steps {
                    script {
                        withSonarQubeEnv(env.SONAR_SERVER) {
                            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                // GÜNCELLEME: sonar.host.url artık 194.99.74.2 adresine bakıyor
                                bat "${env.SCANNER_TOOL} begin /k:\"${config.sonarProjectKey}\" /d:sonar.token=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"${env.SONAR_HOST_URL}\" /d:sonar.exclusions=\"**/wwwroot/lib/**,**/wwwroot/assets/**,**/node_modules/**,**/*.min.css,**/*.min.js,**/*.xml,**/*.json,**/*.png,**/*.jpg\""
                            }
                        }
                    }
                }
            }

            stage('🔨 Build & Publish') {
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

            stage('📦 Paketleme (Artifact)') {
                steps {
                    script {
                        env.ENV_TAG = ""
                        env.TARGET_JOB = "" 

                        if (env.CURRENT_BRANCH == 'test' || env.CURRENT_BRANCH == 'test1') {
                            echo "✅ Ortam Tespit Edildi: TEST"
                            env.ENV_TAG = "test"
                            env.TARGET_JOB = "Deploy-to-TEST"
                        } 
                        else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'main') {
                            echo "✅ Ortam Tespit Edildi: PRODUCTION"
                            env.ENV_TAG = "prod"
                            env.TARGET_JOB = "Deploy-to-PROD"
                        } 
                        else {
                            echo "ℹ️ Geliştirme Branch'i: Sadece Build yapılacak."
                            env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                        }

                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
                        
                        if (fileExists(env.ZIP_TOOL)) {
                             echo "🚀 7-Zip bulundu, hızlı sıkıştırma yapılıyor..."
                             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
                        } else {
                             echo "⚠️ 7-Zip bulunamadı! Yavaş PowerShell sıkıştırması kullanılıyor..."
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
                        echo "🎯 Hedef Job: ${env.TARGET_JOB}"
                        
                        nexusArtifactUploader(
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            nexusUrl: '194.99.74.2:8081', // KRİTİK GÜNCELLEME: Dış IP
                            groupId: 'com.nabusoft',
                            version: "1.0.${env.BUILD_NUMBER}",
                            repository: env.NEXUS_REPO,
                            credentialsId: env.NEXUS_CRED_ID,
                            artifacts: [
                                [artifactId: config.projectName, classifier: '', file: env.FINAL_ARTIFACT_NAME, type: 'zip']
                            ]
                        )

                        // Deploy Job'ını tetikle (wait: false = bitmesini bekleme, hemen bitir)
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