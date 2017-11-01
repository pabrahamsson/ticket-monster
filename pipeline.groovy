#!/usr/bin/groovy

////
// This pipeline requires the following plugins:
// Kubernetes Plugin 0.10
////

String ocpApiServer = env.OCP_API_SERVER ? "${env.OCP_API_SERVER}" : "https://openshift.default.svc.cluster.local"

// define helper functions
def ocp_object_exist(object, name, namespace) {
  rc = (sh(returnStatus: true, script: "${env.OC_CMD} get ${object}/${name} -n ${namespace}") == 0) ? true : false
}
def ocp_create_dc(app_name, color, namespace) {
  sh "${env.OC_CMD} process blue-green-deploymentconfig -p APPLICATION_NAME=${app_name} -p COLOR=${color} -p NAMESPACE=${namespace}|${env.OC_CMD} apply -n ${namespace} -f -"
}

node('master') {

  env.NAMESPACE = readFile('/var/run/secrets/kubernetes.io/serviceaccount/namespace').trim()
  env.TOKEN = readFile('/var/run/secrets/kubernetes.io/serviceaccount/token').trim()
  env.OC_CMD = "oc --token=${env.TOKEN} --server=${ocpApiServer} --certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt --namespace=${env.NAMESPACE}"

  env.APP_NAME = "${env.JOB_NAME}".replaceAll(/-?pipeline-?/, '').replaceAll(/-?${env.NAMESPACE}-?/, '')
  //def projectBase = "${env.NAMESPACE}".replaceAll(/-dev/, '')
  def projectBase = env.APP_NAME
  env.STAGE1 = "${projectBase}-dev-pabraham"
  env.STAGE2 = "${projectBase}-stage-pabraham"
  env.STAGE3 = "${projectBase}-prod-pabraham"

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
  def active_color = sh(returnStdout: true, script: "${env.OC_CMD} get route ${env.APP_NAME} -n ${env.STAGE1} -o jsonpath='{ .spec.to.name }'").trim().replaceAll("${env.APP_NAME}-", '')
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
    // create deploymentconfigs if !exist
    for (color in ['blue', 'green']) {
      if (!ocp_object_exist('dc', "${env.APP_NAME}-${color}", env.STAGE1)) {
        ocp_create_dc(env.APP_NAME, color, env.STAGE1)
      }
    }
    // Get currently number of replicas of currently active dc or create new one
    replicas = sh(returnStdout: true, script: "${env.OC_CMD} get dc/${env.APP_NAME}-${active_color} -o jsonpath='{ .spec.replicas }' -n ${env.STAGE1}")
    if (replicas > 0) {
      openshiftScale(depCfg: "${env.APP_NAME}-${dest_color}", namespace: "${env.STAGE1}", replicaCount: replicas, verifyReplicaCount: true)
    } else {
      openshiftScale(depCfg: "${env.APP_NAME}-${dest_color}", namespace: "${env.STAGE1}", replicaCount: 1, verifyReplicaCount: true)
    }
    rc = sh(returnStatus: true, script: "${env.OC_CMD} patch route/${env.APP_NAME} -n ${env.STAGE1} -p '{\"spec\":{\"to\":{\"name\":\"${env.APP_NAME}-${dest_color}\"}}}'")
    if (rc == 0) {
      openshiftScale(depCfg: "${env.APP_NAME}-${active_color}", namespace: "${env.STAGE1}", replicaCount: 0, verifyReplicaCount: true)
    }
  }
}
/*
  stage("Verify Deployment to ${env.STAGE1}") {

    openshiftVerifyDeployment(deploymentConfig: "${env.APP_NAME}-${dest_color}", namespace: "${env.STAGE1}", verifyReplicaCount: true)

    //input "Promote Application to Stage?"
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
}*/

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
