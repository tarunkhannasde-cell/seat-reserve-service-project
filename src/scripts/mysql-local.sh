#!/usr/bin/env bash
# Dedicated local MySQL server for this project: own datadir, own port (3307), --no-defaults so a
# global my.cnf or any other MySQL install on the machine is never touched.
#
#   ./scripts/mysql-local.sh init     # first time: create datadir, start, create DBs + user
#   ./scripts/mysql-local.sh start | stop | status | shell
set -euo pipefail

find_mysql_home() {
  local c
  # 1. explicit override
  if [[ -n "${MYSQL_HOME:-}" && -x "$MYSQL_HOME/bin/mysqld" ]]; then echo "$MYSQL_HOME"; return; fi
  # 2. Homebrew formulas (Apple Silicon, then Intel), preferred version first
  for c in /opt/homebrew/opt/mysql@8.4 /opt/homebrew/opt/mysql /opt/homebrew/opt/mysql@8.0 \
           /usr/local/opt/mysql@8.4 /usr/local/opt/mysql /usr/local/opt/mysql@8.0 \
           /usr/local/mysql; do
    if [[ -x "$c/bin/mysqld" ]]; then echo "$c"; return; fi
  done
  # 3. whatever is on PATH
  if command -v mysqld >/dev/null 2>&1; then
    dirname "$(dirname "$(command -v mysqld)")"; return
  fi
  return 1
}

if ! MYSQL_HOME=$(find_mysql_home); then
  echo "mysqld not found. Install it with:  brew install mysql@8.4" >&2
  echo "or set MYSQL_HOME to a MySQL install directory." >&2
  exit 1
fi

BIN=$MYSQL_HOME/bin
DATA=${SEATS_MYSQL_DATA:-$HOME/.local/seat-booking-mysql}
PORT=${SEATS_MYSQL_PORT:-3307}
SOCK=$DATA/mysqld.sock
CLIENT=("$BIN/mysql" --no-defaults -uroot -h127.0.0.1 -P"$PORT")

echo "using $("$BIN/mysqld" --version | head -1)" >&2

ping_db() { "$BIN/mysqladmin" --no-defaults -uroot -h127.0.0.1 -P"$PORT" ping >/dev/null 2>&1; }

start() {
  if ping_db; then
    echo "already running on 127.0.0.1:$PORT"; return
  fi
  if [[ ! -d "$DATA/mysql" ]]; then
    echo "datadir not initialised; run: $0 init" >&2; exit 1
  fi
  "$BIN/mysqld" --no-defaults \
    --datadir="$DATA" --port="$PORT" --bind-address=127.0.0.1 --socket="$SOCK" \
    --pid-file="$DATA/mysqld.pid" --log-error="$DATA/mysqld.err" \
    --mysqlx=OFF \
    --max-connections=500 \
    --transaction-isolation=READ-COMMITTED \
    --innodb-lock-wait-timeout=50 \
    --innodb-buffer-pool-size=512M \
    --daemonize >/dev/null
  for _ in $(seq 1 30); do
    if ping_db; then echo "started on 127.0.0.1:$PORT (data: $DATA)"; return; fi
    sleep 1
  done
  echo "mysqld did not come up; see $DATA/mysqld.err" >&2; exit 1
}

case "${1:-}" in
  init)
    if [[ -d "$DATA/mysql" ]]; then echo "already initialised at $DATA"; else
      mkdir -p "$DATA"
      "$BIN/mysqld" --no-defaults --initialize-insecure --datadir="$DATA" --log-error="$DATA/mysqld.err"
      echo "initialised $DATA"
    fi
    start
    "${CLIENT[@]}" <<'SQL'
CREATE DATABASE IF NOT EXISTS seats      CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS seats_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'seats'@'localhost' IDENTIFIED BY 'seats';
CREATE USER IF NOT EXISTS 'seats'@'127.0.0.1' IDENTIFIED BY 'seats';
GRANT ALL ON seats.*      TO 'seats'@'localhost', 'seats'@'127.0.0.1';
GRANT ALL ON seats_test.* TO 'seats'@'localhost', 'seats'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
    echo "databases seats, seats_test and user seats/seats ready"
    ;;
  start) start ;;
  stop)
    "$BIN/mysqladmin" --no-defaults -uroot -h127.0.0.1 -P"$PORT" shutdown && echo stopped ;;
  status)
    "$BIN/mysqladmin" --no-defaults -uroot -h127.0.0.1 -P"$PORT" status ;;
  shell)
    exec "${CLIENT[@]}" "${@:2}" ;;
  *) echo "usage: $0 init|start|stop|status|shell" >&2; exit 2 ;;
esac