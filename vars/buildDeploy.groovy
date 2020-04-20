#!groovy

def call( Map config ) {
  def c = new libs.common()
  def label = "worker-${UUID.randomUUID().toString()}"
  podTemplate(
    label: label,
    containers: [
      containerTemplate(name: 'builder', image: 'juanchimienti/jenkins-slave-builder:v0.8.1', command: 'cat', ttyEnabled: true),
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
                c.cloneRepo(config)
              }
              slackSend(color:"#4ab737",message: getChangelogString())

              if ( config.build_image ) {
                stage('Build images') {
                  c.buildImage(config)
                }

                stage('Tag/push Images') {
                  c.tagPush(config)
                }
              }

              stage('Deploy') {
                c.deploy(config)
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
