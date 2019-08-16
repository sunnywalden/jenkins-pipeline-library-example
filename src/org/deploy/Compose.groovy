#!groovy

class DockerCompose {

    def generate_compose() {
        filePath = "/tmp/docker-stack.yml"
        File file = new File(filePath)

        file <<
"""
version: '3.4'
services:
    "{{ APP_NAME }}":
         image: "{{ IMAGE_NAME }}:{{ env.BUILD_ID }}"
         {% if PORTS != '' -%}
         ports:
           - '{{ PORTS }}'
         {% endif %}

         environment: '{{ ENVS }}'

         networks:
           - '{{ NETWORK }}'
         volumes: '{{ VOLUMES }}'
         stop_grace_period: 30s # Specify how long to wait when attempting to stop a container if it doesn’t handle SIGTERM
         deploy:
           replicas: '{{ REPLICATES }}'
           resources:
             limits:
               memory: "{{ MEMORY_LIMIT }}"
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
           test: "{{ HEALTH_CHECK }}"
           interval: 3s
           timeout: 5s
           retries: 3
           start_period: 2m
# external.name was deprecated in version 3.5 file format use name instead.
# https://docs.docker.com/compose/compose-file/#external-1
networks:
  tezign:
    external: true
"""
    println file.text
}
}