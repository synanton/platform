#!/usr/bin/env bash
# kafka-init.sh - creates the three ingestion topics on first run.
# Runs as a Docker Compose init container after Kafka is healthy.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-4}"
RF="${KAFKA_REPLICATION_FACTOR:-1}"

wait_for_kafka() {
  local retries=30
  echo "Waiting for Kafka at ${BOOTSTRAP}..."
  until kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --list &>/dev/null; do
    retries=$((retries - 1))
    if [ "$retries" -le 0 ]; then
      echo "Kafka not available after 30 retries, giving up."
      exit 1
    fi
    sleep 2
  done
  echo "Kafka is ready."
}

create_topic() {
  local topic="$1"
  if kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --describe --topic "${topic}" &>/dev/null; then
    echo "Topic ${topic} already exists, skipping."
  else
    kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" \
      --create --topic "${topic}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${RF}"
    echo "Created topic: ${topic}"
  fi
}

wait_for_kafka
create_topic ingestion_requests
create_topic ingestion_events
create_topic ingestion_completed
create_topic topology_events

echo "Kafka topics initialised."
