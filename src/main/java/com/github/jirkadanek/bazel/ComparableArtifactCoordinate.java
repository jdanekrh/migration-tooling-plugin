package com.github.jirkadanek.bazel;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;

import java.util.Arrays;
import java.util.List;

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
