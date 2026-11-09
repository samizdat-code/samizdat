#!/usr/bin/env bash
# Build samizdat-bundle.tgz from a commit (default HEAD), for agent.py to upload.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
rev=${1:-HEAD}
ctx=$(mktemp -d)
trap 'rm -rf "$ctx"' EXIT
mkdir "$ctx/repo"
git -C "$repo" archive "$rev" | tar -x -C "$ctx/repo"
python3 "$here/open_shell.py" "$ctx/repo"
docker build --platform linux/amd64 -t samizdat-bundle -f "$here/bundle.Dockerfile" "$ctx"
docker run --rm --platform linux/amd64 samizdat-bundle tar -czf - -C / opt/samizdat > "$here/samizdat-bundle.tgz.tmp"
# Renamed into place: a trial starting during a build uploads the old file whole.
mv -f "$here/samizdat-bundle.tgz.tmp" "$here/samizdat-bundle.tgz"
git -C "$repo" rev-parse "$rev" > "$here/samizdat-bundle.rev"
ls -lh "$here/samizdat-bundle.tgz"
