#!/usr/bin/env bash
set -euo pipefail

PRIMARY=synanton-replica-parity-primary
REPLICA=synanton-replica-parity-replica
NETWORK=synanton-replica-parity-net
IMAGE=postgres:16-alpine
PRIMARY_PORT=55432
REPLICA_PORT=55433
SCHEMA="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/java/content-cache-postgres/src/main/resources/org/synanton/contentcache/postgres/schema.sql"

log() { printf '[replica-parity] %s\n' "$*"; }
die() { printf '[replica-parity] ERROR: %s\n' "$*" >&2; exit 1; }

wait_ready() {
  local name=$1
  for _ in $(seq 1 60); do
    if docker exec "$name" pg_isready -U postgres -d synanton >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker logs "$name" >&2 || true
  die "$name did not become ready"
}

up() {
  down
  docker network create "$NETWORK" >/dev/null
  log "starting primary"
  docker run -d --name "$PRIMARY" --network "$NETWORK" \
    -p "$PRIMARY_PORT:5432" \
    -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=synanton -e POSTGRES_HOST_AUTH_METHOD=trust \
    "$IMAGE" \
    -c wal_level=replica -c max_wal_senders=10 -c max_replication_slots=10 \
    -c hot_standby=on >/dev/null
  wait_ready "$PRIMARY"
  docker exec "$PRIMARY" psql -U postgres -d synanton -v ON_ERROR_STOP=1 \
    -c "CREATE ROLE replicator REPLICATION LOGIN PASSWORD 'replicator';" >/dev/null
  docker exec "$PRIMARY" bash -c 'printf "\nhost replication all all trust\n" >> "$PGDATA/pg_hba.conf"'
  docker exec "$PRIMARY" psql -U postgres -d synanton -c "SELECT pg_reload_conf();" >/dev/null
  docker cp "$SCHEMA" "$PRIMARY:/tmp/content-cache-schema.sql"
  docker exec "$PRIMARY" psql -U postgres -d synanton -v ON_ERROR_STOP=1 \
    -f /tmp/content-cache-schema.sql >/dev/null
  docker exec "$PRIMARY" psql -U postgres -d synanton -v ON_ERROR_STOP=1 \
    -c "CREATE ROLE app LOGIN PASSWORD 'app';" \
    -c "GRANT USAGE ON SCHEMA public TO app;" \
    -c "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app;" \
    -c "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app;" >/dev/null

  log "starting streaming replica"
  docker run -d --name "$REPLICA" --network "$NETWORK" \
    -p "$REPLICA_PORT:5432" \
    -e PGPASSWORD=replicator --user postgres \
    --entrypoint bash "$IMAGE" -c '
      rm -rf /var/lib/postgresql/data/*
      until pg_basebackup -h '"$PRIMARY"' -D /var/lib/postgresql/data \
        -U replicator -Fp -Xs -P -R; do sleep 1; done
      chmod 700 /var/lib/postgresql/data
      exec postgres
    ' >/dev/null
  wait_ready "$REPLICA"

  for _ in $(seq 1 60); do
    recovery=$(docker exec "$REPLICA" psql -U postgres -d synanton -tAc "SELECT pg_is_in_recovery();" 2>/dev/null | tr -d '[:space:]')
    streaming=$(docker exec "$PRIMARY" psql -U postgres -d synanton -tAc \
      "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND (replay_lag IS NULL OR replay_lag < interval '1 second');" 2>/dev/null | tr -d '[:space:]')
    if [ "$recovery" = "t" ] && [ "$streaming" = "1" ]; then
      log "streaming and caught up (replay_lag < 1s)"
      printf 'export REPLICA_PARITY_PRIMARY_URL=jdbc:postgresql://localhost:%s/synanton\n' "$PRIMARY_PORT"
      printf 'export REPLICA_PARITY_REPLICA_URL=jdbc:postgresql://localhost:%s/synanton\n' "$REPLICA_PORT"
      printf 'export REPLICA_PARITY_PRIMARY_USER=app\nexport REPLICA_PARITY_PRIMARY_PASSWORD=app\n'
      printf 'export REPLICA_PARITY_REPLICA_USER=app\nexport REPLICA_PARITY_REPLICA_PASSWORD=app\n'
      return 0
    fi
    sleep 1
  done
  docker logs "$PRIMARY" >&2 || true
  docker logs "$REPLICA" >&2 || true
  die "replica did not reach streaming/caught-up state"
}

down() {
  docker rm -f "$PRIMARY" "$REPLICA" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) die "usage: $0 up|down" ;;
esac
