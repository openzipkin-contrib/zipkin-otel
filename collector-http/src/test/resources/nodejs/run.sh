echo 'Installing the packages...' 1>&2
npm install --no-audit --no-fund
echo 'Run index.js' 1>&2
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_SERVICE_NAME=opentelemetry-nodejs-openai
export OTEL_NODE_RESOURCE_DETECTORS=env
node -r @elastic/opentelemetry-node --no-deprecation index.js