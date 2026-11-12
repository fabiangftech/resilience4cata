#!/usr/bin/env bash
# Installs resilience4cata the way a user does, from its published artifacts, and checks that it works: the POMs,
# the Gradle module metadata, the BOM, every module-info and the processor, none of which the build's own tests see
# (they use project dependencies).
#
#   consumer/check.sh [--staged] [gradle args...]
#
# 1. Publishes every module to build/central-staging (the directory centralBundle zips), unless --staged: then it
#    checks what is already there, as release.yml does with the bundle it is about to upload.
# 2. Every published artifact is in the BOM, and every artifact in the BOM is published.
# 3. Gradle (consumer/gradle): the application in consumer/app, on the class path and as a named module, compiled with
#    -Xlint:all -Werror and run; and every artifact of the BOM resolves with its dependencies.
# 4. Maven (consumer/maven): the same application on the class path, compiled and run; and every artifact of the BOM
#    resolves with its dependencies.
#
# Gradle arguments (for example -Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64) go to every Gradle run.
# Maven uses its own local repository, build/consumer/m2, so artifacts from an earlier run or from
# publishToMavenLocal never stand in for the ones under test.
set -euo pipefail

cd "$(dirname "$0")/.."
root=$PWD
staged=false
if [ "${1:-}" = --staged ]; then
  staged=true
  shift
fi

version=$(sed -n 's/^version=//p' gradle.properties)
for arg in "$@"; do
  case $arg in -Pversion=*) version=${arg#-Pversion=} ;; esac
done
repository=$root/build/central-staging
group=$repository/dev/resilience4cata
work=$root/build/consumer

if [ "$staged" = false ]; then
  echo "== Publishing $version to $repository"
  ./gradlew centralStagingClean publishMavenJavaPublicationToCentralStagingRepository --console=plain -q "$@"
fi
[ -d "$group" ] || { echo "error: nothing published in $group" >&2; exit 1; }

echo "== The BOM lists every published artifact"
bom=$(find "$group/resilience4cata-bom/$version" -name '*.pom' | head -1)
published=$(find "$group" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | grep -v '^resilience4cata-bom$' | sort)
listed=$(grep -o '<artifactId>resilience4cata-[a-z-]*</artifactId>' "$bom" | sed 's/<[^>]*>//g' \
  | grep -v '^resilience4cata-bom$' | sort -u)
if [ "$published" != "$listed" ]; then
  echo "error: published and listed in the BOM differ (< published only, > BOM only):" >&2
  diff <(echo "$published") <(echo "$listed") >&2 || true
  exit 1
fi
artifacts=$(echo "$listed" | paste -sd, -)
echo "$(echo "$listed" | wc -l | tr -d ' ') artifacts"

echo "== Gradle: class path, module path, every artifact"
./gradlew -p consumer/gradle --console=plain -q \
  "-Pr4cRepository=file://$repository" "-Pr4cVersion=$version" "-Pr4cArtifacts=$artifacts" \
  :classpath:run :modulepath:run resolveEverything "$@"

echo "== Maven: class path, every artifact"
m2=$work/m2
rm -rf "$m2/dev/resilience4cata"
maven=(mvn -B -q -ntp "-Dmaven.repo.local=$m2" "-Dr4c.repository=file://$repository" "-Dr4c.version=$version")
"${maven[@]}" -f consumer/maven/pom.xml clean compile exec:java

everything=$work/maven-everything
mkdir -p "$everything"
{
  cat <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>everything</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <repositories>
    <repository><id>r4c-published</id><url>\${r4c.repository}</url><snapshots><enabled>true</enabled></snapshots></repository>
  </repositories>
  <dependencyManagement><dependencies><dependency>
    <groupId>dev.resilience4cata</groupId><artifactId>resilience4cata-bom</artifactId><version>\${r4c.version}</version>
    <type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>
  <dependencies>
POM
  for artifact in $listed; do
    echo "    <dependency><groupId>dev.resilience4cata</groupId><artifactId>$artifact</artifactId></dependency>"
  done
  echo '  </dependencies>'
  echo '</project>'
} > "$everything/pom.xml"
"${maven[@]}" -f "$everything/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve
echo "resolved every artifact in the BOM with Maven"

echo "== consumer check passed for $version"
