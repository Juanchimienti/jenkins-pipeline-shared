#!groovy

def loginGcloud( Map config ){
    sh 'echo ${GCLOUD_KEY_FILE} > /tmp/jenkins.json'
    sh "gcloud config set account ${config.gcloud_email}"
    sh "gcloud auth activate-service-account --key-file=/tmp/jenkins.json --project=${config.cloud_project}"
    sh "yes | gcloud auth configure-docker"
}

def call( Map config ) {
    def label = "worker-${UUID.randomUUID().toString()}"
    podTemplate(label: label,
      containers: [
        containerTemplate(name: 'builder', image: 'juanchimienti/jenkins-slave-builder:v0.6', command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'docker' , image: 'docker:18.09-dind', privileged: true),
      ]
    ) {
        node(label) {
            try {
                slackSend(color:"#a7b736",message:"""Starting Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]': Check console output at ${env.BUILD_URL}console""")
                VERSION=''

                withEnv(['HELM_HOST=tiller-deploy.kube-system:44134','DOCKER_HOST=tcp://localhost:2375']){
                    container('builder') {
                        deleteDir()
                        dir ("${config.app}") {
                            stage('Cloning repos') {
                                script {
                                   if (config.tag != '') {
                                       git credentialsId: "${config.repo_credentials}", url: "${config.repo_url}", tag: "${config.tag}"
                                       VERSION = config.tag
                                   } else {
                                       git credentialsId: "${config.repo_credentials}", url: "${config.repo_url}", branch: "${config.branch}"
                                       VERSION = sh(script: "git rev-parse --short HEAD|tr -d '\n'", returnStdout: true)
                                   }
                                }
                            }

                            slackSend(color:"#4ab737",message: getChangelogString())

                            stage('Build images') {
                                withCredentials([sshUserPrivateKey(credentialsId: "${config.repo_credentials}", keyFileVariable: 'SSH_PRIVATE_KEY' )]) {
                                    BUILD_ARGS=''
                                    for ( e in config.build_env ) {
                                        BUILD_ARGS="${BUILD_ARGS} --build-arg ${e.key}=${e.value}"
                                    }
                                    sh 'KEY=`cat "$SSH_PRIVATE_KEY"` ;docker build -t ' + "${config.app}:${VERSION}" +' -f Dockerfile --build-arg "SSH_PRIVATE_KEY=${KEY}" ' + BUILD_ARGS + ' . '
                                }
                            }

                            stage('Tag/push Images') {
                                withCredentials([string(credentialsId: "${config.gcloud_credentials}", variable: 'GCLOUD_KEY_FILE' )]) {
                                    loginGcloud(config)
                                    sh "docker tag ${config.app}:${VERSION} ${config.registry}/${config.app}:latest"
                                    sh "docker tag ${config.app}:${VERSION} ${config.registry}/${config.app}:${VERSION}"
                                    sh "docker push ${config.registry}/${config.app}:latest"
                                    sh "docker push ${config.registry}/${config.app}:${VERSION}"
                                }
                            }

                            stage('Deploy') {
                                script {
                                    if ( config.namespace == '' ){
                                        NAMESPACE = 'default'
                                        RELEASE_NAME = config.release_name
                                    } else {
                                        RELEASE_NAME = "${config.release_name}-${config.namespace}"
                                    }
                                    if ( config.encripted == 'true' ){
                                        withCredentials([string(credentialsId: "${config.gcloud_credentials}", variable: 'GCLOUD_KEY_FILE' )]) {
                                            loginGcloud(config)
                                            sh "export GOOGLE_APPLICATION_CREDENTIALS='/tmp/jenkins.json' ; sops --encrypted-suffix _SOPS_ENCRIPTED -d ${config.app}/enc_values-${config.branch}.yaml|sed 's/_SOPS_ENCRIPTED//g' >  ${config.app}/values-${config.branch}.yaml"
                                        }
                                    }
                                }
                                sh "cd ${config.app};helm init --client-only; helm dep update; cd .."
                                sh "helm upgrade ${RELEASE_NAME}  ${config.app} --namespace ${NAMESPACE} -i -f ${config.app}/values-${config.branch}.yaml --set-string image.tag=${VERSION},image.repository=${config.registry}/${config.app}"
                            }
                        }
                    }
                }
            } catch(e) {
                slackSend(color:"#b73636",message:"""Failed Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':""" + e.toString())
                throw e
            } finally {
                slackSend(color:"#4ab737",message:"""Finished Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':""")
            }
        }
    }
}
