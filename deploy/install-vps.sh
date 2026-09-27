#!/usr/bin/env bash
# =============================================================================
#  MCS - Bootstrap du VPS central (Ubuntu 24.04)
#
#  Installe et configure : Java 25 (JDK), Maven, PostgreSQL, rathole (serveur),
#  mcs-api, Velocity + proxymanager, lobby Paper + MCSLobbyPlugin + LuckPerms.
#  Les plugins et l'API sont compilés ici, depuis le code du dépôt GitHub.
#
#  Normalement lancé par la commande "mcs-deploy" (voir README).
#  Ne contient rien de propre à une machine ou à une personne : les machines
#  volontaires se connectent ensuite avec "sudo mcs-add-node".
#
#  Config (optionnelle) : /etc/mcs/mcs.env    Secrets : /etc/mcs/secrets.env
#
#  Étapes : system java postgres rathole api velocity lobby firewall bootstrap summary
# =============================================================================
set -euo pipefail

ENV_FILE="${MCS_ENV_FILE:-/etc/mcs/mcs.env}"
SRC_DIR="${MCS_SRC_DIR:-/opt/mcs-deploy}"
JARS_DIR="${MCS_JARS_DIR:-/var/lib/mcs/jars}"

ETC_DIR="/etc/mcs"
SECRETS_FILE="$ETC_DIR/secrets.env"


MC_USER="minecraft"
API_USER="mcs"
MC_ROOT="/opt/minecraft"
VELOCITY_DIR="$MC_ROOT/velocity"
LOBBY_DIR="$MC_ROOT/lobby"
API_DIR="/opt/mcs-api"
FILL_API="https://fill.papermc.io/v3/projects"
RCON_PORT=25575
MAVEN_VERSION=3.9.11
MAVEN_HOME=/opt/maven
M2_REPO=/var/cache/mcs/m2

ALL_STEPS=(system java postgres rathole api velocity lobby firewall bootstrap summary)

