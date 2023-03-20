<p align="right">
<a href="https://autorelease.general.dmz.palantir.tech/palantir/gradle-shadow-jar"><img src="https://img.shields.io/badge/Perform%20an-Autorelease-success.svg" alt="Autorelease"></a>
</p>

# Gradle shadow jar ![Bintray](https://img.shields.io/bintray/v/palantir/releases/gradle-shadow-jar.svg) [![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](https://opensource.org/licenses/Apache-2.0)


This is a Gradle plugin that wraps the existing [`shadow`](https://github.com/johnrengelman/shadow) Gradle plugin to
make producing *partially shaded jars* much easier. This means you can shade just one of your dependencies in a library or Gradle plugin to avoid dependency clashes. It is possible to produce fully shaded jars with this plugin, but it is not the aim of this plugin, and perf may be bad for shading a large dependency tree.

## Applying the plugin

To apply this plugin, *build.gradle* should look something like:

```diff
 buildscript {
     repositories {
         maven { url 'https://dl.bintray.com/palantir/releases' }
     }
 
     dependencies {
         classpath 'com.palantir.gradle.consistentversions:gradle-consistent-versions:<version>'
+        classpath 'com.palantir.gradle.shadow-jar:gradle-shadow-jar:<version>'
     }
     
     apply plugin: 'com.palantir.consistent-versions'
+    apply plugin: 'com.palantir.shadow-jar'
 }
```

*Requires [`gradle-consistent-versions`](https://github.com/palantir/gradle-consistent-versions) and Gradle 6 to work.*

## Producing shaded JARs

Shading is where you copy the class files of another jar into your jar, and then change the package names
of the classes from the original jar. This removes dependencies from your project's publication and can reduce
dependency conflicts at the expense of increased jar size and build time.

To use, identify which of your dependencies you want shaded and put them in the `shadeTransitively` configuration like so:

```gradle
dependencies {
    implementation 'some-unshaded:dependency'

    shadeTransitively 'com.google.guava:guava'
}
```

The dependency you list and all its dependencies will be shaded *unless* one of these dependencies exists in other
standard java configurations. For example, [`com.google.guava:guava:28.2-jre`](https://mvnrepository.com/artifact/com.google.guava/guava/28.2-jre)
depends on [`'com.google.code.findbugs:jsr305:3.0.2'`](https://mvnrepository.com/artifact/com.google.code.findbugs/jsr305/3.0.2).
If you use the following:

```gradle
dependencies {
    implementation 'com.google.code.findbugs:jsr305'

    shadeTransitively 'com.google.guava:guava'
}
```

will shade `guava` and all its dependencies except for `jsr305` (and its dependencies), which will not be shaded and
appear in the maven POM.

We explicitly ban logging libraries from shading, as they can cause problems when shaded, and will show up as real
dependencies in your POM, even if they were brought in as transitives through `shadeTransitively`.

We also ban tracing and metric libraries, as they might rely on static variables (see `com.palantir.tracing.Tracer`
and `com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry`).
