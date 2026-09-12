FROM jenkins/jenkins:lts-jdk17
USER root

# Docker CLI (client only — talks to the host's Docker Desktop daemon via the mounted socket)
COPY --from=docker:25-cli /usr/local/bin/docker /usr/local/bin/docker

# kubectl (talks to the Docker Desktop Kubernetes API server via the mounted kubeconfig)
RUN curl -fsSL -o /usr/local/bin/kubectl "https://dl.k8s.io/release/v1.30.5/bin/linux/amd64/kubectl" \
    && chmod +x /usr/local/bin/kubectl

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY casc.yaml /usr/share/jenkins/ref/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
