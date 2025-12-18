// vars/nabusoftNetCore.groovy
def call(Map config) {
    pipeline {
        agent any
        
        // Jenkins'te tanımlı tool isimleri (Gerekirse kendine göre düzenle)
        environment {
            // Nexus kimlik bilgisi ID'si (Jenkins Credentials'da bu ID ile kayıtlı olmalı)
            NEXUS_CRED_ID = 'nexus-admin-credentials' 
            NEXUS_URL = 'http://localhost:8081'
            NEXUS_REPO = 'nexus-candidates' // Senin raw repon
        }

        stages {
            stage('📥 Kaynak Kodu Çek (Checkout)') {
                steps {
                    // Git'ten kodu çeker
                    git branch: 'main', url: config.gitUrl
                }
            }

            stage('restore & Build') {
                steps {
                    script {
                        echo "🔨 ${config.projectName} Projesi Derleniyor..."
                        // .NET Core Restore ve Build
                        sh "dotnet restore ${config.solutionPath}"
                        sh "dotnet build ${config.solutionPath} --configuration Release --no-restore"
                    }
                }
            }

            stage('🧪 Test') {
                steps {
                    script {
                        // Eğer test atlamak istersen config'den parametre geçebilirsin
                        sh "dotnet test ${config.solutionPath} --no-build --verbosity normal"
                    }
                }
            }

            stage('📦 Paketle (Zip)') {
                steps {
                    script {
                        // Publish alıp zipleyeceğiz
                        sh "dotnet publish ${config.solutionPath} -c Release -o ./publish_output"
                        
                        // Versiyonlama (Jenkins Build Numarasını kullanıyoruz)
                        def version = "1.0.${BUILD_NUMBER}"
                        def zipName = "${config.projectName}-${version}.zip"
                        
                        // Zip oluştur (Linux/Mac ortamı için zip komutu)
                        // Windows kullanıyorsan PowerShell komutu gerekir
                        sh "cd publish_output && zip -r ../${zipName} ."
                        
                        // Zip ismini daha sonra kullanmak için env'e atalım
                        env.ARTIFACT_NAME = zipName
                    }
                }
            }

            stage('🚀 Nexus\'a Yükle') {
                steps {
                    script {
                        echo "📤 Nexus'a yükleniyor: ${env.ARTIFACT_NAME}"
                        
                        // Nexus Artifact Uploader Plugin Kullanımı
                        nexusArtifactUploader(
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            nexusUrl: 'localhost:8081', // Docker içindeysen container name veya IP
                            groupId: 'com.nabusoft',
                            version: "1.0.${BUILD_NUMBER}",
                            repository: env.NEXUS_REPO,
                            credentialsId: env.NEXUS_CRED_ID,
                            artifacts: [
                                [artifactId: config.projectName, classifier: '', file: env.ARTIFACT_NAME, type: 'zip']
                            ]
                        )
                    }
                }
            }
        }

        post {
            always {
                cleanWs() // İş bitince temizlik
                echo "🏁 İşlem Tamamlandı!"
            }
            success {
                echo "✅ Başarıyla Nexus'a atıldı."
            }
            failure {
                echo "❌ Bir şeyler ters gitti."
            }
        }
    }
}