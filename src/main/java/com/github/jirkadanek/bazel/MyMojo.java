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
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;
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
import org.apache.maven.shared.dependency.graph.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.SerializingDependencyNodeVisitor;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.utils.io.IOUtil;

import java.io.*;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

//import org.apache.maven.plugin.dependency.tree.DOTDependencyNodeVisitor;
//import org.apache.maven.plugin.dependency.tree.GraphmlDependencyNodeVisitor;
//import org.apache.maven.plugin.dependency.tree.TGFDependencyNodeVisitor;

class ComparableArtifactCoordinate extends DefaultArtifactCoordinate {
    ComparableArtifactCoordinate(DependencyNode n) {
        this(n.getArtifact());
    }

    ComparableArtifactCoordinate(Artifact a) {
        setArtifactId(a.getArtifactId());
        setClassifier(a.getClassifier());
        setExtension(a.getType());
        setGroupId(a.getGroupId());
        try {
            setVersion(a.getSelectedVersion().toString());
        } catch (OverConstrainedVersionException e) {
            throw new RuntimeException(e);
        }

    }

    private List<Object> toList() {
        // intentionally leaving out version
        return Arrays.asList(getGroupId(), getArtifactId(), getClassifier(), getExtension());
    }

    @Override
    public int hashCode() {
        return toList().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ComparableArtifactCoordinate) {
            ComparableArtifactCoordinate c = ((ComparableArtifactCoordinate) o);
            return c.toList().equals(toList());
        }
        return false;
    }
}

@Mojo(name = "sayhi", requiresDependencyCollection = ResolutionScope.TEST, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = false)
//@Mojo(name = "sayhi", requiresDependencyCollection = ResolutionScope.RUNTIME, threadSafe = false)
public class MyMojo extends AbstractMojo {

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

    private static Map<ArtifactCoordinate, DependencyNode> artifacts = new HashMap<>();

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
        if (artifacts.isEmpty()) {
            System.out.println("artifacts is empty");
        }

        Set<ComparableArtifactCoordinate> allReactorArtifacts =
                reactorProjects.stream()
                        .map((project) -> new ComparableArtifactCoordinate(project.getArtifact()))
                        .collect(Collectors.toSet());


        String dependencyTreeString;

        // TODO: note that filter does not get applied due to MSHARED-4
        ArtifactFilter artifactFilter = createResolvingArtifactFilter();

        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setProject(project);

        // non-verbose mode use dependency graph component, which gives consistent results with Maven version
        // running
        try {
            rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter, reactorProjects);
        } catch (DependencyGraphBuilderException e) {
            e.printStackTrace();
        }

//        for (DependencyNode node : rootNode.getChildren()) {
//            System.out.println(node.getArtifact());
//            for (DependencyNode node2 : rootNode.getChildren()) {
//                System.out.println(node2.getArtifact());
//            }
//        }

