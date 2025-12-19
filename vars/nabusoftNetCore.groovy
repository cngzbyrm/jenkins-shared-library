def call(Map config) {
    pipeline {
        agent any
        
        environment {
            // Jenkins Ayarları
            SONAR_SERVER = 'SonarQube' 
            SONAR_TOKEN_ID = 'sonarqube-token'
            NEXUS_CRED_ID = 'nexus-admin-credentials'
            NEXUS_REPO = 'nexus-candidates' 
            
            // Mevcut Branch Adı
            CURRENT_BRANCH = "${env.BRANCH_NAME}"
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
                                bat "dotnet sonarscanner begin /k:\"${config.sonarProjectKey}\" /d:sonar.login=\"%SONAR_TOKEN%\" /d:sonar.host.url=\"http://localhost:9000\""
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
                                 bat "dotnet sonarscanner end /d:sonar.login=\"%SONAR_TOKEN%\""
                             }
                        }
                        
                        bat "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                    }
                }
            }

            stage('📦 Karar Anı: Hangi Ortama Gidiyoruz?') {
                steps {
                    script {
                        // Varsayılan değerler (Boş)
                        env.ENV_TAG = ""
                        env.TARGET_JOB = "" 

                        // ---------------------------------------------------------
                        // 1. TEST ORTAMI KONTROLÜ
                        // ---------------------------------------------------------
                        if (env.CURRENT_BRANCH == 'test' || env.CURRENT_BRANCH == 'test1') {
                            echo "✅ Ortam Tespit Edildi: TEST"
                            env.ENV_TAG = "test"
                            env.TARGET_JOB = "Deploy-to-TEST"
                        } 
                        // ---------------------------------------------------------
                        // 2. STAGING ORTAMI KONTROLÜ
                        // ---------------------------------------------------------
                        else if (env.CURRENT_BRANCH == 'uat-staging' || env.CURRENT_BRANCH == 'uat-staging1') {
                            echo "✅ Ortam Tespit Edildi: STAGING"
                            env.ENV_TAG = "staging"
                            env.TARGET_JOB = "Deploy-to-STAGING"
                        } 
                        // ---------------------------------------------------------
                        // 3. PRODUCTION ORTAMI KONTROLÜ
                        // ---------------------------------------------------------
                        else if (env.CURRENT_BRANCH == 'production' || env.CURRENT_BRANCH == 'production1') {
                            echo "✅ Ortam Tespit Edildi: PRODUCTION"
                            env.ENV_TAG = "prod"
                            env.TARGET_JOB = "Deploy-to-PROD"
                        } 
                        // ---------------------------------------------------------
                        // 4. DIGER GELISTRME BRANCHLERI
                        // ---------------------------------------------------------
                        else {
                            echo "ℹ️ Geliştirme Branch'i: Deploy yapılmayacak."
                            env.ENV_TAG = "dev-${env.BUILD_NUMBER}"
                        }

                        // Dosya adını oluştur
                        def version = "1.0.${env.BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${env.ENV_TAG}-v${version}.zip"
                        
                        // Ziple
                        powershell "Compress-Archive -Path ./publish_output/* -DestinationPath ./${zipName} -Force"
                        
                        // Sonraki aşamaya taşımak için global değişkene ata
                        env.FINAL_ARTIFACT_NAME = zipName
                    }
                }
            }

            stage('🚀 Nexus & Deploy') {
                // Sadece hedef job belirlenmişse çalış (Dev branchlerde çalışmaz)
                when {
                    expression { return env.TARGET_JOB != "" && config.deploy == true }
                }
                steps {
                    script {
                        echo "🎯 Hedef Job: ${env.TARGET_JOB} tetikleniyor..."
                        echo "📦 Dosya: ${env.FINAL_ARTIFACT_NAME}"

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

                        // 2. İlgili Job'ı Tetikle (DİNAMİK)
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