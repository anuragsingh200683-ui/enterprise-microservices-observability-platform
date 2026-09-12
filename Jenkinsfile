pipeline {
    agent any

    environment {
        NAMESPACE = 'microservices'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Images') {
            parallel {
                stage('eureka-server') {
                    steps {
                        sh 'docker build -t local/eureka-server:${BUILD_NUMBER} -t local/eureka-server:1.0 ./eureka-server'
                    }
                }
                stage('employee-service') {
                    steps {
                        sh 'docker build -t local/employee-service:${BUILD_NUMBER} -t local/employee-service:1.0 ./employee-service'
                    }
                }
                stage('project-service') {
                    steps {
                        sh 'docker build -t local/project-service:${BUILD_NUMBER} -t local/project-service:1.0 ./project-service'
                    }
                }
                stage('api-gateway') {
                    steps {
                        sh 'docker build -t local/api-gateway:${BUILD_NUMBER} -t local/api-gateway:1.0 ./api-gateway'
                    }
                }
            }
        }

        stage('Apply Manifests') {
            steps {
                sh '''
                    kubectl apply -f kubernetes/namespace.yaml
                    kubectl apply -f kubernetes/microservices-configmap.yaml
                    kubectl apply -f kubernetes/eureka-deployment.yaml -f kubernetes/eureka-service.yaml
                    kubectl apply -f kubernetes/employee-deployment.yaml -f kubernetes/employee-service.yaml
                    kubectl apply -f kubernetes/project-deployment.yaml -f kubernetes/project-service.yaml
                    kubectl apply -f kubernetes/gateway-deployment.yaml -f kubernetes/gateway-service.yaml
                    kubectl apply -f kubernetes/prometheus-configmap.yaml -f kubernetes/prometheus-deployment.yaml -f kubernetes/prometheus-service.yaml
                    kubectl apply -f kubernetes/grafana-configmap.yaml -f kubernetes/grafana-dashboard-configmap.yaml -f kubernetes/grafana-deployment.yaml -f kubernetes/grafana-service.yaml
                    kubectl apply -f kubernetes/otel-collector-configmap.yaml -f kubernetes/otel-collector-deployment.yaml -f kubernetes/otel-collector-service.yaml
                    kubectl apply -f kubernetes/jaeger-deployment.yaml -f kubernetes/jaeger-service.yaml
                '''
            }
        }

        stage('Deploy New Images') {
            steps {
                sh '''
                    kubectl set image deployment/eureka-server eureka-server=local/eureka-server:${BUILD_NUMBER} -n ${NAMESPACE}
                    kubectl set image deployment/employee-service employee-service=local/employee-service:${BUILD_NUMBER} -n ${NAMESPACE}
                    kubectl set image deployment/project-service project-service=local/project-service:${BUILD_NUMBER} -n ${NAMESPACE}
                    kubectl set image deployment/api-gateway api-gateway=local/api-gateway:${BUILD_NUMBER} -n ${NAMESPACE}

                    kubectl rollout status deployment/eureka-server -n ${NAMESPACE} --timeout=180s
                    kubectl rollout status deployment/employee-service -n ${NAMESPACE} --timeout=180s
                    kubectl rollout status deployment/project-service -n ${NAMESPACE} --timeout=180s
                    kubectl rollout status deployment/api-gateway -n ${NAMESPACE} --timeout=180s
                '''
            }
        }

        stage('Smoke Test') {
            steps {
                sh '''
                    # A rollout leaves a short window where the Gateway's cached Eureka
                    # instance list still points at the just-terminated pod (Eureka
                    # registry-fetch-interval is 30s), so retry instead of failing on
                    # the first NoRouteToHostException-driven 500.
                    for path in /api/employees /api/projects; do
                      ok=false
                      for attempt in $(seq 1 12); do
                        if curl -sf "http://host.docker.internal:30080${path}" > /dev/null; then
                          echo "${path} OK (attempt ${attempt})"
                          ok=true
                          break
                        fi
                        sleep 5
                      done
                      if [ "$ok" != "true" ]; then
                        echo "${path} failed after retries"
                        exit 1
                      fi
                    done
                '''
            }
        }
    }

    post {
        success {
            echo "Build #${BUILD_NUMBER} deployed successfully to namespace ${NAMESPACE}."
        }
        failure {
            echo "Build #${BUILD_NUMBER} failed - check the stage logs above."
        }
    }
}
