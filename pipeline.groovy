#!/usr/bin/groovy

////
// This pipeline requires the following plugins:
// Kubernetes Plugin 0.10
////

String ocpApiServer = env.OCP_API_SERVER ? "${env.OCP_API_SERVER}" : "https://openshift.default.svc.cluster.local"

node('master') {

  env.NAMESPACE = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
  env.TOKEN = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
  env.OC_CMD = "oc --token=${env.TOKEN} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${env.NAMESPACE}"

  env.APP_NAME = "${env.JOB_NAME}".replaceAll(/-?pipeline-?/, '').replaceAll(/-?${env.NAMESPACE}-?/, '')
  //def projectBase = "${env.NAMESPACE}".replaceAll(/-dev/, '')
  def projectBase = env.APP_NAME
  env.STAGE1 = "${projectBase}-dev"
  env.STAGE2 = "${projectBase}-stage"
  env.STAGE3 = "${projectBase}-prod"

//  sh(returnStdout: true, script: "${env.OC_CMD} get is jenkins-slave-image-mgmt --template=\'{{ .status.dockerImageRepository }}\' -n openshift > /tmp/jenkins-slave-image-mgmt.out")
//  env.SKOPEO_SLAVE_IMAGE = readFile('/tmp/jenkins-slave-image-mgmt.out').trim()
//  println "${env.SKOPEO_SLAVE_IMAGE}"

}

node('maven') {
//  def artifactory = Artifactory.server(env.ARTIFACTORY_SERVER)
  // def artifactoryMaven = Artifactory.newMavenBuild()
  // def buildInfo = Artifactory.newBuildInfo()
  // def scannerHome = tool env.SONARQUBE_TOOL
  def mvnHome = env.MAVEN_HOME ? "${env.MAVEN_HOME}" : "/usr/share/maven/"
  def mvnCmd = "mvn"
  String pomFileLocation = env.BUILD_CONTEXT_DIR ? "${env.BUILD_CONTEXT_DIR}/pom.xml" : "pom.xml"

  // Define vars for blue green deployment
  def active_color = sh(returnStdout: true, script: "oc get route ${env.APP_NAME} -n ${env.STAGE1} -o jsonpath='{ .spec.to.name }'").trim().replaceAll("${env.APP_NAME}-", '')
  def dest_color = ""
  if (active_color == "blue") {
    dest_color = "green"
  } else {
    dest_color = "blue"
  }

  stage('SCM Checkout') {
    checkout scm
    sh "orig=\$(pwd); cd \$(dirname ${pomFileLocation}); git describe --tags; cd \$orig"
  }

  stage('Build') {

    sh "${mvnCmd} clean install -DskipTests=true -f ${pomFileLocation}"

  }

  stage('Build Image') {

    sh """
       rm -rf oc-build && mkdir -p oc-build/deployments

       for t in \$(echo "jar;war;ear" | tr ";" "\\n"); do
         cp -rfv ./target/*.\$t oc-build/deployments/ 2> /dev/null || echo "No \$t files"
       done

       for i in oc-build/deployments/*.war; do
          mv -v oc-build/deployments/\$(basename \$i) oc-build/deployments/ROOT.war
          break
       done

       ${env.OC_CMD} start-build ${env.APP_NAME} --from-dir=oc-build --wait=true --follow=true || exit 1
    """
  }

  stage("Promote To ${env.STAGE1}") {
    sh """
    ${env.OC_CMD} tag ${env.NAMESPACE}/${env.APP_NAME}:latest ${env.STAGE1}/${env.APP_NAME}:latest
    """
  }

  stage("Deploy to ${env.STAGE1}") {
    // Get currently number of replicas of currently active dc or create new one
    dc = sh(returnStatus: true, script: "oc get dc/${env.APP_NAME}-${active_color}")
    if (dc != 0) {
      sh "oc process blue-green-deploymentconfig -p COLOR=${dest_color} -p NAMESPCE=${env.STAGE1}|oc apply -f -"
    } else {
      replicas = sh(returnStdout: true, script: "oc get dc/${env.APP_NAME}-${active_color} -o jsonpath='{ .spec.replicas}'")
      if (replicas > 0) {
        openshiftScale(depCfg: "${env.APP_NAME}-${dest_color}", namespace: "${env.STAGE1}", replicaCount: replicas, verifyReplicaCount: true)
        //sh "oc patch dc/${env.APP_NAME}-${dest_color} -p '{\"spec\":{\"replicas\":${replicas}}}'"
      } else {
        openshiftScale(depCfg: "${env.APP_NAME}-${dest_color}", namespace: "${env.STAGE1}", replicaCount: 1, verifyReplicaCount: true)
        //sh "oc patch dc/${env.APP_NAME}-${dest_color} -p '{\"spec\":{\"replicas\":${replicas}}}'"
      }
    }
  }
  stage("Verify Deployment to ${env.STAGE1}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${env.STAGE1}", verifyReplicaCount: true)

    input "Promote Application to Stage?"
  }

  stage("Promote To ${env.STAGE2}") {
    sh """
    ${env.OC_CMD} tag ${env.STAGE1}/${env.APP_NAME}:latest ${env.STAGE2}/${env.APP_NAME}:latest
    """
  }

  stage("Verify Deployment to ${env.STAGE2}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${env.STAGE2}", verifyReplicaCount: true)

    //input "Promote Application to Prod?"
  }

  stage("Promote To ${env.STAGE3}") {
    sh """
    ${env.OC_CMD} tag ${env.STAGE2}/${env.APP_NAME}:latest ${env.STAGE3}/${env.APP_NAME}:latest
    """
  }

  stage("Scale out Deployment in ${env.STAGE3}") {
    openshiftScale(deploymentConfig: "${env.APP_NAME}", replicaCount: 3, verifyReplicaCount: true, namespace: "${env.STAGE3}")
  }

  stage("Verify Deployment to ${env.STAGE3}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${env.STAGE3}", verifyReplicaCount: true)

  }
}

/*
podTemplate(label: 'promotion-slave', cloud: 'openshift', containers: [
  containerTemplate(name: 'jenkins-slave-image-mgmt', image: "${env.SKOPEO_SLAVE_IMAGE}", ttyEnabled: true, command: 'cat'),
  containerTemplate(name: 'jnlp', image: 'jenkinsci/jnlp-slave:2.62-alpine', args: '${computer.jnlpmac} ${computer.name}')
]) {

  node('promotion-slave') {

    stage("Promote To ${env.STAGE3}") {

      container('jenkins-slave-image-mgmt') {
        sh """

        set +x
        imageRegistry=\$(${env.OC_CMD} get is ${env.APP_NAME} --template='{{ .status.dockerImageRepository }}' -n ${env.STAGE2} | cut -d/ -f1)

        strippedNamespace=\$(echo ${env.NAMESPACE} | cut -d/ -f1)

        echo "Promoting \${imageRegistry}/${env.STAGE2}/${env.APP_NAME} -> \${imageRegistry}/${env.STAGE3}/${env.APP_NAME}"
        skopeo --tls-verify=false copy --remove-signatures --src-creds openshift:${env.TOKEN} --dest-creds openshift:${env.TOKEN} docker://\${imageRegistry}/${env.STAGE2}/${env.APP_NAME} docker://\${imageRegistry}/${env.STAGE3}/${env.APP_NAME}
        """
      }
    }

    stage("Verify Deployment to ${env.STAGE3}") {

      openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}", namespace: "${STAGE3}", verifyReplicaCount: true)

    }

  }
}
*/
println "Application ${env.APP_NAME} is now in Production!"
