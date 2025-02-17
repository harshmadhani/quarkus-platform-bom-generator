package io.quarkus.bom.test;

import io.quarkus.bom.decomposer.ProjectDependency;
import io.quarkus.bom.decomposer.ProjectRelease;
import io.quarkus.bom.decomposer.ReleaseIdFactory;
import io.quarkus.bom.decomposer.ReleaseOrigin;
import io.quarkus.bom.decomposer.ReleaseOrigin.ScmConnectionOrigin;
import io.quarkus.bom.decomposer.ReleaseVersion;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepositoryManager;

public class ProjectReleaseInstaller {

    public static ProjectReleaseInstaller forGa(String groupId, String artifact) {
        return new ProjectReleaseInstaller(ReleaseOrigin.Factory.ga(groupId, artifact)).projectGroupId(groupId);
    }

    public static ProjectReleaseInstaller forScm(String scm) {
        return new ProjectReleaseInstaller(ReleaseOrigin.Factory.scmConnection(scm));
    }

    public static ProjectReleaseInstaller forScmAndTag(String scm, String tag) {
        return new ProjectReleaseInstaller(ReleaseOrigin.Factory.scmConnection(scm)).tag(tag);
    }

    public static ProjectReleaseInstaller forParentPom(String coordsString) {
        final ArtifactCoords coords = ArtifactCoords.fromString(coordsString);
        final ProjectReleaseInstaller installer = forGa(coords.getGroupId(), coords.getArtifactId())
                .version(coords.getVersion())
                .parentPomArtifactId(coords.getArtifactId());
        return installer;
    }

    private final ReleaseOrigin origin;
    private String projectGroupId;
    private String projectVersion;
    private PomInstaller parentPom;
    private ProjectRelease.Builder pb;
    private MavenArtifactResolver resolver;

    private ProjectReleaseInstaller(ReleaseOrigin origin) {
        this.origin = Objects.requireNonNull(origin);
    }

    /**
     * Associates a release with a tag. Either tag or {@code #version(String)} has to be set.
     * 
     * @param tag tag
     * @return this builder
     */
    public ProjectReleaseInstaller tag(String tag) {
        this.pb = ProjectRelease.builder(ReleaseIdFactory.create(origin, ReleaseVersion.Factory.tag(tag)));
        this.projectVersion = tag;
        return this;
    }

    /**
     * Associates a release with a given version. Either tag or {@code #version(String)} has to be set.
     * 
     * @param version
     * @return
     */
    public ProjectReleaseInstaller version(String version) {
        this.pb = ProjectRelease.builder(ReleaseIdFactory.create(origin, ReleaseVersion.Factory.version(version)));
        this.projectVersion = version;
        return this;
    }

    public ProjectReleaseInstaller projectGroupId(String projectGroupId) {
        this.projectGroupId = projectGroupId;
        return this;
    }

    public ProjectReleaseInstaller parentPomArtifactId(String parentPomArtifactId) {
        parentPom = PomInstaller.forGav(projectGroupId, parentPomArtifactId, projectVersion).packaging("pom");
        return this;
    }

    public ProjectReleaseInstaller artifactCoords(String coordsStr) {
        assertProjectBuilder();
        final ArtifactCoords coords = ArtifactCoords.fromString(coordsStr);
        final Artifact a = new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(),
                coords.getClassifier(), coords.getType(), coords.getVersion());
        artifact(a);
        return this;
    }

    public ProjectReleaseInstaller artifactId(String artifactId) {
        assertProjectBuilder();
        if (projectGroupId == null) {
            throw new RuntimeException("Project groupId has not been initialized");
        }
        final Artifact a = new DefaultArtifact(projectGroupId, artifactId, null, "jar", projectVersion);
        artifact(a);
        return this;
    }

    private void artifact(final Artifact a) {
        pb.add(ProjectDependency.create(pb.id(), a));
        if (parentPom != null) {
            parentPom.module(a.getArtifactId());
        }
    }

    public ProjectReleaseInstaller resolver(MavenArtifactResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    public ProjectRelease install() {
        if (resolver == null) {
            throw new RuntimeException("Maven resolver has not been configured");
        }
        final ProjectRelease pr = pb.build();

        if (parentPom != null) {
            parentPom.resolver(resolver).install();
        }
        for (ProjectDependency d : pr.dependencies()) {
            final Artifact a = d.artifact();
            final PomInstaller pomInstaller = PomInstaller.forGav(a.getGroupId(), a.getArtifactId(), a.getVersion());
            if (parentPom != null) {
                pomInstaller.parent(parentPom.model().getGroupId(), parentPom.model().getArtifactId(),
                        parentPom.model().getVersion());
            }
            if (pb.id().origin() instanceof ReleaseOrigin.ScmConnectionOrigin) {
                final ReleaseOrigin.ScmConnectionOrigin scm = (ScmConnectionOrigin) pb.id().origin();
                pomInstaller.scm(scm.toString(), pb.id().version().asString());
            }
            if ("pom".equals(a.getExtension())) {
                pomInstaller.packaging("pom");
            } else {
                final File target = initRepoPath(a);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(target))) {
                    writer.append(a.toString());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to persist " + a + " at " + target, e);
                }
            }
            pomInstaller.resolver(resolver).install();
        }

        return pr;
    }

    private File initRepoPath(Artifact a) {
        final LocalRepositoryManager localRepo = resolver.getSession().getLocalRepositoryManager();
        final File f = new File(localRepo.getRepository().getBasedir(), localRepo.getPathForLocalArtifact(a));
        f.getParentFile().mkdirs();
        return f;
    }

    private void assertProjectBuilder() {
        if (pb == null) {
            throw new RuntimeException("ProjectRelease.Builder has not been initialized");
        }
    }
}
