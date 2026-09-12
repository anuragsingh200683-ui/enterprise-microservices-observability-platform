FROM jenkins/jenkins:lts-jdk17
USER root

# Docker CLI (client only — talks to the host's Docker Desktop daemon via the mounted socket)
COPY --from=docker:25-cli /usr/local/bin/docker /usr/local/bin/docker

# kubectl (talks to the Docker Desktop Kubernetes API server via the mounted kubeconfig)
RUN curl -fsSL -o /usr/local/bin/kubectl "https://dl.k8s.io/release/v1.30.5/bin/linux/amd64/kubectl" \
    && chmod +x /usr/local/bin/kubectl

# JDK 21 for compiling the services (separate from the JDK17 running Jenkins itself)
# used as the "JDK21" Jenkins tool declared in casc.yaml
RUN mkdir -p /opt/jdk21 \
    && curl -fsSL -o /tmp/jdk21.tar.gz "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz" \
    && tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk21 --strip-components=1 \
    && rm /tmp/jdk21.tar.gz

# Maven 3.9 - used as the "Maven_3" Jenkins tool declared in casc.yaml
RUN mkdir -p /opt/maven \
    && curl -fsSL -o /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" \
    && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 \
    && rm /tmp/maven.tar.gz

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY casc.yaml /usr/share/jenkins/ref/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
