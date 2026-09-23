#!/bin/zsh
set -eu
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
node_bin="$(command -v node)"
"$node_bin" "$project_dir/scripts/preview-service.mjs" stop "$project_dir"
