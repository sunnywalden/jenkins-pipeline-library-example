#!groovy

import groovy.json.JsonSlurper

def generate_compose() {
        filePath = "/tmp/docker-stack.yml"
        File file = new File(filePath)
        file.delete()
        file <<
"""
version: '3.4'
services:
    "${APP_NAME}":
         image: "${IMAGE_NAME}:${TAG}"
         ports:
           - ${PORTS}
         environment:
           - ${ENVS}
         networks:
           - "${NETWORK}"
         volumes:
           - ${VOLUMES}
         stop_grace_period: 30s # Specify how long to wait when attempting to stop a container if it doesn’t handle SIGTERM
         deploy:
           replicas: ${REPLICATES}
           resources:
             limits:
               memory: "${MEMORY_LIMIT}"
           update_config:
             parallelism: 1            # The number of containers to update at a time.
             delay: 0s                 # The time to wait between updating a group of containers.
             failure_action: rollback  # What to do if an update fails  One of continue, rollback, or pause (default: pause).
             max_failure_ratio: 0      # Failure rate to tolerate during an update.
             order: start-first
           restart_policy:
             condition: any
             max_attempts: 3
         healthcheck:
           test: "${HEALTH_CHECK}"
           interval: 3s
           timeout: 5s
           retries: 3
           start_period: 2m
networks:
  tezign:
    external: true
"""
    println file.text
}


def getServer() {
    def remote = [:]
    remote.name = 'manager node'
    remote.user = "${REMOTE_USER}"
    remote.host = "${REMOTE_HOST}"
    remote.password = "${REMOTE_SUDO_PASSWORD}"
    remote.port = "${REMOTE_PORT}".toInteger()
    remote.identityFile = '/root/.ssh/id_rsa'
    remote.allowAnyHosts = true
    return remote
}

def str_to_list(str) {
    return list = str.split(',')
}

def list_ready_use(str) {
    res = ""
    list = str.split(',')
    list.each { item ->
        res += '- ' + item + '\n'
    }
    return res

}

def mk_dir(volumes_str) {
    volumes_list = volumes_str.split(',')
    volumes_list.each { item ->
        vls = item.split(':')
        if (vls.size() == 2) {
            dest_dir = vls[0]
            File dir = new File(dest_dir)
            if (dir.isDirectory()) {
                dest_path = dest_dir
            }
            else {
                dest_path = dir.absolutePath

            }
            echo "Volume to be created: ${dest_path}"
//             File file = new File(dest_path)
//             file.mkdir()
            sshCommand remote: remote, sudo: true, command: "mkdir -p ${dest_path}"

        }
        else {
            return 0
        }
    }
}

def send_all(file_str) {

    files_list = file_str.split(',')
    files_list.each { item ->
            echo "print file object to be send: ${item}"
            files = item.split(':')
            if (files.size() == 2) {
                source_file = files[0]
                dest_file = files[1]
            } else {
                return 0
            }
            echo "send file ${source_file} to ${dest_file}"
            sshPut remote: remote, from: "${source_file}", into: "${dest_file}"
    }
}


def call(String type, Map map) {
    pipeline {
        agent {
            label "master"
        }

        environment {
            // Ansible host
            REMOTE_HOST = "${map.REMOTE_HOST}"
            REMOTE_USER = "${map.REMOTE_USER}"
            REMOTE_PORT = "${map.REMOTE_PORT}"
            REMOTE_SUDO_PASSWORD = "${map.REMOTE_SUDO_PASSWORD}"
            //  git config
            REPO_URL = "${map.REPO_URL}"
            BRANCH_NAME = "${map.BRANCH_NAME}"
            CREDENTIALS_ID = 'artifactory'
//             TAG = "${map.tag}"

            // env type
            ENV_TYPE = "${map.ENV_TYPE}"

            // build config
            // values in: npm maven or none
            BUILD_TYPE = "${map.BUILD_TYPE}"
            BUILD_ARGS = "${map.BUILD_ARGS}"
            BUILD_CMD = "${map.BUILD_CMD}"


            // docker config
            REGISTRY_URL = "${map.REGISTRY_URL}"
            MEMORY_LIMIT = "${map.MEMORY_LIMIT}"
            PORTS = "${map.PORTS}"
            REPLICATES = "${map.REPLICATES}".toInteger()
            HEALTH_CHECK = "${map.HEALTH_CHECK}"
            NETWORK = "${map.NETWORK}"
            VOLUMES = "${map.VOLUMES}"
            ENVS = "${map.ENVS}"
            TAG = "${map.tag}"

            // deploy config
            APP_NAME = "${map.APP_NAME}"
            IMAGE_NAME = "${REGISTRY_URL}/" + "${map.APP_NAME}" + "_${map.ENV_TYPE}"
            STACK_FILE_NAME = 'docker-stack.yml'
            SEND_FILES = "${map.SEND_FILES}"

            ENVS_LIST = list_ready_use("${ENVS}")
            PORTS_LIST = list_ready_use("${PORTS}")
            VOLUMES_LIST = list_ready_use("${VOLUMES}")
        }

        stages {
            stage('获取代码') {

                steps {
                    git([url: "${REPO_URL}", branch: "${BRANCH_NAME}", credentialsId: "${CREDENTIALS_ID}"])
                }
            }

            stage('编译代码') {
                steps {
                    echo "Ignore"
//                     sh "${BUILD_CMD}"
                }
            }

            stage('构建镜像') {
                steps {
                    sh "docker build -t ${IMAGE_NAME}:${TAG} ."
                    sh "docker push ${IMAGE_NAME}:${env.TAG}"
                }
            }

            stage('获取主机') {
                steps {
                    script {
                        remote = getServer()
                    }
                }
            }

            stage('更新') {
                when {
                    expression {
                        SEND_FILES != []
                    }
                }
                steps {
                    echo "print all files objects: ${SEND_FILES}"
                    // send files
                    send_all("${SEND_FILES}")
                }
            }

            stage('发布') {
                steps {
                    echo "${ENVS} ${PORTS} ${VOLUMES}"

                       writeFile file: 'deploy.sh', text: "sudo docker stack deploy -c /tmp/${STACK_FILE_NAME} ${APP_NAME} \n" +
                           "sleep 180 \n" +
                           "run_docker=`docker service ps ${APP_NAME}_${APP_NAME} | grep Running|wc -l` \n" +
                           "health_docker=`docker ps|grep ${APP_NAME}_${APP_NAME} |grep -v unhealthy|grep healthy|wc -l`" +
                           "if [[ \$run_docker -eq 1 ]] && [[ \$health_docker -ne 0 ];then echo 'Deploy success';else echo 'Deploy failed';fi"

                    // deploy
                    generate_compose()
                    mk_dir("${VOLUMES}")
                    sshPut remote: remote, from: "/tmp/docker-stack.yml", into: "/tmp/docker-stack.yml"
                    sshScript remote: remote, script: "deploy.sh"
                }
            }
        }
        post {
            always {
                echo 'Deploy pipeline finished'
            }
            failure {
                mail to: 'sunnywalden@gmail.com', subject: 'The Pipeline failed', body: '''
                    Deploy pipeline is failed
                '''
			    dingTalk accessToken:'https://oapi.dingtalk.com/robot/send?access_token=***',
			        jenkinsUrl:'https://your-jenkins-url',
			        message:'pipeline构建失败',
			        notifyPeople:'zhangbo'
			        imageUrl:'../img/jenkins.png',
            }
        }
    }
//     }
}