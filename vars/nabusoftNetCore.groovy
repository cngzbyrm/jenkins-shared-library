def call(Map config) {
    pipeline {
        agent any
        
        environment {
            // Jenkins Ayarları
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates' // Raw repo adın
            
            // Branch ismini alıyoruz (Jenkins otomatik verir)
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
        }

        stages {
            stage('📥 Kaynak Kod (Checkout)') {
                steps {
                    // Git'ten çek
                    git branch: config.branchName ?: 'main', url: config.gitUrl
                }
            }

            stage('🔍 SonarQube Analizi') {
                steps {
                    script {
                        // YAML'daki "SonarQube Begin" adımı
                        withSonarQubeEnv(env.SONAR_SERVER) {
                            withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                bat "dotnet sonarscanner begin /k:\"${config.sonarProjectKey}\" /d:sonar.login=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://localhost:9000\""
                            }
                        }
                    }
                }
            }

            stage('🔨 Build & Publish') {
                steps {
                    script {
                        // YAML'daki Restore, Build, Publish adımları
                        bat "dotnet restore ${config.solutionPath}"
                        bat "dotnet build ${config.solutionPath} -c Release --no-restore"
                        
                        // Sonar Bitiş
                        withSonarQubeEnv(env.SONAR_SERVER) {
                             withCredentials([string(credentialsId: env.SONAR_TOKEN_ID, variable: 'SONAR_TOKEN')]) {
                                 bat "dotnet sonarscanner end /d:sonar.login=\"%SONAR_TOKEN%\""
                             }
                        }

                        // Publish
                        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                    }
                }
            }

            stage('📦 Paketleme ve İsimlendirme') {
                steps {
                    script {
                        // --- İŞTE SENİN YAML MANTIĞIN BURADA ---
                        def envTag = ""
                        
                        if (env.CURRENT_BRANCH == 'test') {
                            envTag = "test"
                        } else if (env.CURRENT_BRANCH == 'uat-staging') {
                            envTag = "staging"
                        } else if (env.CURRENT_BRANCH == 'production') {
                            envTag = "prod"
                        } else {
                            // Branch ismi standart dışıysa (örn: feature/login) varsayılan:
                            envTag = "dev-${env.BUILD_NUMBER}"
                        }

                        // Dosya Adı: app-test-v1.0.55.zip
                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${envTag}-v${version}.zip"
                        
                        // Zip Oluştur
                        powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
                        
                        // Değişkeni global yapalım ki sonraki adımda kullanalım
                        env.FINAL_ARTIFACT_NAME = zipName
                    }
                }
            }

            stage('🚀 Nexus Upload & Deploy') {
                // Sadece belirlediğimiz branchlerde çalışsın (YAML'daki 'if' mantığı)
                when {
                    expression {
                        return ['test', 'uat-staging', 'production'].contains(env.BRANCH_NAME)
                    }
                }
                steps {
                    script {
                        // 1. Nexus'a Yükle
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

                        // 2. İlgili Deploy Job'ını Tetikle
                        def targetJob = ""
                        if (env.CURRENT_BRANCH == 'test') {
                            targetJob = "Deploy-to-TEST"
                        } else if (env.CURRENT_BRANCH == 'uat-staging') {
                            targetJob = "Deploy-to-STAGING"
                        } else if (env.CURRENT_BRANCH == 'production') {
                            targetJob = "Deploy-to-PROD"
                        }

                        echo "Tetiklenen Job: ${targetJob}"
                        
                        // Jenkins'in kendi "build" komutu (CURL kullanmaya gerek yok, daha güvenli)
                        build job: targetJob, parameters: [
                            string(name: 'VERSION', value: "1.0.${env.BUILD_NUMBER}"),
                            string(name: 'ARTIFACT_NAME', value: env.FINAL_ARTIFACT_NAME)
                        ], wait: false // Deploy bitmesini bekleme, tetikle ve çık
                    }
                }
            }
        }
    }
}