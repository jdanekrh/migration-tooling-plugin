package com.github.jirkadanek.bazel;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.utils.io.IOUtil;

import java.io.*;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(name = "generate-workspace", requiresDependencyCollection = ResolutionScope.TEST, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = false)
public class GenerateWorkspaceMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    private DependencyNode rootNode;

    @Parameter(property = "outputFile")
    private File outputFile;

    @Parameter(property = "project.build.directory")
    private File outputDirectory;

    private boolean isLastProject() {
        // https://stackoverflow.com/questions/132976/how-do-you-force-a-maven-mojo-to-be-executed-only-once-at-the-end-of-a-build
        final int size = reactorProjects.size();
        MavenProject lastProject = reactorProjects.get(size - 1);
        return lastProject == project;
    }

    public static synchronized void write(String string, File file, boolean append, Log log)
            throws IOException {
        file.getParentFile().mkdirs();

        FileWriter writer = null;

        try {
            writer = new FileWriter(file, append);

            writer.write(string);

            writer.close();
            writer = null;
        } finally {
            IOUtil.close(writer);
        }
    }

    public static synchronized void log(String string, Log log)
            throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(string));

        String line;

        while ((line = reader.readLine()) != null) {
            log.info(line);
        }

        reader.close();
    }

    public void execute() throws MojoExecutionException {
        Set<ComparableArtifactCoordinate> allReactorArtifacts =
                reactorProjects.stream()
                        .map((project) -> new ComparableArtifactCoordinate(project.getArtifact()))
                        .collect(Collectors.toSet());

        // TODO: note that filter does not get applied due to MSHARED-4
        ArtifactFilter artifactFilter = null;

        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setProject(project);

        // non-verbose mode use dependency graph component, which gives consistent results with Maven version
        try {
            rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter, reactorProjects);
        } catch (DependencyGraphBuilderException e) {
            e.printStackTrace();
        }

        final BazelDependencyNodeVisitor visitor = new BazelDependencyNodeVisitor(allReactorArtifacts);
        rootNode.accept(visitor);

        if (isLastProject()) {
            String workspaceFileContent = generateWorkspaceFileContent(visitor.getArtifactDependencyNodes());
            if (outputFile == null) {
                try {
                    log(workspaceFileContent, getLog());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    write(workspaceFileContent, outputFile, false, getLog());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private String generateWorkspaceFileContent(Collection<DependencyNode> artifacts) {
        StringBuilder sb = new StringBuilder();
        // https://github.com/bazelbuild/bazel/issues/2049
        // load("@bazel_tools//tools/build_defs/repo:maven_rules.bzl", "maven_jar", "maven_dependency_plugin")
        sb.append("load(\"@bazel_tools//tools/build_defs/repo:maven_rules.bzl\", \"maven_jar\", \"maven_dependency_plugin\")\n");
        sb.append("def generated_maven_jars():\n");
        sb.append("  maven_dependency_plugin()\n");
        for (DependencyNode n : artifacts) {
            System.out.println(n.toNodeString());
            try {
                if (shouldSkipInternalArtifact(n)) {
                    continue;
                }
                mavenJar(sb, n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        sb.append("\n\n\n");

        sb.append("def generated_java_libraries():\n");

        for (DependencyNode n : artifacts) {
            try {
                if (shouldSkipInternalArtifact(n)) {
                    continue;
                }
                javaLibrary(sb, n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return sb.toString();
    }

    private boolean shouldSkipInternalArtifact(DependencyNode n) throws OverConstrainedVersionException {
        return getMavenName(n.getArtifact()).startsWith("org.apache.activemq:artemis") ||
                getMavenName(n.getArtifact()).startsWith("org.apache.activemq.tests:");
    }

    public String createSha1(File file) throws Exception {
        // https://stackoverflow.com/questions/6293713/java-how-to-create-sha-1-for-a-file
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            final DigestInputStream digestInputStream = new DigestInputStream(bis, digest);
            while (digestInputStream.read() != -1) {
            }
            byte[] bytes = digest.digest();
            return String.format("%040x", new BigInteger(1, bytes));
        }
    }

    @Component
    protected ArtifactResolver resolver;

    private void mavenJar(StringBuilder sb, DependencyNode n) throws Exception {
        final Artifact a = n.getArtifact();
        String bazelName = getBazelName(a);
        String mavenName = getMavenName(a);
        for (org.apache.maven.artifact.repository.ArtifactRepository remote : session.getProjectBuildingRequest().getRemoteRepositories()) {
            System.out.println("repo: " + remote.getUrl());
        }
        String repository = a.getRepository() == null ? "http://repo.apache.maven.org/maven2/" : a.getRepository().getUrl();
        if (a.getFile() == null) {
            System.err.println("for file " + a.toString() + " file is null");
        }
        String sha1 = (a.getFile() == null) ? "" : createSha1(a.getFile());
        if (useNativeMavenJar(a)) { // || a.getType().equals("bundle") ) {
            sb.append("  native.maven_jar(\n");
        } else {
            sb.append("  maven_jar(\n");
        }
        sb.append("      name = \"" + bazelName + "\",\n" +
                "      artifact = \"" + mavenName + "\",\n" +
                "      repository = \"" + repository + "\",\n" +
                "      sha1 = \"" + sha1 + "\",\n" +
                "  )\n");
    }

    private boolean useNativeMavenJar(Artifact a) {
        return (a.getType().equals("jar") || a.getType().equals("bundle")); // && !a.hasClassifier();
    }

    private void javaLibrary(StringBuilder sb, DependencyNode n) throws OverConstrainedVersionException {
        final Artifact a = n.getArtifact();
        String bazelName = getBazelName(a);
        String type = a.getType().equals("bundle") ? "jar" : a.getType(); //not sure why i put classifier here before .hasClassifier() ? a.getType() : "jar";
//        String type = "jar";  // looks like bazel native cannot fetch anything else; edit it can, it is always a jar

        sb.append(
                "  native.java_library(\n" +
                        "      name = \"" + bazelName + "\",\n" +
                        "      visibility = [\"//visibility:public\"],\n" +
                        "      exports = [\"@" + bazelName + "//" + type + "\"],\n");
        if (!n.getChildren().isEmpty()) {
            sb.append("      runtime_deps = [\n");
            for (DependencyNode dep : n.getChildren()) {
                String depName = getBazelName(dep.getArtifact());
                sb.append("          \":" + depName + "\",\n");
            }
            sb.append(
                    "      ],\n");
        }
        sb.append("  )\n");
    }

    private String getMavenName(Artifact a) throws OverConstrainedVersionException {
        // https://maven.apache.org/plugins/maven-dependency-plugin/get-mojo.html
        // A string of the form groupId:artifactId:version[:packaging[:classifier]].

        final String version = ":" + a.getSelectedVersion();
        final String packaging = a.getType().equals("bundle") ? ":jar" : ":" + a.getType();
        final String classifier = a.hasClassifier() ? ":" + a.getClassifier() : "";
        if (useNativeMavenJar(a)) { // || a.getType().equals("bundle")) {
            // native maven_jar
            // https://github.com/bazelbuild/bazel/issues/2049
            return a.getGroupId() + ":" + a.getArtifactId() +
                    packaging +
                    classifier +
                    version;
        } else {
            // https://maven.apache.org/plugins/maven-dependency-plugin/get-mojo.html
            // A string of the form groupId:artifactId:version[:packaging[:classifier]].
            return a.getGroupId() + ":" + a.getArtifactId() +
                    version +
                    packaging +
                    classifier;
        }
    }

    private String getBazelName(Artifact a) throws OverConstrainedVersionException {
        return replace(a.getGroupId()) +
                "_" +
                replace(a.getArtifactId()) +
                (a.hasClassifier() ? "_" + replace(a.getClassifier()) : "");
    }

    private String replace(String s) {
        return s.replace(".", "_").replace("-", "_");
    }
}
