#!groovy

def call(Map config) {
  def c = new libs.common()
  def label = "worker-${UUID.randomUUID().toString()}"
  podTemplate(
    label: label,
    containers: [
      containerTemplate(
        name: 'builder',
        image: config.build_image,
        ttyEnabled: true),
    ]
  ) {
    node(label) {
      try {
        slackSend(
          color: "#a7b736",
          message: "Starting Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]': Check console output at ${env.BUILD_URL}console")

          container('builder') {
            deleteDir()
            dir ("${config.app}") {
              stage('Cloning repos for run') {
                c.cloneRepo(config)
              }

              stage('Build for run'){
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
}
