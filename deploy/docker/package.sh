#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="$root/deploy/docker/dist"
lib="$dist/lib"

rm -rf "$dist"
mkdir -p "$lib"

cd "$root"
classpath="$(sbt -batch -error "export examples/Runtime/fullClasspath")"
IFS=':' read -r -a jars <<< "$classpath"
if [[ ${#jars[@]} -lt 2 ]]; then
  echo "sbt export 没有产出可用 classpath" >&2
  exit 1
fi

copied=0
for jar in "${jars[@]}"; do
  if [[ -f "$jar" ]]; then
    cp "$jar" "$lib/"
    copied=$((copied + 1))
  fi
done

if [[ "$copied" -lt 2 ]]; then
  echo "没有复制到运行所需 JAR" >&2
  exit 1
fi

cat > "$dist/run.sh" <<'EOF'
#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec java ${JAVA_OPTS:-} -cp "lib/*" com.zyblw.agent.examples.production.ProductionSupportHost "$@"
EOF
chmod +x "$dist/run.sh"
echo "已打包 $copied 个 JAR 到 $lib"
