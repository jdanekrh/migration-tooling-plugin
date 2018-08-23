package com.github.jirkadanek.bazel;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class BazelDependencyNodeVisitor implements DependencyNodeVisitor {
    private static Map<ArtifactCoordinate, DependencyNode> artifacts = new HashMap<>();
    private final Set<ComparableArtifactCoordinate> allReactorArtifacts;

    BazelDependencyNodeVisitor(Set<ComparableArtifactCoordinate> allReactorArtifacts) {
        this.allReactorArtifacts = allReactorArtifacts;
    }

    public boolean visit(DependencyNode dependencyNode) {
        try {
            Artifact a = dependencyNode.getArtifact();
            if (allReactorArtifacts.contains(new ComparableArtifactCoordinate(dependencyNode.getArtifact()))) {
                System.out.println("skipping " + dependencyNode.toNodeString());
            } else if (!dependencyNode.getArtifact().getType().equals("jar") && !dependencyNode.getArtifact().getType().equals("bundle")) {
                System.out.println("skipping nonjar " + dependencyNode.toNodeString());
            } else {
                storeNode(dependencyNode);
            }
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
                    // TODO: assumes deps are the same. Consider maven overrides and plain reconciliation.
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

    public Collection<DependencyNode> getArtifactDependencyNodes() {
        return artifacts.values();
    }
}
