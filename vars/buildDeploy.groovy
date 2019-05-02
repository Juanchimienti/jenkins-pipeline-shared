#!groovy

def loginGcloud( Map config ){
  sh 'echo ${GCLOUD_KEY_FILE} > /tmp/jenkins.json'
  sh "gcloud config set account ${config.gcloud_email}"
  sh "gcloud auth activate-service-account --key-file=/tmp/jenkins.json --project=${config.cloud_project}"
  sh "yes | gcloud auth configure-docker"
}
def cloneRepo( Map config ){
  checkout([$class: 'GitSCM',
            branches: [[name: "*/${config.branch}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[ $class: 'SubmoduleOption',
                          disableSubmodules: false,
                          parentCredentials: true,
                          recursiveSubmodules: true,
                          reference: '',
                          trackingSubmodules: false]],
            submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: "${config.repo_credentials}",
                                 url: "${config.repo_url}"]]]
  )
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

def buildImage( Map config ) {
  withCredentials([sshUserPrivateKey(credentialsId: "${config.repo_credentials}", keyFileVariable: 'SSH_PRIVATE_KEY' )]) {
    BUILD_ARGS=''
    for ( e in config.build_env ) {
      BUILD_ARGS="${BUILD_ARGS} --build-arg ${e.key}=${e.value}"
    }
    sh 'KEY=`cat "$SSH_PRIVATE_KEY"` ;docker build -t ' + "${config.app}:${VERSION}" +' -f Dockerfile --build-arg "SSH_PRIVATE_KEY=${KEY}" ' + BUILD_ARGS + ' . '
  }
}

def tagPush( Map config ) {
  withCredentials([string(credentialsId: "${config.gcloud_credentials}", variable: 'GCLOUD_KEY_FILE' )]) {
    loginGcloud(config)
    sh "docker tag ${config.app}:${VERSION} ${config.registry}/${config.app}:latest"
    sh "docker tag ${config.app}:${VERSION} ${config.registry}/${config.app}:${VERSION}"
    sh "docker push ${config.registry}/${config.app}:latest"
    sh "docker push ${config.registry}/${config.app}:${VERSION}"
  }
}

def deploy( Map config ){
  script {
    if ( config.namespace == '' ){
      NAMESPACE = 'default'
      RELEASE_NAME = config.release_name
    } else {
      RELEASE_NAME = "${config.release_name}-${config.namespace}"
    }
    if ( config.yaml_path ) {
      YAML_PATH=config.yaml_path
    } else {
      YAML_PATH=config.app
    }
    if ( config.chart ) {
      CHART=config.chart
    } else {
      CHART=config.app
    }
    if ( config.force_values_yaml ) {
      VALUES_YAML = config.force_values_yaml
    } else {
      VALUES_YAML = "values-${config.branch}.yaml"
    }
    if ( config.encripted ){
      withCredentials([string(credentialsId: "${config.gcloud_credentials}", variable: 'GCLOUD_KEY_FILE' )]) {
        loginGcloud(config)
        sh "export GOOGLE_APPLICATION_CREDENTIALS='/tmp/jenkins.json' ; sops --encrypted-suffix _SOPS_ENCRIPTED -d ${YAML_PATH}/enc_${VALUES_YAML} |sed 's/_SOPS_ENCRIPTED//g' >  ${YAML_PATH}/${VALUES_YAML}"
      }
    }
    ARGS=""
    if ( config.build_image ) {
      ARGS = ARGS.concat("--set-string image.tag=${VERSION},image.repository=${config.registry}/${config.app} ")
    }
    if ( config.chart_version ) {
      ARGS = ARGS.concat("--version ${config.chart_version} ")
    }
  }
  sh "cd ${YAML_PATH};helm init --client-only; if [ -f requirements.yaml ] ; then helm dep update; fi; cd -"
  sh "helm upgrade ${RELEASE_NAME} ${CHART} --namespace ${NAMESPACE} -i -f ${YAML_PATH}/${VALUES_YAML} ${ARGS}"
}


def call( Map config ) {
  def label = "worker-${UUID.randomUUID().toString()}"
  podTemplate(
    label: label,
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
                cloneRepo(config)
              }
              slackSend(color:"#4ab737",message: getChangelogString())

              if ( config.build_image ) {
                stage('Build images') {
                    buildImage(config)
                  }

                stage('Tag/push Images') {
                  tagPush(config)
                }
              }

              stage('Deploy') {
                deploy(config)
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
