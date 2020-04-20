#!groovy
package libs

def loginGcloud( Map config ){
  sh 'echo ${GCLOUD_KEY_FILE} > /tmp/jenkins.json'
  sh "gcloud config set account ${config.gcloud_email}"
  sh "gcloud auth activate-service-account --key-file=/tmp/jenkins.json --project=${config.cloud_project}"
  sh "yes | gcloud auth configure-docker"
}

def cloneRepo( Map config ){
   script {
    if (config.tag != '') {
      CHECKOUT_POINT = "tags/" + config.tag
    } else {
      CHECKOUT_POINT = "*/" + config.branch
    }
  }
  checkout([$class: 'GitSCM',
            branches: [[name: "${CHECKOUT_POINT}"]],
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
      VERSION = config.tag
    } else {
      VERSION = sh(script: "git rev-parse --short HEAD|tr -d '\n'", returnStdout: true)
    }
    if ( config.force_version && config.force_version != '') {
      VERSION = config.force_version
    }
  }
}

def buildImage( Map config ) {
  withCredentials([sshUserPrivateKey(credentialsId: "${config.repo_credentials}", keyFileVariable: 'SSH_PRIVATE_KEY' )]) {
    BUILD_ARGS=''
    for ( e in config.build_env ) {
      BUILD_ARGS="${BUILD_ARGS} --build-arg ${e.key}=${e.value}"
    }
    if ( config.Dockerfile ) {
      DOCKERFILE=config.Dockerfile
    } else {
      DOCKERFILE="Dockerfile"
    }
    if ( config.docker_build_path ) {
      DOCKER_BUILD_PATH=config.docker_build_path
    } else {
      DOCKER_BUILD_PATH="."
    }
    sh 'KEY=`cat "$SSH_PRIVATE_KEY"` ;docker build -t ' + "${config.app}:${VERSION} -f ${DOCKERFILE}" + ' --build-arg "SSH_PRIVATE_KEY=${KEY}" ' + BUILD_ARGS + ' ' + DOCKER_BUILD_PATH
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
        sh "export GOOGLE_APPLICATION_CREDENTIALS='/tmp/jenkins.json'; sops --encrypted-suffix _SOPS_ENCRIPTED -d ${YAML_PATH}/enc_${VALUES_YAML} |sed 's/_SOPS_ENCRIPTED//g' >  ${YAML_PATH}/${VALUES_YAML}"
      }
    }
    // Use wait so helm waits the deploy to be applied in the cluster
    COMMON_ARGS=""
    UPGRADE_ARGS="--wait "
    if ( config.build_image || ( config.force_version && config.force_version != '') ) {
      COMMON_ARGS = COMMON_ARGS.concat("--set-string image.tag=${VERSION},image.repository=${config.registry}/${config.app} ")
    }
    if ( config.chart_version ) {
      COMMON_ARGS = COMMON_ARGS.concat("--version ${config.chart_version} ")
    }
  }
  sh "cd ${YAML_PATH}; helm init --client-only; if [ -f requirements.yaml ] ; then helm dep update; fi; cd -"
  sh "helm diff upgrade ${RELEASE_NAME} ${CHART} --allow-unreleased --namespace ${NAMESPACE} -f ${YAML_PATH}/${VALUES_YAML} ${COMMON_ARGS}"
  sh "helm upgrade ${RELEASE_NAME} ${CHART} --namespace ${NAMESPACE} -i -f ${YAML_PATH}/${VALUES_YAML} ${COMMON_ARGS} ${UPGRADE_ARGS}"
}

return this
