/*
 * Copyright 2005-2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.KubernetesHelper;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.tar.TarUnArchiver;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates a Helm <code>index.yaml</code> file by querying a maven repository
 * to find all the charts available and their releases.
 */
@Mojo(name = "helm-index")
public class HelmIndexMojo extends AbstractFabric8Mojo {

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Folder where to find project specific files
     */
    @Parameter(property = "fabric8.helm.indexFile", defaultValue = "${project.build.directory}/fabric8/helm-index.yaml")
    private File outputFile;

    /**
     * Folder where to find project specific files
     */
    @Parameter(property = "fabric8.helm.tempDir", defaultValue = "${project.build.directory}/fabric8/tmp-charts")
    private File tempDir;

    @Component
    protected ArtifactResolver artifactResolver;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}")
    protected List<MavenArtifactRepository> remoteRepositories;

    @Component(role = UnArchiver.class, hint = "tar")
    private TarUnArchiver unArchiver;

    @Parameter(defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    @Parameter(property = "fabric8.helm.index.maxSearchResults", defaultValue = "50000")
    private int maxSearchResults = 2;

    @Parameter(property = "fabric8.helm.index.mavenRepoUrl", defaultValue = "http://central.maven.org/maven2/")
    private String mavenRepoUrl;

    @Parameter(property = "fabric8.helm.index.mavenRepoSearchUrl", defaultValue = "http://search.maven.org/solrsearch/select")
    private String mavenRepoSearchUrl;

    @Override
    public void executeInternal() throws MojoExecutionException, MojoFailureException {
        outputFile.getParentFile().mkdirs();

        log.info("Creating Helm Chart Index file at: %s", outputFile);
        Result result = null;
        String urlText = mavenRepoSearchUrl + "?q=l:%22helm%22&wt=json&rows=" + maxSearchResults;
        try {
            URL url = new URL(urlText);
            ObjectMapper mapper = new ObjectMapper();
            result = mapper.readerFor(Result.class).readValue(url);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not query " + urlText + " : " + e, e);
        }

        if (result == null) {
            throw new MojoExecutionException("No result!");
        }
        Response response = result.getResponse();
        if (response == null) {
            throw new MojoExecutionException("No response!");
        }
        List<ArtifactDTO> artifacts = response.getDocs();
        if (artifacts == null) {
            throw new MojoExecutionException("No docs!");
        }
        Map<String, ChartInfo> charts = new TreeMap<>();
        for (ArtifactDTO artifact : artifacts) {
            addChartInfo(charts, artifact);
        }

        Set<Map.Entry<String, ChartInfo>> entries = charts.entrySet();
        for (Map.Entry<String, ChartInfo> entry : entries) {
            getLog().debug("" + entry.getKey() + " = " + entry.getValue());
        }
        try {
            ObjectMapper mapper = KubernetesHelper.createYamlObjectMapper();
            mapper.writeValue(outputFile, charts);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write results as YAML to: " + outputFile + ". " + e, e);
        }

        // projectHelper.attachArtifact(project, "yaml", "helm-index", destinationFile);
    }

    protected void addChartInfo(Map<String, ChartInfo> charts, ArtifactDTO artifact) {
        // lets create the latest chart
        ChartInfo latest = new ChartInfo(mavenRepoUrl, artifact);
        String key = createChartKey(artifact);

        // if we could load the chartfile lets add it
        HelmMojo.Chart chartfile = createChartFile(artifact);
        if (chartfile != null) {
            latest.setChartfile(chartfile);
            charts.put(key, latest);
        } else {
            getLog().warn("Could not find chartfile for " + latest);
        }
    }

    private String createChartKey(ArtifactDTO artifact) {
        return artifact.getA() + "-" + artifact.getV();
    }

    private HelmMojo.Chart createChartFile(ArtifactDTO artifactDTO) {
        File file = null;
        try {
            ArtifactRequest artifactRequest = new ArtifactRequest();
            org.eclipse.aether.artifact.Artifact artifact = new DefaultArtifact(artifactDTO.getG(), artifactDTO.getA(), "helm", "tar.gz", artifactDTO.getV());
            artifactRequest.setArtifact(artifact);

            // convert maven remote repositories to Aether repos
            List<RemoteRepository> aetherRepoList = new ArrayList<>();
            for (MavenArtifactRepository remoteRepository : remoteRepositories) {
                RemoteRepository.Builder builder = new RemoteRepository.Builder(remoteRepository.getId(), remoteRepository.getLayout().getId(), remoteRepository.getUrl());
                RemoteRepository aetherRepo = builder.build();
                aetherRepoList.add(aetherRepo);
            }
            artifactRequest.setRepositories(aetherRepoList);

            ArtifactResult artifactResult = artifactResolver.resolveArtifact(repoSession, artifactRequest);
            org.eclipse.aether.artifact.Artifact resolvedArtifact = artifactResult.getArtifact();
            if (resolvedArtifact == null) {
                getLog().warn("Could not resolve artifact " + artifactDTO.description());
                return null;
            }
            file = resolvedArtifact.getFile();

        } catch (Exception e) {
            getLog().warn("Failed to resolve helm chart for " + artifactDTO.description() + ". " + e, e);
            return null;
        }

        if (file == null) {
            getLog().warn("Could not resolve artifact file for " + artifactDTO.description());
            return null;
        }
        if (!file.isFile() || !file.exists()) {
            getLog().warn("Resolved artifact file does not exist for " + artifactDTO.description());
            return null;
        }


        File untarDestDir = new File(tempDir, artifactDTO.getG() + "/" + artifactDTO.getA() + "-" + artifactDTO.getV() + "-chart");
        untarDestDir.mkdirs();
        getLog().debug("" + artifactDTO.description() + " extracting " + file.getAbsolutePath() + " to " + untarDestDir);
        unArchiver.setSourceFile(file);
        unArchiver.setDestDirectory(untarDestDir);
        unArchiver.setCompression(TarUnArchiver.UntarCompressionMethod.GZIP);
        unArchiver.extract();

        File tempChartFile = new File(untarDestDir, "Chart.yaml");
        if (!tempChartFile.isFile() || !tempChartFile.exists()) {
            getLog().warn("No Chart.yaml exists at " + tempChartFile);
            return null;
        }
        try {
            return KubernetesHelper.loadYaml(tempChartFile, HelmMojo.Chart.class);
        } catch (IOException e) {
            getLog().warn("Failed to parse " + tempChartFile + ". " + e, e);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private Response response;

        public Response getResponse() {
            return response;
        }

        public void setResponse(Response response) {
            this.response = response;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private List<ArtifactDTO> docs;

        public List<ArtifactDTO> getDocs() {
            return docs;
        }

        public void setDocs(List<ArtifactDTO> docs) {
            this.docs = docs;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArtifactDTO {
        private String id;
        private String g;
        private String a;
        private String v;
        private String p;

        @Override
        public String toString() {
            return "ArtifactDTO: " + g + ":" + a + ":" + v;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getG() {
            return g;
        }

        public void setG(String g) {
            this.g = g;
        }

        public String getA() {
            return a;
        }

        public void setA(String a) {
            this.a = a;
        }

        public String getV() {
            return v;
        }

        public void setV(String v) {
            this.v = v;
        }

        public String getP() {
            return p;
        }

        public void setP(String p) {
            this.p = p;
        }

        public String description() {
            return "" + g + ":" + a + ":" + v;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    protected static class ChartInfo {
        private String url;
        private String name;
        private HelmMojo.Chart chartfile;

        public ChartInfo(String mavenRepoUrl, ArtifactDTO artifact) {
            String artifactId = artifact.getA();
            String version = artifact.getV();
            this.url = mavenRepoUrl + artifact.getG().replace('.', '/') +
                    "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + "-helm.tar.gz";
            this.name = artifactId;
        }

        @Override
        public String toString() {
            return "ChartInfo{" +
                    "url='" + url + '\'' +
                    ", name='" + name + '\'' +
                    ", chartfile=" + chartfile +
                    '}';
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public HelmMojo.Chart getChartfile() {
            return chartfile;
        }

        public void setChartfile(HelmMojo.Chart chartfile) {
            this.chartfile = chartfile;
        }
    }
}
