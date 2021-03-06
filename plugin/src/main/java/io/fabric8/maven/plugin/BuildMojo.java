/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.plugin;


import java.io.*;
import java.util.List;
import java.util.Objects;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.config.BuildRecreateMode;
import io.fabric8.maven.core.config.PlatformMode;
import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.util.ProfileUtil;
import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.service.ServiceHub;
import io.fabric8.maven.docker.util.ImageName;
import io.fabric8.maven.docker.util.MojoParameters;
import io.fabric8.maven.plugin.generator.GeneratorManager;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

/**
 * Proxy to d-m-p's build Mojo
 *
 * @author roland
 * @since 16/03/16
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresDependencyResolution = ResolutionScope.COMPILE)
public class BuildMojo extends io.fabric8.maven.docker.BuildMojo {

    /**
     * Generator specific options. This is a generic prefix where the keys have the form
     * <code>&lt;generator-prefix&gt;-&lt;option&gt;</code>.
     */
    @Parameter
    private ProcessorConfig generator;

    /**
     * Profile to use. A profile contains the enrichers and generators to
     * use as well as their configuration. Profiles are looked up
     * in the classpath and can be provided as yaml files.
     *
     * However, any given enricher and or generator configuration overrides
     * the information provided by a profile.
     */
    @Parameter(property = "fabric8.profile")
    private String profile;

    /**
     * Folder where to find project specific files, e.g a custom profile
     */
    @Parameter(property = "fabric8.resourceDir", defaultValue = "${basedir}/src/main/fabric8")
    private File resourceDir;

    @Parameter(property = "fabric8.build.skip.pom", defaultValue = "true")
    private boolean skipPomBuilds;

    /**
     * Whether to perform a Kubernetes build (i.e. agains a vanilla Docker daemon) or
     * an OpenShift build (with a Docker build against the OpenShift API server.
     */
    @Parameter(property = "fabric8.mode")
    private PlatformMode mode = PlatformMode.kubernetes;

    /**
     * How to recreate the build config and/or image stream created by the build.
     * Only in effect when <code>mode == openshift</code>. If not set, existing
     * build config will not be recreated.
     *
     * The possible values are:
     *
     * <ul>
     *   <li><strong>buildConfig</strong> or <strong>bc</strong> :
     *       Only the build config is recreated</li>
     *   <li><strong>imageStream</strong> or <strong>is</strong> :
     *       Only the image stream is recreated</li>
     *   <li><strong>all</strong> : Both, build config and image stream are recreated</li>
     *   <li><strong>none</strong> : Neither build config nor image stream is recreated</li>
     * </ul>
     */
    @Parameter(property = "fabric8.build.recreate", defaultValue = "none")
    private BuildRecreateMode recreate;

    /**
     * Namespace to use when doing a Docker build against OpenShift
     */
    @Parameter(property = "fabric8.namespace")
    private String namespace;

    // Access for creating OpenShift binary builds
    private ClusterAccess clusterAccess;

    @Override
    protected void executeInternal(ServiceHub hub) throws DockerAccessException, MojoExecutionException {
        if (project != null && skipPomBuilds
            && Objects.equals("pom", project.getPackaging())) {
            getLog().debug("Disabling docker build for pom packaging");
            return;
        }
        super.executeInternal(hub);
    }

    @Override
    protected void buildAndTag(ServiceHub hub, ImageConfiguration imageConfig)
        throws MojoExecutionException, DockerAccessException {

        if (mode == PlatformMode.kubernetes) {
            super.buildAndTag(hub, imageConfig);
        } else if (mode == PlatformMode.openshift) {
            executeOpenShiftBuild(hub, imageConfig);
        } else {
            throw new MojoExecutionException("Unknown platform mode " + mode);
        }
    }

    /**
     * Customization hook called by the base plugin.
     *
     * @param configs configuration to customize
     * @return the configuration customized by our generators.
     */
    @Override
    public List<ImageConfiguration> customizeConfig(List<ImageConfiguration> configs) {
        return GeneratorManager.generate(configs, extractGeneratorConfig(), project, log);
    }

    @Override
    protected String getLogPrefix() {
        return "F8> ";
    }

    // ==================================================================================================

    // Get generator config
    private ProcessorConfig extractGeneratorConfig() {
        try {
            return ProfileUtil.extractProcesssorConfiguration(generator, ProfileUtil.GENERATOR_CONFIG, profile, resourceDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract generator config: " + e,e);
        }
    }

    // Docker build with a binary source strategy
    private void executeOpenShiftBuild(ServiceHub hub, ImageConfiguration imageConfig) throws MojoExecutionException {
        MojoParameters params = createMojoParameters();
        ImageName imageName = new ImageName(imageConfig.getName());

        // Create tar file with Docker archive
        File dockerTar = hub.getArchiveService().createDockerBuildArchive(imageConfig, params);

        OpenShiftClient client = getOpenShiftClient();

        KubernetesListBuilder builder = new KubernetesListBuilder();
        String buildName = imageName.getSimpleName() + "-build";
        String imageStreamName = imageName.getSimpleName();

        // Check for buildconfig / imagestream and create them if necessary
        checkOrCreateBuildConfig(client, builder, buildName, imageStreamName, imageName.getTag());
        checkOrCreateImageStream(client, builder, imageStreamName);
        createResourceObjects(client, builder);

        // Start the actual build
        startBuild(dockerTar, client, buildName);
    }

    // Create the openshift client
    private OpenShiftClient getOpenShiftClient() throws MojoExecutionException {
        ClusterAccess access = new ClusterAccess(namespace);
        OpenShiftClient client = access.createOpenShiftClient();
        if (!KubernetesHelper.isOpenShift(client)) {
            throw new MojoExecutionException(
                "Cannot create OpenShift Docker build with a non-OpenShift cluster at " + client.getMasterUrl());
        }
        return client;
    }


    private void startBuild(File dockerTar, OpenShiftClient client, String buildName) {
        // TODO: Wait unti kubernetes-client support instantiateBinary()
        // PR is underway ....
        log.info("Starting Build %s",buildName);
        client.buildConfigs().withName(buildName)
              .instantiateBinary()
              .fromFile(dockerTar);
    }

    private void createResourceObjects(OpenShiftClient client, KubernetesListBuilder builder) {
        KubernetesList k8sList = builder.build();
        if (k8sList.getItems().size() != 0) {
            client.lists().create(k8sList);
        }
    }

    //
    private void checkOrCreateImageStream(OpenShiftClient client, KubernetesListBuilder builder, String imageStreamName) {
        boolean hasImageStream = client.imageStreams().withName(imageStreamName).get() != null;
        if (hasImageStream && recreate.isImageStream()) {
            client.imageStreams().withName(imageStreamName).delete();
            hasImageStream = false;
        }
        if (!hasImageStream) {
            log.info("Creating ImageStream %s", imageStreamName);
            builder.addNewImageStreamItem()
                     .withNewMetadata()
                       .withName(imageStreamName)
                     .endMetadata()
                   .endImageStreamItem();
        } else {
            log.info("Using ImageStream %s", imageStreamName);
        }
    }

    private void checkOrCreateBuildConfig(OpenShiftClient client, KubernetesListBuilder builder,
                                          String buildName, String imageStreamName, String imageTag) {
        boolean hasBuildConfig = client.buildConfigs().withName(buildName).get() != null;
        if (hasBuildConfig && recreate.isBuildConfig()) {
            client.buildConfigs().withName(buildName).delete();
            hasBuildConfig = false;
        }
        if (!hasBuildConfig) {
            log.info("Creating BuildConfig %s for Docker build", buildName);
            builder.addNewBuildConfigItem()
                     .withNewMetadata()
                       .withName(buildName)
                     .endMetadata()
                     .withNewSpec()
                       .withNewStrategy()
                         .withType("Docker")
                       .endStrategy()
                       .withNewSource()
                         .withType("Binary")
                       .endSource()
                       .withNewOutput()
                         .withNewTo()
                           .withKind("ImageStreamTag")
                           .withName(imageStreamName + ":" + imageTag)
                         .endTo()
                       .endOutput()
                     .endSpec()
                   .endBuildConfigItem();
        } else {
            log.info("Using BuildConfig %s", buildName);
        }
    }

}