//        rootNode.getArtifact().getSelectedVersion()

        rootNode.accept(new DependencyNodeVisitor() {
            public boolean visit(DependencyNode dependencyNode) {
                try {
                    Artifact a = dependencyNode.getArtifact();
                    if (allReactorArtifacts.contains(new ComparableArtifactCoordinate(dependencyNode.getArtifact()))) {
                        System.out.println("skipping " + dependencyNode.toNodeString());
                    } else if (!dependencyNode.getArtifact().getType().equals("jar") && !dependencyNode.getArtifact().getType().equals("bundle")) {
                        System.out.println("skipping nonjar " + dependencyNode.toNodeString());
//                    } else if (a.getGroupId().equals("org.eclipse.jetty.aggregate") && a.getArtifactId().equals("jetty-all")) {
//                        System.out.println("skipping jetty");
                    } else {
                        storeNode(dependencyNode);
                    }
// will get to them, eventually
//                    for (DependencyNode c : dependencyNode.getChildren()) {
//                        storeNode(c);
//                    }
                } catch (Exception e) {
                    //ensure we don't silently swallow exceptions
                    e.printStackTrace();
                }
                return true;
            }

            private void storeNode(DependencyNode dependencyNode) {
                ComparableArtifactCoordinate c = new ComparableArtifactCoordinate(dependencyNode);
                DependencyNode previous = artifacts.get(c);
                if (dependencyNode.toNodeString().contains("org.apache.directory.api")) {
                    System.out.println("found dir api " + dependencyNode.toNodeString() + "under key" + c.toString());
                    System.out.flush();
                    if (previous == null) {
                        System.out.println("previous is null");
                    } else {
                        System.out.println("previous is " + previous.toNodeString());
                    }
                }
                if (previous != null) {
                    try {
                        final ArtifactVersion previousVersion = previous.getArtifact().getSelectedVersion();
                        final ArtifactVersion currentVersion = dependencyNode.getArtifact().getSelectedVersion();
                        if (previousVersion.equals(currentVersion)) {  // todo equals is not meaningfully overridden, must compareto
                            System.err.println("got two different versions there");
                            final String s = c.toString() + ": previous:" + previousVersion + " current " + currentVersion;
                            System.out.println(s);
//                            throw new RuntimeException(s);
                            // assume deps are the same, may not be true due to overrides???
                            // and due to plain reconciliation...
                        }
                        if (currentVersion.compareTo(previousVersion) > 0) {
                            System.out.println("upgrading to " + currentVersion.toString());
                            artifacts.put(c, dependencyNode);
                        }
                    } catch (OverConstrainedVersionException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                    return;
                }
                artifacts.put(c, dependencyNode);
            }

            public boolean endVisit(DependencyNode dependencyNode) {
                return true;
            }
        });

//        dependencyTreeString = serializeDependencyTree(rootNode);
        if (isLastProject()) {
            System.out.println("LAST!");
            String workspaceFileContent = generateWorkspaceFileContent();
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

    private String generateWorkspaceFileContent() {
        StringBuilder sb = new StringBuilder();
        // https://github.com/bazelbuild/bazel/issues/2049
        // load("@bazel_tools//tools/build_defs/repo:maven_rules.bzl", "maven_jar", "maven_dependency_plugin")
        sb.append("load(\"@bazel_tools//tools/build_defs/repo:maven_rules.bzl\", \"maven_jar\", \"maven_dependency_plugin\")\n");
        sb.append("def generated_maven_jars():\n");
        sb.append("  maven_dependency_plugin()\n");
        for (DependencyNode n : artifacts.values()) {
            System.out.println(n.toNodeString());
//                System.out.println(n.toNodeString());
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

        for (DependencyNode n : artifacts.values()) {
//                System.out.println(n.toNodeString());
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
//        return false;
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
//        ProjectBuildingRequest buildingRequest =
//                new DefaultProjectBuildingRequest( session.getProjectBuildingRequest() );
//        buildingRequest.setLocalRepository( session.getLocalRepository() );
//         session.getProjectBuildingRequest().getRemoteRepositories().get(1). );
//        final ProjectBuildingRequest projectBuildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
//        projectBuildingRequest.setLocalRepository(null); // force remote resolution
//        ArtifactResult resolved = resolver.resolveArtifact(projectBuildingRequest, a);
        Artifact ar = null;
        for (org.apache.maven.artifact.repository.ArtifactRepository remote : session.getProjectBuildingRequest().getRemoteRepositories()) {
//            ar = remote.find(a);
//            if (ar != null) {
            System.out.println("repo: " + remote.getUrl());
//                break;
//            }
        }
//        if (ar != null) {
//            System.out.println("repo: " + ar.getRepository());
//        }
//        System.out.println(("repo:" + session.grepogetLocalRepository().find(a).getRepository());
        String repository = a.getRepository() == null ? "http://repo.apache.maven.org/maven2/" : a.getRepository().getUrl();
        if (a.getFile() == null) {
            System.err.println("for file " + a.toString() + " file is null");
        }
        String sha1 = (a.getFile() == null) ? "" : createSha1(a.getFile());
//        String sha1 = "";
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

        /*
[jdanek@nixos:~/Work/repos/activemq-artemis]$ find ~/.cache/bazel/ -name "*.war"
/home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/jar/hawtio-web-1.5.5.war
/home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/io/hawt/hawtio-web/1.5.5/hawtio-web-1.5.5.war

[jdanek@nixos:~/Work/repos/activemq-artemis]$ ls /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/
io/        jar/       WORKSPACE

[jdanek@nixos:~/Work/repos/activemq-artemis]$ ls /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/WORKSPACE
/home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/WORKSPACE

[jdanek@nixos:~/Work/repos/activemq-artemis]$ cat /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/WORKSPACE
# DO NOT EDIT: automatically generated WORKSPACE file for maven_jar
workspace(name = "io_hawt_hawtio_web")

[jdanek@nixos:~/Work/repos/activemq-artemis]$ cat /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/io/hawt/hawtio-web/1.5.5/
hawtio-web-1.5.5.war       hawtio-web-1.5.5.war.sha1  _remote.repositories

[jdanek@nixos:~/Work/repos/activemq-artemis]$ cat /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/io/hawt/hawtio-web/1.5.5/_remote.repositories

[jdanek@nixos:~/Work/repos/activemq-artemis]$ cat /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/jar/
BUILD.bazel           hawtio-web-1.5.5.war

[jdanek@nixos:~/Work/repos/activemq-artemis]$ cat /home/jdanek/.cache/bazel/_bazel_jdanek/2e6bf0550407faabfb7b338e84352554/external/io_hawt_hawtio_web/jar/BUILD.bazel
# DO NOT EDIT: automatically generated BUILD.bazel file for maven_jar rule io_hawt_hawtio_web
java_import(
    name = 'jar',
    jars = ['hawtio-web-1.5.5.war'],
    visibility = ['//visibility:public']
)

filegroup(
    name = 'file',
    srcs = ['hawtio-web-1.5.5.war'],
    visibility = ['//visibility:public']
         */
        sb.append(
                "  native.java_library(\n" +
                        "      name = \"" + bazelName + "\",\n" +
                        "      visibility = [\"//visibility:public\"],\n" +
                        "      exports = [\"@" + bazelName + "//" + type + "\"],\n" +
                        "      runtime_deps = [");
        for (DependencyNode dep : n.getChildren()) {
            String depName = getBazelName(dep.getArtifact());
            sb.append(
                    "          \":" + depName + "\",\n"
            );
        }
        sb.append(
                "      ],\n" +
                        "  )\n");
    }

    private String getMavenName(Artifact a) throws OverConstrainedVersionException {
//        if (a.getGroupId().equals("org.eclipse.jetty.aggregate") && a.getArtifactId().equals("jetty-all")) {
//            return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getSelectedVersion() + "-uber";
//        }
//        if (a.getGroupId().equals("io.netty") && a.getArtifactId().equals("netty-transport-native-kqueue")) {
//            return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getSelectedVersion() + "-" + a.clauber";
//        }
//        io/netty/netty-transport-native-kqueue
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
//            return a.getGroupId() + ":" + a.getArtifactId() +
//                    ":" + a.getType() +
//                    ":" + a.getSelectedVersion() +
//                    (a.hasClassifier() ? ":" + a.getClassifier() : "");

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

    /**
     * Gets the artifact filter to use when resolving the dependency tree.
     *
     * @return the artifact filter
     */
    private ArtifactFilter createResolvingArtifactFilter() {
        ArtifactFilter filter;

//        // filter scope
//        if ( scope != null )
//        {
//            getLog().debug( "+ Resolving dependency tree for scope '" + scope + "'" );
//
//            filter = new ScopeArtifactFilter( scope );
//        }
//        else
//        {
        filter = null;
//        }

        return filter;
    }

//    /**
//     * Serializes the specified dependency tree to a string.
//     *
//     * @param theRootNode the dependency tree root node to serialize
//     * @return the serialized dependency tree
//     */
//    private String serializeDependencyTree(DependencyNode theRootNode) {
//        StringWriter writer = new StringWriter();
//
//        DependencyNodeVisitor visitor = getSerializingDependencyNodeVisitor(writer);
//
//        // TODO: remove the need for this when the serializer can calculate last nodes from visitor calls only
//        visitor = new BuildingDependencyNodeVisitor(visitor);
//
//        DependencyNodeFilter filter = createDependencyNodeFilter();
//
//        if (filter != null) {
//            CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
//            DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor(collectingVisitor, filter);
//            theRootNode.accept(firstPassVisitor);
//
//            DependencyNodeFilter secondPassFilter =
//                    new AncestorOrSelfDependencyNodeFilter(collectingVisitor.getNodes());
//            visitor = new FilteringDependencyNodeVisitor(visitor, secondPassFilter);
//        }
//
//        theRootNode.accept(visitor);
//
//        return writer.toString();
//    }

//    /**
//     * @param writer {@link Writer}
//     * @return {@link DependencyNodeVisitor}
//     */
//    public DependencyNodeVisitor getSerializingDependencyNodeVisitor(Writer writer) {
//        if ("graphml".equals(outputType)) {
//            return new GraphmlDependencyNodeVisitor(writer);
//        } else if ("tgf".equals(outputType)) {
//            return new TGFDependencyNodeVisitor(writer);
//        } else if ("dot".equals(outputType)) {
//            return new DOTDependencyNodeVisitor(writer);
//        } else {
//            return new SerializingDependencyNodeVisitor(writer, toGraphTokens(tokens));
//        }
//    }

    /**
     * Gets the graph tokens instance for the specified name.
     *
     * @param theTokens the graph tokens name
     * @return the <code>GraphTokens</code> instance
     */
    private SerializingDependencyNodeVisitor.GraphTokens toGraphTokens(String theTokens) {
        SerializingDependencyNodeVisitor.GraphTokens graphTokens;

        if ("whitespace".equals(theTokens)) {
            getLog().debug("+ Using whitespace tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.WHITESPACE_TOKENS;
        } else if ("extended".equals(theTokens)) {
            getLog().debug("+ Using extended tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.EXTENDED_TOKENS;
        } else {
            graphTokens = SerializingDependencyNodeVisitor.STANDARD_TOKENS;
        }

        return graphTokens;
    }

    /**
     * Gets the dependency node filter to use when serializing the dependency graph.
     *
     * @return the dependency node filter, or <code>null</code> if none required
     */
    private DependencyNodeFilter createDependencyNodeFilter() {
        List<DependencyNodeFilter> filters = new ArrayList<DependencyNodeFilter>();

//        // filter includes
//        if ( includes != null )
//        {
//            List<String> patterns = Arrays.asList( includes.split( "," ) );
//
//            getLog().debug( "+ Filtering dependency tree by artifact include patterns: " + patterns );
//
//            ArtifactFilter artifactFilter = new StrictPatternIncludesArtifactFilter( patterns );
//            filters.add( new ArtifactDependencyNodeFilter( artifactFilter ) );
//        }
//
//        // filter excludes
//        if ( excludes != null )
//        {
//            List<String> patterns = Arrays.asList( excludes.split( "," ) );
//
//            getLog().debug( "+ Filtering dependency tree by artifact exclude patterns: " + patterns );
//
//            ArtifactFilter artifactFilter = new StrictPatternExcludesArtifactFilter( patterns );
//            filters.add( new ArtifactDependencyNodeFilter( artifactFilter ) );
//        }

        return filters.isEmpty() ? null : new AndDependencyNodeFilter(filters);
    }

    // following is required because the version handling in maven code
    // doesn't work properly. I ripped it out of the enforcer rules.

    /**
     * Copied from Artifact.VersionRange. This is tweaked to handle singular ranges properly. Currently the default
     * containsVersion method assumes a singular version means allow everything. This method assumes that "2.0.4" ==
     * "[2.0.4,)"
     *
     * @param allowedRange range of allowed versions.
     * @param theVersion   the version to be checked.
     * @return true if the version is contained by the range.
     */
    public static boolean containsVersion(VersionRange allowedRange, ArtifactVersion theVersion) {
        ArtifactVersion recommendedVersion = allowedRange.getRecommendedVersion();
        if (recommendedVersion == null) {
            List<Restriction> restrictions = allowedRange.getRestrictions();
            for (Restriction restriction : restrictions) {
                if (restriction.containsVersion(theVersion)) {
                    return true;
                }
            }
        }

        // only singular versions ever have a recommendedVersion
        return recommendedVersion.compareTo(theVersion) <= 0;
    }
}
