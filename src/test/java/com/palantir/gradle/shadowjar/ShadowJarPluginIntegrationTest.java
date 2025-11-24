/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.shadowjar;

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.google.common.io.CharStreams;
import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.execution.TaskOutcome;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.maven.MavenArtifact;
import com.palantir.gradle.testing.maven.MavenRepo;
import com.palantir.gradle.testing.project.RootProject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@GradlePluginTests
class ShadowJarPluginIntegrationTest {
    private static final XmlMapper MAPPER = new XmlMapper(new WstxInputFactory(), new WstxOutputFactory());
    private static final String MAVEN_ROOT = "build/repo";

    @BeforeEach
    void setup(RootProject rootProject) {
        rootProject.settingsGradle().rootProjectName("asd-fgh");

        rootProject
                .buildGradle()
                .plugins()
                .add("com.palantir.consistent-versions")
                .add("nebula.maven-nebula-publish")
                .add("com.palantir.shadow-jar");
        rootProject.buildGradle().append("""
            group = 'com.palantir.bar-baz_quux'
            version = '2'

            repositories {
                mavenCentral()
            }

            publishing {
                repositories {
                    maven {
                        name 'testRepo'
                        url "$projectDir/%s"
                    }
                }
            }
            """, MAVEN_ROOT);
    }

    @Test
    void when_using_shadeTransitively_produced_pom_has_only_non_shaded_dependencies(
            GradleInvoker gradle, RootProject project) throws IOException {
        project.buildGradle().append("""
            dependencies {
                api 'org.checkerframework:checker-qual:2.10.0'

                shadeTransitively 'com.google.guava:guava:28.2-jre'
            }
            """);
        project.buildGradle().plugins().add("java-library");

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        String dependenciesText = dependenciesInPom(project);

        assertThat(dependenciesText).doesNotContain("<artifactId>guava</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>error_prone_annotations</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>listenablefuture</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>jsr305</artifactId>");
        assertThat(dependenciesText).contains("<artifactId>checker-qual</artifactId>");

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames)
                .contains(
                        relocatedClass("com/google/j2objc/annotations/Property.class"),
                        relocatedClass("com/google/errorprone/annotations/DoNotCall.class"),
                        relocatedClass("com/google/common/io/ByteSink.class"),
                        relocatedClass("javax/annotation/Nullable.class"));

        assertThat(jarEntryNames).doesNotContain(relocatedClass("org/checkerframework/framework/qual/PolyAll.class"));

