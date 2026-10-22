#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ( "$1" != structurize && "$1" != multipiston ) ]]; then
    echo "Usage: bash .github/scripts/provision-local-deps.sh {structurize|multipiston}" >&2
    exit 2
fi
mode=$1
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
mkdir -p "$project_dir/build"
deps_dir=$(mktemp -d "$project_dir/build/ci-local-deps.XXXXXXXX")
maven_repo="$deps_dir/maven"
init_script="$project_dir/.github/scripts/neoform-setup.init.gradle"

export CI=true
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xmx1g -XX:ActiveProcessorCount=2 -Djava.util.concurrent.ForkJoinPool.common.parallelism=2"
gradle_args=(--max-workers=2 --no-parallel --no-daemon
    '-Dorg.gradle.jvmargs=-Xmx1g -XX:ActiveProcessorCount=2'
    "-Dmaven.repo.local=$maven_repo")

run_gradle() {
    local checkout=$1
    shift
    timeout 420 "$checkout/gradlew" -p "$checkout" "${gradle_args[@]}" "$@"
}

checkout_source() {
    local name=$1 sha=$2 checkout="$deps_dir/$1"
    git init -q "$checkout"
    git -C "$checkout" remote add origin "https://github.com/MrFruitDude/$name.git"
    timeout 120 git -C "$checkout" fetch -q --depth=1 origin "$sha"
    timeout 120 git -C "$checkout" checkout -q --detach FETCH_HEAD
    [[ $(git -C "$checkout" rev-parse HEAD) == "$sha" ]]
    printf '%s %s\n' "$name" "$sha" | tee -a "$deps_dir/sources.txt"
}

# Reviewed security fixes on the MC 26.3 port branches. Keep local versions.
checkout_source BlockUI 9f3f9c845d8314f70a5bb9447d96f2c01f740ce3
run_gradle "$deps_dir/BlockUI" publishToMavenLocal \
    --init-script "$init_script" \
    -Pneogradle.subsystems.tools.installerTools=net.neoforged.installertools:installertools:4.0.16:fatjar

checkout_source Domum-Ornamentum c4d29b0e044fe40c9d982ee02498fffca27e1de3
run_gradle "$deps_dir/Domum-Ornamentum" publishToMavenLocal \
    --init-script "$init_script" \
    -Pneogradle.subsystems.tools.installerTools=net.neoforged.installertools:installertools:4.0.16:fatjar

test -s "$maven_repo/com/ldtteam/blockui/0.0.1-local/blockui-0.0.1-local.pom"
test -s "$maven_repo/com/ldtteam/domum-ornamentum/1.0.0-local/domum-ornamentum-1.0.0-local.pom"

if [[ "$mode" == multipiston ]]; then
    # Parent-reviewed PR2 revision, including its NeoForm setup fix.
    checkout_source Structurize c880e86f79f9d4b843fee2b350f0ce63fb2e41a9
    run_gradle "$deps_dir/Structurize" publishToMavenLocal
    test -s "$maven_repo/com/ldtteam/structurize/0.0.1-local/structurize-0.0.1-local.pom"
fi

printf 'Local dependency Maven repository: %s\n' "$maven_repo"
printf 'export LOCAL_DEPS_MAVEN=%q\n' "$maven_repo" > "$project_dir/build/ci-local-deps.env"
if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'LOCAL_DEPS_MAVEN=%s\n' "$maven_repo" >> "$GITHUB_ENV"
fi
