//
// Jenkins pipeline script for release job.
//
// It accepts two parameters:
//
// - ghprbActualCommit (string): git commit to build
// - ghprbPullId (string): pull request ID to build
//
// These two parameters are populated by sre-bot.
//
// TODO: convert it to declarative pipeline: https://jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
//

def BUILD_URL = "git@github.com:pingcap/advanced-statefulset.git"
def BUILD_BRANCH = "${ghprbActualCommit}"

timeout(60) {

podTemplate(yaml: '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: main
    image: gcr.io/k8s-testimages/kubekins-e2e:v20191108-9467d02-master
    command:
    - runner.sh
    - sleep
    - 99d
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        memory: "8000Mi"
        cpu: 4000m
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    - name: docker-graph
      mountPath: /docker-graph
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
''') {
    node(POD_LABEL) {
        container('main') {
            stage("Debug Info") {
                println "debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            }
            stage('Checkout') {
                dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                    checkout changelog: false,
                        poll: false,
                        scm: [
                            $class: 'GitSCM',
                            branches: [[name: "${BUILD_BRANCH}"]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [],
                            submoduleCfg: [],
                            userRemoteConfigs: [[
                                credentialsId: 'github-sre-bot-ssh',
                                refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                                url: "${BUILD_URL}",
                            ]]
                        ]
                    sh """
                    echo "====== shell env ======"
                    echo "pwd: \$(pwd)"
                    env
                    echo "====== go env ======"
                    go env
                    echo "====== docker version ======"
                    docker version
                    """
                }
            }
            stage('Verify') {
                dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                    sh """
                    export GOPATH=/home/jenkins/agent/workspace/go
                    make verify
                    """
                }
            }
            def builds = [:]
            builds["Build and test"] = {
                stage('Build') {
                    dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                        sh """
                        export GOPATH=/home/jenkins/agent/workspace/go
                        make build
                        """
                    }
                }
                stage('Test') {
                    dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                        sh """
                        export GOPATH=/home/jenkins/agent/workspace/go
                        make test
                        """
                    }
                }
            }
            builds["Integration"] = {
                stage('Integration') {
                    dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                        sh """
                        export GOPATH=/home/jenkins/agent/workspace/go
                        make test-integration
                        """
                    }
                }
            }
            builds["E2E"] = {
                stage('E2E') {
                    dir("/home/jenkins/agent/workspace/go/src/github.com/pingcap/advanced-statefulset") {
                        sh """
                        export GOPATH=/home/jenkins/agent/workspace/go
                        make e2e
                        """
                    }
                }
            }
            parallel builds
        }
    }
}

}

// vim: et