        JarFile jarFile = shadowJarFile(project);
        String classFileAsString = CharStreams.toString(new InputStreamReader(
                jarFile.getInputStream(jarFile.getEntry(
                        relocatedClass("com/google/common/util/concurrent/AbstractFuture$Waiter.class"))),
                StandardCharsets.US_ASCII));
        assertThat(classFileAsString).contains("org/checkerframework/checker/nullness/qual/Nullable");
        assertThat(classFileAsString)
                .doesNotContain(relocatedClass("org/checkerframework/checker/nullness/qual/Nullable"));
    }

    @Test
    void should_not_shade_known_logging_implementations(GradleInvoker gradle, RootProject project, MavenRepo repo) {
        repo.publish(MavenArtifact.builder()
                .coordinate("dep-that-depends-on:slf4j-log4j12:1")
                .addDependency("org.slf4j:slf4j-log4j12:1.7.30")
                .build());

        project.buildGradle().withMavenRepo(repo);
        project.buildGradle().append("""
            dependencies {
                // depends on org.slf4j:slf4j-api and log4j:log4j
                shadeTransitively 'dep-that-depends-on:slf4j-log4j12:1'
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        String dependenciesText = dependenciesInPom(project);

        // Only needs slf4j-log4j12, as the other two are depended on by it
        assertThat(dependenciesText).contains("<artifactId>slf4j-log4j12</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>slf4j-api</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>log4j</artifactId>");

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames)
                .doesNotContain(
                        relocatedClass("org/slf4j/LoggerFactory.class"),
                        relocatedClass("org/apache/log4j/MDC.class"),
                        relocatedClass("org/slf4j/impl/Log4jLoggerFactory.class"));
    }

    @Test
    void should_not_shade_tritium_tracing_or_safe_logging(GradleInvoker gradle, RootProject project, MavenRepo repo) {
        repo.publish(MavenArtifact.builder()
                .coordinate("telemetry-dep:telemetry:1")
                .addDependency("com.palantir.tracing:tracing:6.17.0")
                .addDependency("com.palantir.safe-logging:safe-logging:3.2.0")
                .addDependency("com.palantir.tritium:tritium-registry:0.63.0")
                .build());

        project.buildGradle().withMavenRepo(repo);
        project.buildGradle().append("""
            dependencies {
                shadeTransitively 'telemetry-dep:telemetry:1'
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        String dependenciesText = dependenciesInPom(project);

        assertThat(dependenciesText).contains("<artifactId>tritium-registry</artifactId>");
        assertThat(dependenciesText).contains("<artifactId>tracing</artifactId>");
        assertThat(dependenciesText).doesNotContain("<groupId>safe-logging</groupId>"); // tracing contains safe-logging

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames)
                .doesNotContain(
                        relocatedClass("com/palantir/tracing/Tracer.class"),
                        relocatedClass("com/palantir/tritium/metrics/registry/DefaultTaggedMetricRegistry.class"),
                        relocatedClass("com/palantir/logsafe/SafeArg.class"));
    }

    @Test
    void should_support_multi_release_jars(GradleInvoker gradle, RootProject project) throws IOException {
        // https://www.baeldung.com/java-multi-release-jar

        project.buildGradle().append("""
            repositories {
                mavenCentral()
            }

            dependencies {
                shadeTransitively 'one.util:streamex:0.7.3'
            }

            tasks.register('extractForAssertions', Copy){
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("%s/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "$buildDir/extractForAssertions"
            }
            """, MAVEN_ROOT);

        writeHelloWorld(project);
        runTasksAndCheckSuccess(gradle, "extractForAssertions");

        Set<String> jarEntryNames =
                shadowJarFile(project).stream().map(ZipEntry::getName).collect(Collectors.toSet());

        assertThat(jarEntryNames)
                .contains(
                        "META-INF/versions/9/shadow/com/palantir/bar_baz_quux/asd_fgh/"
                                + "one/util/streamex/VerSpec.class",
                        "META-INF/versions/9/shadow/com/palantir/bar_baz_quux/asd_fgh/"
                                + "one/util/streamex/Java9Specific.class");
        assertThat(jarEntryNames)
                .doesNotContain(
                        "META-INF/versions/9/one/util/streamex/VerSpec.class",
                        "META-INF/versions/9/one/util/streamex/Java9Specific.class");

        JarFile shadowJar = shadowJarFile(project);
        assertThat(shadowJar.isMultiRelease())
                .as(
                        "The jar manifest must include 'Multi-Release: true', but was '%s'",
                        project.file("build/extractForAssertions/META-INF/MANIFEST.MF")
                                .text())
                .isTrue();

        // What is of interest here is that this does not throw an exception
        shadowJar.getManifest();
    }

    @Test
    void should_support_service_loader_providers(GradleInvoker gradle, RootProject project) throws IOException {
        project.buildGradle().append("""
            repositories {
                mavenCentral()
            }

            dependencies {
                // The service is not relocated, only the provider, which still must
                // provide ws.rs-api 'jakarta.ws.rs.ext.RuntimeDelegate'
                implementation 'jakarta.ws.rs:jakarta.ws.rs-api:3.1.0'
                shadeTransitively 'org.glassfish.jersey.core:jersey-common:3.1.1'
            }

            tasks.register('extractForAssertions', Copy){
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("%s/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "$buildDir/extractForAssertions"
            }
            """, MAVEN_ROOT);

        writeHelloWorld(project);
        runTasksAndCheckSuccess(gradle, "extractForAssertions");

        Set<String> jarEntryNames =
                shadowJarFile(project).stream().map(ZipEntry::getName).collect(Collectors.toSet());

        String service = "META-INF/services/jakarta.ws.rs.ext.RuntimeDelegate";
        assertThat(jarEntryNames).contains(service);
        project.file("build/extractForAssertions/" + service)
                .assertThat()
                .content()
                .isEqualTo(
                        "shadow.com.palantir.bar_baz_quux.asd_fgh.org.glassfish.jersey.internal.RuntimeDelegateImpl");
        assertThat(jarEntryNames)
                .contains("shadow/com/palantir/bar_baz_quux/asd_fgh/"
                        + "org/glassfish/jersey/internal/RuntimeDelegateImpl.class");
        assertThat(jarEntryNames)
                .doesNotContain("shadow/com/palantir/bar_baz_quux/asd_fgh/jakarta/ws/rs/ext/RuntimeDelegate.class");
    }

    @Test
    void should_support_service_loader_providers_for_relocated_services(GradleInvoker gradle, RootProject project)
            throws IOException {
        project.buildGradle().append("""
            repositories {
                mavenCentral()
            }

            dependencies {
                // The service and provider are both relocated
                shadeTransitively 'jakarta.ws.rs:jakarta.ws.rs-api:3.1.0'
                shadeTransitively 'org.glassfish.jersey.core:jersey-common:3.1.1'
            }

            tasks.register('extractForAssertions', Copy){
                dependsOn publishNebulaPublicationToTestRepoRepository
                from zipTree("%s/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar")
                into "$buildDir/extractForAssertions"
            }
            """, MAVEN_ROOT);

        writeHelloWorld(project);
        runTasksAndCheckSuccess(gradle, "extractForAssertions");

        Set<String> jarEntryNames =
                shadowJarFile(project).stream().map(ZipEntry::getName).collect(Collectors.toSet());

        String service = "META-INF/services/shadow.com.palantir.bar_baz_quux.asd_fgh.jakarta.ws.rs.ext.RuntimeDelegate";
        assertThat(jarEntryNames)
                .doesNotContain(
                        "shadow.com.palantir.bar_baz_quux.asd_fgh.META-INF/services/jakarta.ws.rs.ext.RuntimeDelegate");
        assertThat(jarEntryNames)
                .contains(
                        "META-INF/services/shadow.com.palantir.bar_baz_quux.asd_fgh.jakarta.ws.rs.ext.RuntimeDelegate");
        project.file("build/extractForAssertions/" + service)
                .assertThat()
                .content()
                .isEqualTo(
                        "shadow.com.palantir.bar_baz_quux.asd_fgh.org.glassfish.jersey.internal.RuntimeDelegateImpl");
        assertThat(jarEntryNames)
                .contains("shadow/com/palantir/bar_baz_quux/asd_fgh/"
                        + "org/glassfish/jersey/internal/RuntimeDelegateImpl.class");
    }

    @Test
    void should_shade_known_logging_implementations_iff_it_is_placed_in_shadeTransitively_directly(
            GradleInvoker gradle, RootProject project, MavenRepo repo) {
        repo.publish(MavenArtifact.builder()
                .coordinate("dep-that-depends-on:slf4j-log4j12:1")
                .addDependency("org.slf4j:slf4j-log4j12:1.7.30")
                .build());

        project.buildGradle().withMavenRepo(repo);
        project.buildGradle().append("""
            dependencies {
                // depends on org.slf4j:slf4j-api and log4j:log4j
                shadeTransitively 'dep-that-depends-on:slf4j-log4j12:1'

                // yes, we really want to do this
                shadeTransitively 'org.slf4j:slf4j-log4j12:1.7.30'
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        String dependenciesText = dependenciesInPom(project);

        // We explicitly asked to shade the logging library, so no dependencies
        assertThat(dependenciesText).doesNotContain("<artifactId>slf4j-log4j12</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>slf4j-api</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>log4j</artifactId>");

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames)
                .contains(
                        relocatedClass("org/slf4j/LoggerFactory.class"),
                        relocatedClass("org/apache/log4j/MDC.class"),
                        relocatedClass("org/slf4j/impl/Log4jLoggerFactory.class"));
    }

    @Test
    void should_not_shade_runtimeOnly_dependencies(GradleInvoker gradle, RootProject project, MavenRepo repo) {
        repo.publish(MavenArtifact.builder()
                .coordinate("depends-on:api-guardian:1")
                .addDependency("org.apiguardian:apiguardian-api:1.1.0")
                .build());

        project.buildGradle().withMavenRepo(repo);
        project.buildGradle().append("""
            dependencies {
                runtimeOnly 'org.apiguardian:apiguardian-api:1.1.0'

                shadeTransitively 'depends-on:api-guardian:1'
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        String dependenciesText = dependenciesInPom(project);

        assertThat(dependenciesText).contains("<artifactId>apiguardian-api</artifactId>");
        assertThat(dependenciesText).doesNotContain("<artifactId>api-guardian</artifactId>");

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames).doesNotContain(relocatedClass("org/apiguardian/api/API.class"));
    }

    @Test
    void root_level_module_info_java_should_not_break_stuff(GradleInvoker gradle, RootProject project) {
        project.buildGradle().append("""
            dependencies {
                // This contains a root level module-info.class
                shadeTransitively 'jakarta.ws.rs:jakarta.ws.rs-api:2.1.6'
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        Set<String> jarEntryNames = jarEntryNames(project);

        assertThat(jarEntryNames).contains(relocatedClass("javax/ws/rs/core/Response.class"));
    }

    @Test
    void shadeTransitively_should_be_available_to_test_source_sets(GradleInvoker gradle, RootProject project) {
        project.buildGradle().append("""
            testSets {
                integrationTest
            }

            dependencies {
                shadeTransitively 'com.google.guava:guava:28.2-jre'

                testImplementation 'junit:junit:4.12'
                integrationTestImplementation 'junit:junit:4.12'
            }
            """);
        project.buildGradle().plugins().add("org.unbroken-dome.test-sets");

        project.mainSourceSet().java().writeClass("""
            package pkg;
            import com.google.common.collect.ImmutableList;
            class MainSourceSetClass {
                static void useGuava() { ImmutableList.of(); }
            }
            """);

        project.testSourceSet().java().writeClass("""
            package pkg;
            import org.junit.Test;
            import com.google.common.collect.ImmutableList;
            public class FooTest {
                @Test
                public void use_guava_directly() {
                    ImmutableList.of();
                }

                @Test
                public void use_guava_though_main_source_set() {
                    MainSourceSetClass.useGuava();
                }
            }
            """);

        project.sourceSet("integrationTest").java().writeClass("""
            package pkg;
            import org.junit.Test;
            import com.google.common.collect.ImmutableList;
            public class FooIntegrationTest {
                @Test
                public void use_guava_directly() {
                    ImmutableList.of();
                }

                @Test
                public void use_guava_though_main_source_set() {
                    MainSourceSetClass.useGuava();
                }
            }
            """);

        runTasksAndCheckSuccess(gradle, "test", "integrationTest");
    }

    @Test
    void the_jar_task_should_produce_a_jar_with_a_classifier_of_thin_to_not_clash_with_shadowJar(
            GradleInvoker gradle, RootProject project) {
        project.buildGradle().append("""
            dependencies {
                implementation 'org.apiguardian:apiguardian-api:1.1.0'
            }
            """);

        runTasksAndCheckSuccess(gradle, "jar");

        project.file("build/libs/asd-fgh-2-thin.jar").assertThat().exists();
    }

    @Test
    void shadowJar_should_contain_manifest_entries_added_to_thin_jar_in_tasks(GradleInvoker gradle, RootProject project)
            throws IOException {
        project.buildGradle().append("""
            dependencies {
                shadeTransitively 'org.apiguardian:apiguardian-api:1.1.0'
            }

            tasks.named('jar', Jar).configure { jarTask ->
                jarTask.manifest.attributes('Foo': 'Bar')
            }
            """);

        runTasksAndCheckSuccess(gradle, "publishNebulaPublicationToTestRepoRepository");

        assertThat(shadowJarFile(project).getManifest().getMainAttributes().getValue("Foo"))
                .isEqualTo("Bar");
    }

    @Test
    void rejected_module_uses_right_version_when_specified_as_virtual_platform_in_versions_props(
            GradleInvoker gradle, RootProject project) {
        // This checks that excluding the `rejectedFromShading` configuration from having versions.props
        // constraint injection does not cause the wrong versions to be used for modules rejected from shading when
        // they are part of a virtual platform. More context for my fear:
        // https://github.com/palantir/gradle-consistent-versions/blob/f407769c01dba863147e6108bd12e6990ab2ebed/src/main/java/com/palantir/gradle/versions/VersionsPropsPlugin.java#L260-L283

        project.file("versions.props").append("""
            org.slf4j:* = 1.7.30
            """);

        project.buildGradle().append("""
            dependencies {
                shadeTransitively 'org.slf4j:slf4j-log4j12:1.7.26'
            }

            tasks.register('printRuntimeClasspath') {
                def files = configurations.runtimeClasspath.incoming.files
                doLast {
                    println files.collect { it.name }
                }
            }
            """);

        InvocationResult result = runTasksAndCheckSuccess(gradle, "printRuntimeClasspath");

        assertThat(result).output().contains("slf4j-log4j12-1.7.30.jar");
    }

    @Test
    void checkUnusedConstraints_runs_correctly(GradleInvoker gradle, RootProject project) {
        project.gradlePropertiesFile().appendProperty("ignoreLockFile", "true");
        project.file("versions.props").createEmpty();

        runTasksAndCheckSuccess(gradle, "checkUnusedConstraints");
    }

    @Test
    void shadowJar_task_should_be_cacheable(GradleInvoker gradle, RootProject project) {
        project.buildGradle().append("""
            dependencies {
                shadeTransitively 'com.google.guava:guava:28.2-jre'
            }
            """);

        writeHelloWorld(project);

        // First run with build cache enabled
        runTasksAndCheckSuccess(gradle, "--build-cache", "shadowJar");

        // Clean and run again - shadowJar should be loaded from cache
        runTasksAndCheckSuccess(gradle, "clean");
        InvocationResult rerun = runTasksAndCheckSuccess(gradle, "--build-cache", "shadowJar");

        assertThat(rerun).task(":shadowJar").outcome().isEqualTo(TaskOutcome.FROM_CACHE);
    }

    private Set<String> jarEntryNames(RootProject project) {
        JarFile shadowJar = shadowJarFile(project);
        return shadowJar.stream().map(ZipEntry::getName).collect(Collectors.toSet());
    }

    private JarFile shadowJarFile(RootProject project) {
        Path jarPath = project.path().resolve(MAVEN_ROOT + "/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.jar");
        try {
            return new JarFile(jarPath.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open shadow jar file: " + jarPath, e);
        }
    }

    private String dependenciesInPom(RootProject project) {
        Path pomFile = project.path().resolve(MAVEN_ROOT + "/com/palantir/bar-baz_quux/asd-fgh/2/asd-fgh-2.pom");

        PomProject pom;
        try {
            pom = MAPPER.readValue(pomFile.toFile(), PomProject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse POM file: " + pomFile, e);
        }

        List<Dependency> deps = Optional.ofNullable(pom)
                .map(PomProject::dependencies)
                .map(Dependencies::dependency)
                .orElseGet(Collections::emptyList);

        return deps.stream()
                .map(dep -> {
                    try {
                        return MAPPER.writeValueAsString(dep);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to serialize dependency: " + dep.artifactId(), e);
                    }
                })
                .collect(Collectors.joining("\n"));
    }

    private String relocatedClass(String clazz) {
        return "shadow/com/palantir/bar_baz_quux/asd_fgh/" + clazz;
    }

    private InvocationResult runTasksAndCheckSuccess(GradleInvoker gradle, String... args) {
        String[] allArgs = Stream.concat(Stream.of("--warning-mode=none", "writeVersionsLock"), Arrays.stream(args))
                .toArray(String[]::new);
        InvocationResult executionResult = gradle.withArgs(allArgs).buildsSuccessfully();
        System.out.println(executionResult.output());
        return executionResult;
    }

    private void writeHelloWorld(RootProject project) {
        project.mainSourceSet().java().writeClass("""
            package pkg;
            public class HelloWorld {
                public static void main(String[] args) {
                    System.out.println("Hello World");
                }
            }
            """);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PomProject(
            @JacksonXmlProperty(localName = "dependencies") Dependencies dependencies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Dependencies(
            @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "dependency")
            List<Dependency> dependency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Dependency(String artifactId) {}
}
