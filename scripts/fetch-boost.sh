#!/usr/bin/env bash
# Downloads the Boost release archive into .deps/boost/, verifies its SHA-256 and unpacks the
# headers and the license (librime needs no compiled Boost library).
#
# Usage: scripts/fetch-boost.sh
# Side effects: creates .deps/boost/boost_1_92_0.tar.gz and .deps/boost/boost_1_92_0/{boost,
#   LICENSE_1_0.txt}. Nothing else is written; an existing verified archive is reused (offline).
# Notes: the version and hash are the ones build-lock.json records; the hash is Boost's own,
#   published next to the archive as boost_1_92_0.tar.gz.json.
set -euo pipefail

version=1.92.0
name=boost_1_92_0
sha256=c4a3b310ddd2472416e091067166b0713be97c63f38c212c484ada022fd296ce
url="https://archives.boost.io/release/${version}/source/${name}.tar.gz"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="${root}/.deps/boost"
archive="${dir}/${name}.tar.gz"
mkdir -p "${dir}"

verify() {
  local actual
  actual="$(sha256sum "${archive}" | cut -d' ' -f1)"
  [[ "${actual}" == "${sha256}" ]]
}

if [[ ! -f "${archive}" ]] || ! verify; then
  curl --fail --location --retry 3 --output "${archive}.part" "${url}"
  mv "${archive}.part" "${archive}"
  if ! verify; then
    echo "error: ${archive} does not match SHA-256 ${sha256}" >&2
    exit 1
  fi
fi

if [[ ! -f "${dir}/${name}/boost/version.hpp" ]]; then
  tar -xzf "${archive}" -C "${dir}" "${name}/boost" "${name}/LICENSE_1_0.txt"
fi
echo "Boost ${version} headers: ${dir}/${name}"
