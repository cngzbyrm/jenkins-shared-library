def call(Map config) {
    pipeline {
        agent any
        
        environment {
            // Jenkins Ayarları
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates-maven'
            
            // Mevcut Branch Adı
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
            
            // Araç Yolları
            SCANNER_TOOL = "C:\\dotnet-tools\\dotnet-sonarscanner.exe"
            // 7-Zip Yolu (Kurduğun yer burası olmalı)
            ZIP_TOOL = "C:\\Program Files\\7-Zip\\7z.exe"
        }

        stages {
            stage('📥 Kaynak Kod') {
                steps {
                    git branch: "${env.BRANCH_NAME}", url: config.gitUrl
                }
            }

            stage('🔍 SonarQube Analizi') {
                steps {
                    script {
                        withSonarQubeEnv(env.SONAR_SERVER) {
                            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                // OPTİMİZASYON 1: Exclusions eklendi. (Lib, assets, node_modules taranmayacak)
                                bat "${env.SCANNER_TOOL} begin /k:\"${config.sonarProjectKey}\" /d:sonar.login=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://localhost:9000\" /d:sonar.exclusions=\"**/wwwroot/lib/**,**/wwwroot/assets/**,**/node_modules/**,**/*.min.css,**/*.min.js,**/*.xml,**/*.json,**/*.png,**/*.jpg\""
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
                                 bat "${env.SCANNER_TOOL} end /d:sonar.login=\"%SONAR_TOKEN%\""
                             }
                        }
                        
                        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                    }
                }
            }

            stage('📦 Karar Anı: Hangi Ortama Gidiyoruz?') {
                steps {
                    script {
                        env.ENV_TAG = ""
                        env.TARGET_JOB = "" 

                        if (env.CURRENT_BRANCH == 'test' || env.CURRENT_BRANCH == 'test1') {
                            echo "✅ Ortam Tespit Edildi: TEST"
                            env.ENV_TAG = "test"
                            env.TARGET_JOB = "Deploy-to-TEST"
                        } 
                        else if (env.CURRENT_BRANCH == 'uat-staging' || env.CURRENT_BRANCH == 'uat-staging1') {
                            echo "✅ Ortam Tespit Edildi: STAGING"
                            env.ENV_TAG = "staging"
                            env.TARGET_JOB = "Deploy-to-STAGING"
                        } 
                        else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                            echo "✅ Ortam Tespit Edildi: PRODUCTION"
                            env.ENV_TAG = "prod"
                            env.TARGET_JOB = "Deploy-to-PROD"
                        } 
                        else {
                            echo "ℹ️ Geliştirme Branch'i: Deploy yapılmayacak."
                            env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                        }

                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
                        
                        // OPTİMİZASYON 2: 7-Zip Kontrolü ve Kullanımı
                        if (fileExists(env.ZIP_TOOL)) {
                             echo "🚀 7-Zip bulundu, turbo modda sıkıştırma yapılıyor..."
                             // 7z komutu: 'a' (add), '-tzip' (zip formatı)
                             bat "\"${env.ZIP_TOOL}\" a -tzip ./${zipName} ./publish_output/*"
                        } else {
                             echo "⚠️ 7-Zip bulunamadı! Yavaş PowerShell sıkıştırması kullanılıyor..."
                             echo "Lütfen sunucuya C:\\Program Files\\7-Zip\\7z.exe kurun."
                             powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
                        }
                        
                        env.FINAL_ARTIFACT_NAME = zipName
                    }
                }
            }

            stage('🚀 Nexus & Deploy') {
                when {
                    expression { return env.TARGET_JOB != "" && config.deploy == true }
                }
                steps {
                    script {
                        echo "🎯 Hedef Job: ${env.TARGET_JOB} tetikleniyor..."
                        echo "📦 Dosya: ${env.FINAL_ARTIFACT_NAME}"

                        nexusArtifactUploader(
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            nexusUrl: 'localhost:8081',
                            groupId: 'com.nabusoft',
                            version: "1.0.${env.BUILD_NUMBER}",
                            repository: env.NEXUS_REPO,
                            credentialsId: env.NEXUS_CRED_ID,
                            artifacts: [
                                [artifactId: config.projectName, classifier: '', file: env.FINAL_ARTIFACT_NAME, type: 'zip']
                            ]
                        )

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