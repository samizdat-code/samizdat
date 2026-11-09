#!/usr/bin/env bash
# Build <name>.tgz (default samizdat-bundle) from a commit (default HEAD), for
# samizdat_agent.py to upload; a job picks a non-default one with the agent's
# `bundle` kwarg, so a running job's bundle is never rebuilt under it.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
rev=${1:-HEAD}
name=${2:-samizdat-bundle}
ctx=$(mktemp -d)
trap 'rm -rf "$ctx"' EXIT
mkdir "$ctx/repo"
git -C "$repo" archive "$rev" | tar -x -C "$ctx/repo"
python3 "$here/open_shell.py" "$ctx/repo"
docker build --platform linux/amd64 -t samizdat-bundle -f "$here/bundle.Dockerfile" "$ctx"
docker run --rm --platform linux/amd64 samizdat-bundle tar -czf - -C / opt/samizdat > "$here/$name.tgz.tmp"
# Renamed into place: a trial starting during a build uploads the old file whole.
mv -f "$here/$name.tgz.tmp" "$here/$name.tgz"
git -C "$repo" rev-parse "$rev" > "$here/$name.rev"
ls -lh "$here/$name.tgz"
