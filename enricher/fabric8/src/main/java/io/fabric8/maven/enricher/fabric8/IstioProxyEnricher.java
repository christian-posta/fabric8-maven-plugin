/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.enricher.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpecBuilder;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;

/**
 * Created by ceposta 
 * <a href="http://christianposta.com/blog>http://christianposta.com/blog</a>.
 */
public class IstioProxyEnricher extends BaseEnricher {

    private static final String ISTIO_INIT_CONTAINER_ANNOTATION = "'[{\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"image\":\"docker.io/istio/init:0.1.1\",\"imagePullPolicy\":\"Always\",\"name\":\"init\",\"securityContext\":{\"capabilities\":{\"add\":[\"NET_ADMIN\"]}}},{\"args\":[\"-c\",\"sysctl\n" +
            "          -w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\"],\"command\":[\"/bin/sh\"],\"image\":\"alpine\",\"imagePullPolicy\":\"Always\",\"name\":\"enable-core-dump\",\"securityContext\":{\"privileged\":true}}]'";

    private static final String INIT_CONTAINER_ANNOTATION = "pod.beta.kubernetes.io/init-containers";

    public IstioProxyEnricher(EnricherContext buildContext) {
        super(buildContext, "f8-istio-proxy");
    }

    private static final String ISTIO_IO_ANNOTATION = "alpha.istio.io/sidecar";


    @Override
    public void addMissingResources(KubernetesListBuilder builder) {
        builder.accept(new TypedVisitor<PodSpecBuilder>() {
            @Override
            public void visit(PodSpecBuilder builder) {
                System.out.println("Should be adding proxy container");
            }
        });

        builder.accept(new TypedVisitor<DeploymentSpecBuilder>() {
            @Override
            public void visit(DeploymentSpecBuilder builder) {
                builder.editTemplate()
                        .editMetadata()
                        .addToAnnotations(INIT_CONTAINER_ANNOTATION, ISTIO_INIT_CONTAINER_ANNOTATION)
                        .addToAnnotations(ISTIO_IO_ANNOTATION, "injected")
                        .endMetadata()
                        .editSpec()
                            .addNewContainer()
                            .withName("proxy")
                            .withImage("docker.io/istio/proxy_debug:0.1.1")
                            .withImagePullPolicy("Always")
                            .withSecurityContext(new SecurityContext(null, null, null, null, 1337L, null))
                            .withArgs("proxy", "sidecar", "-v", "2")
                            .withEnv(new EnvVar("POD_NAME", null, new EnvVarSource(null, new ObjectFieldSelector(null, "metadata.name"), null, null)),
                                new EnvVar("POD_NAMESPACE", null, new EnvVarSource(null, new ObjectFieldSelector(null, "metadata.namespace"), null, null)),
                                    new EnvVar("POD_IP", null, new EnvVarSource(null, new ObjectFieldSelector(null, "status.podIP"), null, null)))
                        .endContainer()
                        .endSpec()
                .endTemplate();
            }
        });


    }
}
