#!/usr/bin/groovy

////
// This pipeline requires the following plugins:
// Kubernetes Plugin 0.10
////

String ocpApiServer = env.OCP_API_SERVER ? "${env.OCP_API_SERVER}" : "https://openshift.default.svc.cluster.local"
def namespace = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
def token = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
def ocCmd = "oc --token=${token} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${namespace}"
def appName = "${env.JOB_NAME}".replace(/-\?pipeline-\?/, '').replace("/-\?${namespace}-\?/", '')

}

node('maven') {
//  def artifactory = Artifactory.server(env.ARTIFACTORY_SERVER)
  // def artifactoryMaven = Artifactory.newMavenBuild()
  // def buildInfo = Artifactory.newBuildInfo()
  // def scannerHome = tool env.SONARQUBE_TOOL
  def mvnHome = "/usr/share/maven/"
  def mvnCmd = "${mvnHome}bin/mvn"
  String pomFileLocation = env.BUILD_CONTEXT_DIR ? "${env.BUILD_CONTEXT_DIR}/pom.xml" : "pom.xml"

  stage('SCM Checkout') {
    checkout scm
  }

  stage('Build') {

    // artifactoryMaven.tool = env.MAVEN_TOOL
    // artifactoryMaven.deployer releaseRepo: env.ARTIFACTORY_DEPLOY_RELEASE_REPO, snapshotRepo: env.ARTIFACTORY_DEPLOY_SNAPSHOT_REPO, server: artifactory
    // artifactoryMaven.resolver releaseRepo: env.ARTIFACTORY_RESOLVE_RELEASE_REPO, snapshotRepo:env.ARTIFACTORY_RESOLVE_SNAPSHOT_REPO, server: artifactory
    // buildInfo.env.capture = true
    // buildInfo.retention maxBuilds: 10, maxDays: 7, deleteBuildArtifacts: true
    //
    // artifactoryMaven.run pom: pomFileLocation , goals: 'clean install', buildInfo: buildInfo
    // artifactory.publishBuildInfo buildInfo
    sh "${mvnCmd} clean install -DskipTests=true -f ${pomFileLocation}"

  }

  // stage('SonarQube scan') {
  //   withSonarQubeEnv {
  //       artifactoryMaven.run pom: pomFileLocation, goals: 'org.sonarsource.scanner.maven:sonar-maven-plugin:3.2:sonar'
  //   }
  // }
//             cp -rfv "${env.BUILD_CONTEXT_DIR}/target/*.\$t" oc-build/deployments/ 2> /dev/null || echo "No \$t files"


  stage('Build Image') {

    sh """
       set -e
       set -x

       echo "Environment Variables:"
       env

       echo "Current Directory:"
       pwd

       rm -rf oc-build && mkdir -p oc-build/deployments

       echo "Directory Contents Before:"
       find . -maxdepth 2

       for t in \$(echo "jar;war;ear" | tr ";" "\\n"); do
         cp -rfv ./target/*.\$t oc-build/deployments/ 2> /dev/null || echo "No \$t files"
       done

       echo "Directory Contents After:"
       find . -maxdepth 2

       set +e

       for i in oc-build/deployments/*.war; do
          mv -v oc-build/deployments/\$(basename \$i) oc-build/deployments/ROOT.war
          break
       done

       app_name=\$(echo "${env.JOB_NAME}" | sed -e "s/-\\?pipeline-\\?//" | sed -e "s/-\\?${namespace}-\\?//")

       ${ocCmd} start-build \${app_name} --from-dir=oc-build --wait=true --follow=true || exit 1
       set +x
    """

  }

  input "Promote Application to Stage?"

  stage('Promote To Stage') {
    sh """
    app_name=\$(echo "${env.JOB_NAME}" | sed -e "s/-\\?pipeline-\\?//" | sed -e "s/-\\?${namespace}-\\?//")

    ${ocCmd} tag ${namespace}/\${app_name}:latest ${namespace}-stage/\${app_name}:latest
    """
  }

}

input "Promote Application to Prod?"

String slaveImage = sh"${ocCmd} get is ${appName} --template='{{ .status.dockerImageRepository }}'"

podTemplate(label: 'jenkins-slave-image-mgmt', containers: [
  containerTemplate(name: 'jenkins-slave-image-mgmt', image: "${slaveImage}")
]) {

  node('jenkins-slave-image-mgmt') {

    stage('Promote To Prod') {
      sh """
      app_name=\$(echo "${env.JOB_NAME}" | sed -e "s/-\\?pipeline-\\?//" | sed -e "s/-\\?${namespace}-\\?//")

      set +x
      imageRegistry=\$(${ocCmd} get is \${app_name} --template='{{ .status.dockerImageRepository }} -n \${app_name}-stage' | cut -d/ -f1)

      strippedNamespace=\$(echo ${namespace} | cut -d/ -f1)

      echo "Promoting \${imageRegistry}/\${strippedNamespace}-stage/\${app_name} -> \${imageRegistry}/\${strippedNamespace}-prod/\${app_name}"
      skopeo --tls-verify=false copy --src-creds openshift:${token} --dest-creds openshift:${token} docker://\${imageRegistry}/${namespace}/\${app_name} docker://\${imageRegistry}/\${strippedNamespace}-prod/\${app_name}
      """
    }
  }
}