# ================================================================ helpers ====
C_STEP='\033[1;36m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OFF='\033[0m'
step() { echo -e "\n${C_STEP}==> $*${C_OFF}"; }
ok()   { echo -e "${C_OK}    ✔ $*${C_OFF}"; }
warn() { echo -e "${C_WARN}    ! $*${C_OFF}"; }
die()  { echo -e "${C_ERR}    ✘ $*${C_OFF}" >&2; exit 1; }

gen_secret() { openssl rand -hex 24; }

# Écrit ou met à jour KEY=VALUE dans secrets.env, et dans la variable du script
save_secret() {
  local k=$1 v=$2
  touch "$SECRETS_FILE"; chmod 600 "$SECRETS_FILE"
  if grep -q "^${k}=" "$SECRETS_FILE"; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$SECRETS_FILE"
  else
    echo "${k}=${v}" >> "$SECRETS_FILE"
  fi
  printf -v "$k" '%s' "$v"
}

# Modifie une clé d'un fichier style server.properties (ou l'ajoute)
set_prop() {
  local f=$1 k=$2 v=$3
  touch "$f"
  if grep -q "^${k}=" "$f"; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$f"
  else
    echo "${k}=${v}" >> "$f"
  fi
}

write_unit() { cat > "/etc/systemd/system/$1.service"; }

restart_service() {
  systemctl daemon-reload
  systemctl enable "$1" >/dev/null 2>&1
  systemctl restart "$1"
}

wait_http() {
  local url=$1 timeout=${2:-120} i
  for ((i = 0; i < timeout; i += 2)); do
    curl -fsS -o /dev/null "$url" 2>/dev/null && return 0
    sleep 2
  done
  return 1
}

wait_port() {
  local port=$1 timeout=${2:-120} i
  for ((i = 0; i < timeout; i += 2)); do
    ss -ltn "sport = :$port" | grep -q LISTEN && return 0
    sleep 2
  done
  return 1
}

java_bin() {
  local j
  for j in /usr/lib/jvm/*25*/bin/javac; do
    [[ -x "$j" ]] && { echo "${j%c}"; return 0; }
  done
  return 1
}

need_java() {
  JAVA_BIN=$(java_bin) || die "JDK 25 absent : lance d'abord l'étape 'java'"
  JAVA_HOME_DIR=$(dirname "$(dirname "$JAVA_BIN")")
}

need_maven() {
  [[ -x "$MAVEN_HOME/bin/mvn" ]] && return 0
  echo "    Installation de Maven $MAVEN_VERSION"
  local tmp
  tmp=$(mktemp -d)
  download "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" "$tmp/maven.tgz"
  rm -rf "$MAVEN_HOME"
  mkdir -p "$MAVEN_HOME"
  tar -xzf "$tmp/maven.tgz" -C "$MAVEN_HOME" --strip-components=1
  rm -rf "$tmp"
}

# build_jar <dossier du projet dans le dépôt> <jar de destination>
build_jar() {
  local name=$1 dest=$2 src="$SRC_DIR/$1" jar
  if [[ "${MCS_SKIP_BUILD:-0}" == "1" && -f "$dest" ]]; then
    ok "Compilation ignorée, réutilisation de $(basename "$dest")"
    return
  fi
  [[ -f "$src/pom.xml" ]] || die "Code introuvable : $src/pom.xml (le dossier '$name' est-il sur GitHub ?)"
  need_java
  need_maven
  echo "    Compilation de $name..."
  JAVA_HOME="$JAVA_HOME_DIR" MAVEN_OPTS="-Xmx768m" "$MAVEN_HOME/bin/mvn" -q -B \
    -f "$src/pom.xml" -Dmaven.repo.local="$M2_REPO" -DskipTests clean package \
    || die "Échec de compilation de $name (erreurs ci-dessus)"
  jar=$(find "$src/target" -maxdepth 1 -name '*.jar' ! -name 'original-*' ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' -printf '%T@ %p\n' | sort -rn | awk 'NR == 1 { print $2 }')
  [[ -n "$jar" ]] || die "Aucun jar produit dans $src/target"
  mkdir -p "$(dirname "$dest")"
  cp -f "$jar" "$dest"
  ok "$name compilé ($(basename "$jar"))"
}

download() {
  curl -fSL --retry 3 -A "$USER_AGENT" -o "$2.part" "$1" && mv -f "$2.part" "$2"
}

# URL du jar le plus récent d'un projet PaperMC (build STABLE en priorité)
papermc_url() {
  local project=$1 version=$2
  if [[ "$version" == "latest" ]]; then
    version=$(curl -fsSL -A "$USER_AGENT" "$FILL_API/$project" | jq -r '.versions | to_entries[0].value[0]')
  fi
  echo "    $project $version" >&2
  curl -fsSL -A "$USER_AGENT" "$FILL_API/$project/versions/$version/builds" \
    | jq -r '(map(select(.channel == "STABLE")) + .) | .[0].downloads["server:default"].url // empty'
}

# ============================================================== config =======
load_config() {
  [[ $EUID -eq 0 ]] || die "Lance ce script en root : sudo bash install-vps.sh"
  if [[ -f "$ENV_FILE" ]]; then
    sed -i 's/\r$//' "$ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi

  : "${TIMEZONE:=America/Toronto}" "${USER_AGENT:=mcs-deploy/0.3 (+https://github.com/Vinchenzo06/mcs-deploy)}"
  : "${PAPER_VERSION:=26.1.2}" "${VELOCITY_VERSION:=latest}" "${FORCE_UPDATE:=false}"
  : "${API_XMX:=384M}" "${VELOCITY_XMX:=512M}" "${LOBBY_XMX:=512M}"
  : "${PORT_START:=25600}" "${PORT_END:=25620}"
  : "${ENABLE_SSH_TUNNEL:=true}" "${SSH_TUNNEL_PORT:=2222}"
  : "${SRC_API:=api}" "${SRC_PROXY:=proxymanager}" "${SRC_LOBBY:=lobby-plugin}"
  : "${QUOTA_DEFAULT:=1 6144 2}" "${QUOTA_VIP:=2 12288 4}"
  : "${QUOTA_PREMIUM:=2 16384 5}" "${QUOTA_ADMIN:=16 32768 32}"

  if [[ -z "${VPS_IP:-}" ]]; then
    VPS_IP=$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
  fi

  # Secrets : réutilisés s'ils existent, sinon générés une seule fois
  mkdir -p "$ETC_DIR"
  chmod 700 "$ETC_DIR"
  if [[ -f "$SECRETS_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$SECRETS_FILE"
  fi
  local k
  for k in DB_PASSWORD LOBBY_API_KEY VELOCITY_PLUGIN_KEY RCON_PASSWORD FORWARDING_SECRET; do
    save_secret "$k" "${!k:-$(gen_secret)}"
  done
  save_secret RATHOLE_TOKEN "${RATHOLE_TOKEN:-$(openssl rand -hex 32)}"

  # Infos utilisées par mcs-add-node
  cat > "$ETC_DIR/runtime.env" <<EOF
VPS_IP=$VPS_IP
PORT_START=$PORT_START
PORT_END=$PORT_END
ENABLE_SSH_TUNNEL=$ENABLE_SSH_TUNNEL
SSH_TUNNEL_PORT=$SSH_TUNNEL_PORT
EOF
}

install_helpers() {
  # mcs-rcon : envoyer des commandes à la console du lobby
  cat > /usr/local/bin/mcs-rcon <<'PYEOF'
#!/usr/bin/env python3
"""mcs-rcon : envoie des commandes à la console du lobby via RCON.
Usage : sudo mcs-rcon "lp user Vinchenzo06 info" ["autre commande" ...]"""
import os
import socket
import struct
import sys


def load_secret(key):
    if os.environ.get(key):
        return os.environ[key]
    try:
        with open("/etc/mcs/secrets.env") as f:
            for line in f:
                if line.startswith(key + "="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    sys.exit("RCON_PASSWORD introuvable (lance avec sudo)")


def read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("connexion RCON fermée")
        data += chunk
    return data


def send(sock, req_id, ptype, body):
    payload = body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<iii", len(payload) + 8, req_id, ptype) + payload)
    length = struct.unpack("<i", read_exact(sock, 4))[0]
    resp = read_exact(sock, length)
    resp_id, _ = struct.unpack("<ii", resp[:8])
    return resp_id, resp[8:-2].decode("utf-8", "replace")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    port = int(os.environ.get("RCON_PORT", "25575"))
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        resp_id, _ = send(sock, 1, 3, load_secret("RCON_PASSWORD"))
        if resp_id == -1:
            sys.exit("RCON : mot de passe refusé")
        for cmd in sys.argv[1:]:
            _, out = send(sock, 2, 2, cmd)
            if out.strip():
                print(out.strip())


if __name__ == "__main__":
    try:
        main()
    except (OSError, ConnectionError) as e:
        sys.exit(f"RCON indisponible : {e}")
PYEOF
  chmod 755 /usr/local/bin/mcs-rcon

  # mcs-status : état rapide de toute la plateforme
  cat > /usr/local/bin/mcs-status <<'SHEOF'
#!/usr/bin/env bash
echo "=== Services ==="
for s in postgresql rathole-server mcs-api minecraft-velocity minecraft-lobby; do
  printf "  %-20s %s\n" "$s" "$(systemctl is-active "$s")"
done
echo
echo "=== Ports en écoute ==="
ss -ltnp | grep -E ':(25565|25566|8081|8082|2333|2222)\b' | awk '{print "  " $4 "  " $6}'
echo
echo "=== Nodes ==="
runuser -u postgres -- psql -qtA -d mcs_db -c "SELECT '  node ' || id || '  online=' || is_online || '  heartbeat=' || COALESCE(last_heartbeat_at::text,'jamais') FROM nodes" 2>/dev/null
SHEOF
  chmod 755 /usr/local/bin/mcs-status

  # mcs-admin : donner le groupe admin LuckPerms à un joueur
  cat > /usr/local/bin/mcs-admin <<'SHEOF'
#!/usr/bin/env bash
# sudo mcs-admin <pseudo>   (le joueur doit s'être connecté au moins une fois)
[[ -n "${1:-}" ]] || { echo "Usage : sudo mcs-admin <pseudo>"; exit 1; }
exec mcs-rcon "lp user $1 parent set admin" "lp user $1 info"
SHEOF
  chmod 755 /usr/local/bin/mcs-admin

  # mcs-add-node : créer un node et produire le code de jumelage
  cat > /usr/local/bin/mcs-add-node <<'SHEOF'
#!/usr/bin/env bash
# sudo mcs-add-node [--ssh] [nom]
#   Crée un node dans l'API et affiche la commande à lancer sur la machine volontaire.
#   --ssh : cette machine sera aussi joignable en SSH via le VPS (une seule machine à la fois)
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "Lance avec sudo"; exit 1; }
ssh_tunnel=false
name=volontaire
for a in "$@"; do
  case "$a" in
    --ssh) ssh_tunnel=true ;;
    *) name=$a ;;
  esac
done
# shellcheck disable=SC1091
source /etc/mcs/secrets.env
# shellcheck disable=SC1091
source /etc/mcs/runtime.env
if [[ -f /etc/mcs/deploy.env ]]; then
  # shellcheck disable=SC1091
  source /etc/mcs/deploy.env
fi
: "${MCS_REPO:=Vinchenzo06/mcs-deploy}" "${MCS_BRANCH:=main}"
[[ "$ENABLE_SSH_TUNNEL" == "true" ]] || ssh_tunnel=false

vid=$(runuser -u postgres -- psql -qtA -d mcs_db -c "SELECT id FROM volunteers ORDER BY id LIMIT 1")
[[ -n "$vid" ]] || { echo "Aucun volontaire en base"; exit 1; }
resp=$(curl -fsS -X POST "http://127.0.0.1:8081/api/v1/admin/nodes" \
  -H "X-API-Key: $LOBBY_API_KEY" -H "Content-Type: application/json" \
  -d "$(jq -n --argjson v "$vid" --arg h "$name" '{volunteerId: $v, region: "default", hostname: $h}')") \
  || { echo "L'API a refusé la création du node (est-elle démarrée ?)"; exit 1; }
node_id=$(jq -r '.nodeId' <<<"$resp")
code=$(jq -cn --arg vps "$VPS_IP" --arg rt "$RATHOLE_TOKEN" --arg nt "$(jq -r '.nodeToken' <<<"$resp")" \
  --argjson ps "$PORT_START" --argjson pe "$PORT_END" --argjson ssh "$ssh_tunnel" --argjson sp "$SSH_TUNNEL_PORT" \
  '{v: 1, vps: $vps, rathole_token: $rt, node_token: $nt, ports: [$ps, $pe], ssh: $ssh, ssh_public_port: $sp}' | base64 -w0)

echo
echo "Node $node_id créé ($name). Sur la machine volontaire, lance :"
echo
echo "curl -fsSL https://raw.githubusercontent.com/$MCS_REPO/$MCS_BRANCH/deploy/node-setup.sh | sudo bash -s -- $code"
echo
echo "Ce code contient des secrets : ne le donne qu'au propriétaire de la machine."
SHEOF
  chmod 755 /usr/local/bin/mcs-add-node
}

# =============================================================== étapes =====
step_system() {
  step "Système de base"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq git curl wget jq unzip gpg ca-certificates ufw python3 openssl iproute2 >/dev/null
  timedatectl set-timezone "$TIMEZONE" 2>/dev/null || warn "Fuseau horaire non modifié"

  id "$MC_USER" &>/dev/null || useradd --system --home-dir "$MC_ROOT" --shell /usr/sbin/nologin "$MC_USER"
  id "$API_USER" &>/dev/null || useradd --system --home-dir "$API_DIR" --shell /usr/sbin/nologin "$API_USER"
  mkdir -p "$VELOCITY_DIR/plugins" "$LOBBY_DIR/plugins" "$API_DIR"
  chown -R "$MC_USER:$MC_USER" "$MC_ROOT"
  chown -R "$API_USER:$API_USER" "$API_DIR"

  install_helpers
  ok "Paquets, utilisateurs ($MC_USER, $API_USER), outils mcs-status / mcs-rcon / mcs-admin / mcs-add-node"
}

step_java() {
  step "Java 25 (JDK)"
  if ! java_bin >/dev/null; then
    if ! apt-get install -y -qq openjdk-25-jdk-headless >/dev/null 2>&1; then
      warn "openjdk-25 absent des dépôts Ubuntu, installation de Temurin 25"
      wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
      echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
        > /etc/apt/sources.list.d/adoptium.list
      apt-get update -qq
      apt-get install -y -qq temurin-25-jdk >/dev/null
    fi
  fi
  need_java
  need_maven
  ok "$("$JAVA_BIN" -version 2>&1 | head -n1), Maven $MAVEN_VERSION"
}

step_postgres() {
  step "PostgreSQL"
  apt-get install -y -qq postgresql >/dev/null
  systemctl enable --now postgresql >/dev/null 2>&1

  local psql=(runuser -u postgres -- psql -v ON_ERROR_STOP=1 -qtA)
  if [[ "$("${psql[@]}" -c "SELECT 1 FROM pg_roles WHERE rolname='mcs_api'")" == "1" ]]; then
    "${psql[@]}" -c "ALTER ROLE mcs_api WITH LOGIN PASSWORD '$DB_PASSWORD'" >/dev/null
  else
    "${psql[@]}" -c "CREATE ROLE mcs_api WITH LOGIN PASSWORD '$DB_PASSWORD'" >/dev/null
  fi
  if [[ "$("${psql[@]}" -c "SELECT 1 FROM pg_database WHERE datname='mcs_db'")" != "1" ]]; then
    runuser -u postgres -- createdb -O mcs_api mcs_db
  fi
  "${psql[@]}" -d mcs_db -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp"' >/dev/null
  ok "Base mcs_db prête (utilisateur mcs_api)"
}

step_rathole() {
  step "Rathole (serveur de tunnels)"
  if [[ ! -x /usr/local/bin/rathole ]]; then
    local url tmp
    url=$(curl -fsSL https://api.github.com/repos/rathole-org/rathole/releases/latest \
      | jq -r '.assets[] | select(.name == "rathole-x86_64-unknown-linux-gnu.zip") | .browser_download_url')
    [[ -n "$url" ]] || die "Release rathole introuvable sur GitHub"
    tmp=$(mktemp -d)
    download "$url" "$tmp/rathole.zip"
    unzip -q -o "$tmp/rathole.zip" -d "$tmp"
    install -m 755 "$tmp/rathole" /usr/local/bin/rathole
    rm -rf "$tmp"
  fi

  mkdir -p /etc/rathole
  local p
  {
    echo "# Généré par install-vps.sh - réécrit à chaque déploiement"
    echo "[server]"
    echo "bind_addr = \"0.0.0.0:2333\""
    echo "default_token = \"$RATHOLE_TOKEN\""
    for ((p = PORT_START; p <= PORT_END; p++)); do
      printf '\n[server.services.mcs-%d]\nbind_addr = "127.0.0.1:%d"\n' "$p" "$p"
    done
    if [[ "$ENABLE_SSH_TUNNEL" == "true" ]]; then
      printf '\n[server.services.home_ssh]\nbind_addr = "0.0.0.0:%d"\n' "$SSH_TUNNEL_PORT"
    fi
  } > /etc/rathole/server.toml
  chmod 600 /etc/rathole/server.toml

  write_unit rathole-server <<EOF
[Unit]
Description=MCS - Rathole server (tunnels vers les volontaires)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/rathole --server /etc/rathole/server.toml
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
  restart_service rathole-server
  ok "Rathole actif (contrôle :2333, ports $PORT_START-$PORT_END en local)"
}

step_api() {
  step "mcs-api (Spring Boot)"
  build_jar "$SRC_API" "$JARS_DIR/mcs-api.jar"
  need_java
  install -o "$API_USER" -g "$API_USER" -m 644 "$JARS_DIR/mcs-api.jar" "$API_DIR/mcs-api.jar"

  cat > "$ETC_DIR/api.env" <<EOF
DB_PASSWORD=$DB_PASSWORD
LOBBY_API_KEY=$LOBBY_API_KEY
VELOCITY_PLUGIN_KEY=$VELOCITY_PLUGIN_KEY
EOF
  chmod 600 "$ETC_DIR/api.env"

  write_unit mcs-api <<EOF
[Unit]
Description=MCS - API centrale (Spring Boot)
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service

[Service]
User=$API_USER
WorkingDirectory=$API_DIR
EnvironmentFile=$ETC_DIR/api.env
ExecStart=$JAVA_BIN -Xms128M -Xmx$API_XMX -jar $API_DIR/mcs-api.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
  restart_service mcs-api
  echo "    Démarrage (Flyway applique les migrations)..."
  wait_http "http://127.0.0.1:8081/actuator/health" 180 \
    || die "L'API ne répond pas. Logs : journalctl -u mcs-api -n 100"
  ok "API en ligne sur :8081"
}

step_velocity() {
  step "Velocity + proxymanager"
  build_jar "$SRC_PROXY" "$JARS_DIR/velocity/proxymanager.jar"
  need_java

  if [[ ! -f "$VELOCITY_DIR/velocity.jar" || "$FORCE_UPDATE" == "true" ]]; then
    local url=${VELOCITY_URL:-}
    [[ -n "$url" ]] || url=$(papermc_url velocity "$VELOCITY_VERSION")
    [[ -n "$url" ]] || die "Velocity introuvable via l'API PaperMC (renseigne VELOCITY_URL dans mcs.env)"
    download "$url" "$VELOCITY_DIR/velocity.jar"
  fi

  shopt -s nullglob
  local jars=("$JARS_DIR"/velocity/*.jar)
  shopt -u nullglob
  (( ${#jars[@]} )) || die "Aucun plugin dans $JARS_DIR/velocity/ (proxymanager attendu)"
  rm -f "$VELOCITY_DIR"/plugins/proxymanager*.jar
  cp -f "${jars[@]}" "$VELOCITY_DIR/plugins/"

  echo -n "$FORWARDING_SECRET" > "$VELOCITY_DIR/forwarding.secret"

  cat > "$VELOCITY_DIR/velocity.toml" <<'EOF'
# Généré par install-vps.sh - réécrit à chaque déploiement
config-version = "2.7"
bind = "0.0.0.0:25565"
motd = "<aqua>MCS <gray>- Minecraft Community Server"
show-max-players = 500
online-mode = true
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "legacy"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
enable-player-address-logging = true

[servers]
lobby = "127.0.0.1:25566"
try = ["lobby"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false

[query]
enabled = false
port = 25565
map = "Velocity"
show-plugins = false
EOF

  mkdir -p "$VELOCITY_DIR/plugins/proxymanager"
  cat > "$VELOCITY_DIR/plugins/proxymanager/config.yml" <<EOF
# Généré par install-vps.sh
api-url: http://localhost:8081
api-key: $LOBBY_API_KEY
local-api-port: 8082
local-api-key: $VELOCITY_PLUGIN_KEY
EOF
  chown -R "$MC_USER:$MC_USER" "$VELOCITY_DIR"

  write_unit minecraft-velocity <<EOF
[Unit]
Description=MCS - Velocity proxy
After=network-online.target mcs-api.service
Wants=network-online.target

[Service]
User=$MC_USER
WorkingDirectory=$VELOCITY_DIR
ExecStart=$JAVA_BIN -Xms256M -Xmx$VELOCITY_XMX -XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+ParallelRefProcEnabled -jar velocity.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
  restart_service minecraft-velocity
  wait_port 25565 90 || die "Velocity ne démarre pas. Logs : journalctl -u minecraft-velocity -n 100"
  wait_port 8082 30 || warn "L'API locale du proxymanager (:8082) ne répond pas, vérifie ses logs"
  ok "Velocity en écoute sur :25565 (API plugin sur :8082)"
}

step_lobby() {
  step "Lobby Paper + MCSLobbyPlugin + LuckPerms"
  build_jar "$SRC_LOBBY" "$JARS_DIR/lobby/mcs-lobby-plugin.jar"
  need_java

  if [[ ! -f "$LOBBY_DIR/paper.jar" || "$FORCE_UPDATE" == "true" ]]; then
    local url=${PAPER_URL:-}
    [[ -n "$url" ]] || url=$(papermc_url paper "$PAPER_VERSION")
    [[ -n "$url" ]] || die "Paper $PAPER_VERSION introuvable (renseigne PAPER_URL dans mcs.env)"
    download "$url" "$LOBBY_DIR/paper.jar"
  fi

  shopt -s nullglob
  local jars=("$JARS_DIR"/lobby/*.jar)
  (( ${#jars[@]} )) || die "Aucun plugin dans $JARS_DIR/lobby/ (mcs-lobby-plugin attendu)"
  rm -f "$LOBBY_DIR"/plugins/mcs-lobby-plugin*.jar
  cp -f "${jars[@]}" "$LOBBY_DIR/plugins/"

  local lp=("$LOBBY_DIR"/plugins/LuckPerms-Bukkit*.jar)
  shopt -u nullglob
  if (( ${#lp[@]} == 0 )); then
    local lp_url=${LUCKPERMS_BUKKIT_URL:-}
    [[ -n "$lp_url" ]] || lp_url=$(curl -fsSL https://metadata.luckperms.net/data/all 2>/dev/null | jq -r '.downloads.bukkit // empty' || true)
    if [[ -n "$lp_url" ]]; then
      download "$lp_url" "$LOBBY_DIR/plugins/$(basename "$lp_url")"
      ok "LuckPerms téléchargé"
    else
      warn "LuckPerms non téléchargé : renseigne LUCKPERMS_BUKKIT_URL dans /etc/mcs/mcs.env"
    fi
  fi

  echo "eula=true" > "$LOBBY_DIR/eula.txt"

  local props="$LOBBY_DIR/server.properties"
  set_prop "$props" server-port 25566
  set_prop "$props" server-ip 127.0.0.1          # joignable uniquement via Velocity
  set_prop "$props" online-mode false
  set_prop "$props" enforce-secure-profile false
  set_prop "$props" motd "MCS Lobby"
  set_prop "$props" view-distance 6
  set_prop "$props" simulation-distance 4
  set_prop "$props" enable-rcon true
  set_prop "$props" rcon.port "$RCON_PORT"
  set_prop "$props" rcon.password "$RCON_PASSWORD"
  set_prop "$props" broadcast-rcon-to-ops false

  local spigot="$LOBBY_DIR/spigot.yml"
  if [[ -f "$spigot" ]]; then
    sed -i 's/^\(\s*bungeecord:\s*\)false/\1true/' "$spigot"
  else
    printf 'settings:\n  bungeecord: true\n' > "$spigot"
  fi

  mkdir -p "$LOBBY_DIR/plugins/MCSLobbyPlugin"
  cat > "$LOBBY_DIR/plugins/MCSLobbyPlugin/config.yml" <<EOF
# Généré par install-vps.sh
api-url: http://localhost:8081
api-key: $LOBBY_API_KEY
EOF
  chown -R "$MC_USER:$MC_USER" "$LOBBY_DIR"

  write_unit minecraft-lobby <<EOF
[Unit]
Description=MCS - Lobby Paper
After=network-online.target minecraft-velocity.service
Wants=network-online.target

[Service]
User=$MC_USER
WorkingDirectory=$LOBBY_DIR
ExecStart=$JAVA_BIN -Xms256M -Xmx$LOBBY_XMX -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar paper.jar nogui
Restart=on-failure
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
  restart_service minecraft-lobby
  echo "    Démarrage du lobby (premier lancement = génération du monde)..."
  wait_port "$RCON_PORT" 240 || die "Le lobby ne démarre pas. Logs : journalctl -u minecraft-lobby -n 100"
  ok "Lobby prêt sur 127.0.0.1:25566"
}

step_firewall() {
  step "Pare-feu (ufw)"
  ufw allow OpenSSH >/dev/null
  ufw allow 22/tcp >/dev/null
  ufw allow 25565/tcp comment 'Minecraft (Velocity)' >/dev/null
  ufw allow 2333/tcp comment 'rathole (volontaires)' >/dev/null
  ufw allow 8081/tcp comment 'mcs-api (agents WebSocket)' >/dev/null
  if [[ "$ENABLE_SSH_TUNNEL" == "true" ]]; then
    ufw allow "$SSH_TUNNEL_PORT/tcp" comment 'tunnel SSH machine volontaire' >/dev/null
  fi
  ufw --force enable >/dev/null
  ok "Ouverts : 22, 25565, 2333, 8081$([[ "$ENABLE_SSH_TUNNEL" == "true" ]] && echo ", $SSH_TUNNEL_PORT")"
}

bootstrap_luckperms() {
  local cmds=() g var s ram cpu
  for g in default vip premium admin; do
    var="QUOTA_${g^^}"
    read -r s ram cpu <<<"${!var}"
    if [[ "$g" != "default" ]]; then
      cmds+=("lp creategroup $g")
    fi
    cmds+=("lp group $g meta set max-servers $s"
           "lp group $g meta set total-ram $ram"
           "lp group $g meta set total-cpu $cpu")
  done
  cmds+=("lp group admin permission set mcs.admin true"
         "lp group admin permission set luckperms.* true")

  if mcs-rcon "${cmds[@]}" >/dev/null; then
    ok "Groupes LuckPerms et quotas appliqués"
  else
    warn "RCON indisponible : groupes LuckPerms non créés (relance : install-vps.sh bootstrap)"
  fi
}

step_bootstrap() {
  step "Groupes LuckPerms"
  bootstrap_luckperms
}

step_summary() {
  step "Terminé"
  cat <<EOF

  Minecraft : $VPS_IP:25565
  Secrets   : $SECRETS_FILE  (à sauvegarder hors du VPS !)

  Et maintenant :
    1. Connecter une machine volontaire :  sudo mcs-add-node --ssh maison
    2. Te connecter une fois en jeu, puis : sudo mcs-admin <ton_pseudo>

  Outils : sudo mcs-status | sudo mcs-rcon "<commande>"

EOF
}

# ================================================================= main ======
main() {
  cd /
  load_config
  local steps=("$@") s
  (( ${#steps[@]} )) || steps=("${ALL_STEPS[@]}")
  for s in "${steps[@]}"; do
    declare -F "step_$s" >/dev/null || die "Étape inconnue : $s (disponibles : ${ALL_STEPS[*]})"
    "step_$s"
  done
}

main "$@"
