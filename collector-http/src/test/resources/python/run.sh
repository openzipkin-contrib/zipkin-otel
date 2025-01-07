#!/bin/bash
echo 'Installing the packages...' 1>&2
pip install -r requirements.txt -q --root-user-action ignore
echo 'Run main.py' 1>&2
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_PYTHON_LOGGING_AUTO_INSTRUMENTATION_ENABLED=true
export OTEL_SERVICE_NAME=opentelemetry-python-openai
opentelemetry-instrument python main.py