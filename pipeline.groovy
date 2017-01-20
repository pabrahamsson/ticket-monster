#!/usr/bin/groovy

////
// This pipeline requires the following plugins:
// EnvInject: https://wiki.jenkins-ci.org/display/JENKINS/EnvInject+Plugin
// Pipeline Maven Plugin: https://wiki.jenkins-ci.org/display/JENKINS/Pipeline+Maven+Plugin
////

String ocpApiServer = env.OCP_API_SERVER ? "${env.OCP_API_SERVER}" : "https://openshift.default.svc.cluster.local"

node('maven') {
//  def artifactory = Artifactory.server(env.ARTIFACTORY_SERVER)
  // def artifactoryMaven = Artifactory.newMavenBuild()
  // def buildInfo = Artifactory.newBuildInfo()
  // def scannerHome = tool env.SONARQUBE_TOOL
  def mvnHome = "/usr/share/maven/"
  def mvnCmd = "${mvnHome}/bin/mvn"
  def namespace = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
  def token = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
  def ocCmd = "oc --token=${token} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${namespace}"
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
          if [[ "${env.BUILD_CONTEXT_DIR}" ]] && [[ "${env.BUILD_CONTEXT_DIR}" != null ]]; then
            cp -rfv ${env.BUILD_CONTEXT_DIR}/target/*.\$t oc-build/deployments/ 2> /dev/null || echo "No \$t files"
          else
            cp -rfv ./target/*.\$t oc-build/deployments/ 2> /dev/null || echo "No \$t files"
          fi
       done

       echo "Directory Contents After:"
       find . -maxdepth 2

       set +e

       for i in oc-build/deployments/*.war; do
          mv -v oc-build/deployments/\$(basename \$i) oc-build/deployments/ROOT.war
          break
       done

       ${ocCmd} new-build --name=${env.APP_NAME}-dev --image-stream=${env.BUILD_IMAGESTREAM} --binary=true --labels=app=${env.APP_NAME} || true

       ${ocCmd} start-build ${env.APP_NAME}-dev --from-dir=oc-build --wait=true --follow=true
       set +x
    """

  }

}

input "Promote Application?"

node('jenkins-slave-image-mgmt') {

  def namespace = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
  def token = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
  def ocCmd = "oc --token=${token} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${namespace}"

  stage('Promote Application') {
    sh """
    set +x
    imageRegistry=\$(${ocCmd} get is ${env.APP_NAME}-dev --template='{{ .status.dockerImageRepository }}' | cut -d/ -f1)

    strippedNamespace=\$(echo ${namespace} | cut -d/ -f1)

    echo "Promoting \${imageRegistry}/${namespace}/${env.APP_NAME} -> \${imageRegistry}/\${strippedNamespace}-prod/${env.APP_NAME}"
    skopeo --tls-verify=false copy --src-creds openshift:${token} --dest-creds openshift:${token} docker://\${imageRegistry}/${namespace}/${env.APP_NAME} docker://\${imageRegistry}/\${strippedNamespace}-prod/${env.APP_NAME}
    """
  }

}
