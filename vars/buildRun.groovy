#!groovy

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

def call(Map config) {
  def label = "worker-${UUID.randomUUID().toString()}"
  podTemplate(
    containers: [
      containerTemplate(
        name: 'builder',
        image: config.build_image,
        ttyEnabled: true),
    ]
  ) {
    node(POD_LABEL) {
      try {
        slackSend(
          color: "#a7b736",
          message: "Starting Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]': Check console output at ${env.BUILD_URL}console")

          container('builder') {
            deleteDir()
            dir ("${config.app}") {
              stage('Cloning repos') {
                cloneRepo(config)
              }

              stage('Build'){ 
                sh "${config.build_cmd}"
              }

              stage('Run') {
                try {
                  sh "${config.run_cmd}"
                } finally {
                  if (config.run_junit) {
                    junit '**/*.xml'
                  }
                }
              }
            }
          }
      } catch(error) {
        slackSend(
          color: "#b73636",
          message: "Failed Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':" + error.toString(),
        )
      } finally {
        slackSend(
          color:"#4ab737",
          message: "Finished Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]': ${currentBuild.result?:'SUCCESS'}",
        )
      }
    }
}
