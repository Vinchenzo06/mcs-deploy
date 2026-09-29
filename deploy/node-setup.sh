#!/usr/bin/env bash
# =============================================================================
#  MCS - Connexion d'une machine volontaire au VPS
#
#  Le code vient de "sudo mcs-add-node" sur le VPS, qui affiche la commande
#  complète à copier ici :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh -o node-setup.sh && sudo bash node-setup.sh <CODE>
#
#  Mettre à jour seulement l'agent (machine déjà jumelée) :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh -o node-setup.sh && sudo bash node-setup.sh --update-agent
#
#  Changer la part de la machine prêtée à MCS (RAM, CPU, disque, sauvegardes) :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh -o node-setup.sh && sudo bash node-setup.sh --capacity
#
#  Tout ce qui est propre à cette machine (port SSH, nom...) est détecté ici :
#  le VPS n'a pas besoin de le connaître. L'agent est téléchargé depuis la
#  release GitHub "agent-latest", compilée automatiquement (GitHub Actions).
#
#  Les serveurs joueurs sortent sur Internet par un tunnel WireGuard vers le VPS
#  (wg-mcs) : l'IP de cette machine n'est jamais visible depuis les serveurs.
# =============================================================================
set -euo pipefail

REPO="${MCS_REPO:-Vinchenzo06/mcs-deploy}"
AGENT_DIR=/opt/mcs-agent
AGENT_BIN=$AGENT_DIR/mcs-agent
AGENT_CFG=$AGENT_DIR/config.yaml

C_STEP='\033[1;36m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OFF='\033[0m'
step() { echo -e "\n${C_STEP}==> $*${C_OFF}"; }
ok()   { echo -e "${C_OK}    ✔ $*${C_OFF}"; }
warn() { echo -e "${C_WARN}    ! $*${C_OFF}"; }
die()  { echo -e "${C_ERR}    ✘ $*${C_OFF}" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Lance avec sudo"
ARG="${1:-}"
[[ -n "$ARG" ]] || die "Code de jumelage manquant (obtiens-le avec 'sudo mcs-add-node' sur le VPS)"
UPDATE_ONLY=false
[[ "$ARG" == "--update-agent" ]] && UPDATE_ONLY=true
CAPACITY_ONLY=false
[[ "$ARG" == "--capacity" ]] && CAPACITY_ONLY=true

# Terminal disponible pour poser des questions ? (non si l'installation est
# automatisée). Tester avant : "read -p ... 2>/dev/null" masquerait la question.
has_tty() { ( : </dev/tty ) 2>/dev/null; }

# Le script pose des questions : il se lance depuis un fichier (voir en-tête),
# pas via "curl | sudo bash" (avec sudo-rs, Ubuntu 26.04, le clavier n'arrive
# pas toujours au script quand celui-ci est lu depuis un tuyau).

# ================================================================ étapes ====

prerequisites() {
  step "Prérequis"
  local missing=() bin
  for bin in jq curl unzip iptables; do
    command -v "$bin" >/dev/null || missing+=("$bin")
  done
  command -v wg-quick >/dev/null || missing+=(wireguard-tools)
  # Sauvegardes des serveurs vers le serveur de sauvegarde MCS (par le VPS)
  command -v restic >/dev/null || missing+=(restic)
  if (( ${#missing[@]} )); then
    command -v apt-get >/dev/null || die "Installe d'abord : ${missing[*]}"
    apt-get update -qq
    apt-get install -y -qq "${missing[@]}" >/dev/null
  fi
  ok "jq, curl, unzip, iptables, wireguard-tools, restic"

  if ! command -v docker >/dev/null; then
    command -v apt-get >/dev/null || die "Docker est requis : installe-le d'abord"
    echo "    Installation de Docker (paquet docker.io)..."
    apt-get update -qq
    apt-get install -y -qq docker.io >/dev/null
  fi
  systemctl enable --now docker >/dev/null 2>&1 || true
  docker info >/dev/null 2>&1 || die "Docker ne répond pas (systemctl status docker)"
  ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
}

# Télécharge et vérifie le binaire de l'agent (release GitHub "agent-latest")
install_agent_binary() {
  local arch base tmp sum_line
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) die "Architecture non prise en charge : $(uname -m)" ;;
  esac
  base="https://github.com/$REPO/releases/download/agent-latest"
  tmp=$(mktemp -d)
  if ! curl -fsSL -o "$tmp/mcs-agent" "$base/mcs-agent-linux-$arch" \
     || ! curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS"; then
    rm -rf "$tmp"
    if [[ -x "$AGENT_BIN" ]]; then
      warn "Release de l'agent introuvable sur GitHub : l'agent actuel est conservé"
      return
    fi
    die "Release de l'agent introuvable ($base). Vérifie l'onglet Actions du dépôt GitHub."
  fi
  sum_line=$(grep " mcs-agent-linux-$arch\$" "$tmp/SHA256SUMS" || true)
  [[ -n "$sum_line" ]] || { rm -rf "$tmp"; die "Somme de contrôle absente pour mcs-agent-linux-$arch"; }
  if [[ "$(sha256sum "$tmp/mcs-agent" | awk '{print $1}')" != "${sum_line%% *}" ]]; then
    rm -rf "$tmp"
    die "Somme de contrôle invalide : téléchargement corrompu"
  fi
  mkdir -p "$AGENT_DIR"
  install -m 755 "$tmp/mcs-agent" "$AGENT_BIN"
  rm -rf "$tmp"
  ok "Agent installé ($AGENT_BIN, linux-$arch)"
}

write_agent_unit() {
  # Protections réseau posées au démarrage AVANT Docker : les conteneurs qui
  # redémarrent seuls ne sont jamais, même quelques secondes, sans isolation.
  cat > /etc/systemd/system/mcs-netguard.service <<EOF
[Unit]
Description=MCS - Protections réseau des serveurs (avant Docker)
Before=docker.service
After=network-pre.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$AGENT_BIN -config $AGENT_CFG -early

[Install]
WantedBy=multi-user.target
EOF
  cat > /etc/systemd/system/mcs-agent.service <<EOF
[Unit]
Description=MCS - Agent (serveurs Minecraft de cette machine)
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
WorkingDirectory=$AGENT_DIR
ExecStart=$AGENT_BIN -config $AGENT_CFG
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable mcs-netguard mcs-agent >/dev/null 2>&1
}

show_agent_log() {
  sleep 5
  journalctl -u mcs-agent -n 8 --no-pager || true
}

# ==================================================== capacité prêtée ====
# Le volontaire choisit la part de sa machine prêtée à MCS. Il doit toujours lui
# rester au moins 10 % de sa RAM et de son disque, et au moins un cœur CPU.
# L'agent applique les mêmes limites (même si config.yaml est modifié à la main).

CAP_RAM=; CAP_CPU=; CAP_DISK=; CAP_LOCAL=
MIN_RAM_MB=2048    # le plus petit serveur prend ~1,2 Go ; 2 Go laissent un peu de marge
MIN_DISK_MB=5120   # quota disque par défaut d'un serveur

# "8G", "8 Go", "1,5G", "512M", "8192" (Mo) -> Mo ; vide si le format est inconnu
to_mb() {
  local v=${1// /}
  v=${v,,}
  if [[ "$v" =~ ^([0-9]+([.,][0-9]+)?)(g|go|gb|gio|gib)$ ]]; then
    awk -v n="${BASH_REMATCH[1]/,/.}" 'BEGIN { printf "%d", n * 1024 }'
  elif [[ "$v" =~ ^([0-9]+)(m|mo|mb|mio|mib)?$ ]]; then
    echo "${BASH_REMATCH[1]}"
  fi
}

# 16384 -> "16 Go (16384 Mo)"
fmt_mb() {
  awk -v m="$1" 'BEGIN {
    if (m >= 1024) { g = m / 1024; if (g == int(g)) printf "%d Go (%d Mo)", g, m; else printf "%.1f Go (%d Mo)", g, m }
    else printf "%d Mo", m }'
}

# Pose une question, recommence tant que la réponse n'est pas valable.
# Sans terminal (installation automatisée), la valeur proposée est gardée.
ask_amount() {  # libellé, proposé, min, max, unité ("mb" ou "cores")
  local label=$1 def=$2 min=$3 max=$4 unit=$5 answer value shown
  if [[ "$unit" == "mb" ]]; then shown=$(fmt_mb "$def"); else shown="$def"; fi
  while true; do
    if ! has_tty || ! read -rp "    → $label [$shown] : " answer </dev/tty; then
      echo "$def"; return
    fi
    if [[ -z "$answer" ]]; then
      echo "$def"; return
    fi
    if [[ "$unit" == "mb" ]]; then
      value=$(to_mb "$answer")
      [[ -n "$value" ]] || { echo "      ! Format non reconnu : écris par exemple 8G (= 8 Go) ou 8192 (Mo)" >&2; continue; }
    else
      value=$answer
      [[ "$value" =~ ^[0-9]+$ ]] || { echo "      ! Écris un nombre entier de cœurs" >&2; continue; }
    fi
    if (( value < min )); then
      if [[ "$unit" == "mb" ]]; then echo "      ! Minimum : $(fmt_mb "$min")" >&2; else echo "      ! Minimum : $min" >&2; fi
      continue
    fi
    if (( value > max )); then
      if [[ "$unit" == "mb" ]]; then echo "      ! Maximum : $(fmt_mb "$max")" >&2; else echo "      ! Maximum : $max" >&2; fi
      continue
    fi
    echo "$value"; return
  done
}

# Capacité déjà choisie (config.yaml existant), reprise comme proposition
load_capacity() {
  [[ -f "$AGENT_CFG" ]] || return 0
  CAP_RAM=$(awk '$1 == "ram_mb:" { print $2 }' "$AGENT_CFG" || true)
  CAP_CPU=$(awk '$1 == "cpu_cores:" { print $2 }' "$AGENT_CFG" || true)
  CAP_DISK=$(awk '$1 == "disk_mb:" { print $2 }' "$AGENT_CFG" || true)
  # Section "backups:" -> "  local: true|false"
  CAP_LOCAL=$(awk '/^backups:/ { b = 1; next } b && $1 == "local:" { print $2; exit } /^[^[:space:]]/ { b = 0 }' "$AGENT_CFG" || true)
}

choose_capacity() {  # dossier des données des serveurs
  local data_path=$1 ram_total ram_avail cores disk_total disk_free data_used
  local ram_max cpu_max disk_pool disk_max
  mkdir -p "$data_path"
  ram_total=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
  ram_avail=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
  cores=$(nproc)
  read -r disk_total disk_free < <(df -Pm "$data_path" | awk 'NR == 2 { print $2, $4 }')
  data_used=$(du -sm "$data_path" 2>/dev/null | awk '{ print $1 }')
  data_used=${data_used:-0}

  # Limites : il reste toujours au moins 10 % de RAM et de disque, et un cœur
  ram_max=$(( ram_total * 90 / 100 ))
  cpu_max=$(( cores > 1 ? cores - 1 : 1 ))
  disk_pool=$(( disk_free + data_used ))                 # espace utilisable par MCS
  disk_max=$(( disk_pool - disk_total / 10 ))            # en gardant 10 % du disque libre
  (( ram_max >= MIN_RAM_MB )) || die "Pas assez de RAM : $(fmt_mb "$ram_total") au total, il en faut au moins $(fmt_mb $(( MIN_RAM_MB * 10 / 9 + 1 )))"
  (( disk_max >= MIN_DISK_MB )) || die "Pas assez de disque libre dans $data_path : $(fmt_mb "$disk_free") libres (il faut garder 10 % du disque)"

  # Propositions : la moitié, sans dépasser les limites (ou le choix précédent)
  [[ "$CAP_RAM" =~ ^[0-9]+$ ]] || CAP_RAM=$(( ram_total / 2 ))
  [[ "$CAP_CPU" =~ ^[0-9]+$ ]] || CAP_CPU=$(( cores / 2 > 0 ? cores / 2 : 1 ))
  [[ "$CAP_DISK" =~ ^[0-9]+$ ]] || CAP_DISK=$(( disk_pool / 2 ))
  (( CAP_RAM > ram_max )) && CAP_RAM=$ram_max;   (( CAP_RAM < MIN_RAM_MB )) && CAP_RAM=$MIN_RAM_MB
  (( CAP_CPU > cpu_max )) && CAP_CPU=$cpu_max;   (( CAP_CPU < 1 )) && CAP_CPU=1
  (( CAP_DISK > disk_max )) && CAP_DISK=$disk_max; (( CAP_DISK < MIN_DISK_MB )) && CAP_DISK=$MIN_DISK_MB

  step "Ressources prêtées à MCS"
  echo "    Tu choisis la part de ta machine que les serveurs pourront utiliser."
  echo "    Rappel : 1 Go = 1024 Mo. Tu peux répondre en Go (ex. 8G) ou en Mo (ex. 8192)."
  echo "    Entrée = garder la valeur proposée entre crochets."

  echo
  echo "    RAM : $(fmt_mb "$ram_total") au total, $(fmt_mb "$ram_avail") libres en ce moment."
  echo "          Maximum possible : $(fmt_mb "$ram_max") (il doit te rester au moins 10 %)."
  echo "          Conseil : garde de quoi utiliser ta machine normalement (système, navigateur, jeux...)."
  CAP_RAM=$(ask_amount "RAM prêtée" "$CAP_RAM" "$MIN_RAM_MB" "$ram_max" mb)

  echo
  echo "    Processeur : $cores cœur(s) (threads)."
  echo "          Maximum possible : $cpu_max (il doit te rester au moins un cœur)."
  CAP_CPU=$(ask_amount "Cœurs prêtés" "$CAP_CPU" 1 "$cpu_max" cores)

  echo
  echo "    Disque ($data_path) : $(fmt_mb "$disk_total") au total, $(fmt_mb "$disk_free") libres$( (( data_used > 1 )) && echo ", dont $(fmt_mb "$data_used") déjà utilisés par MCS")."
  echo "          Maximum possible : $(fmt_mb "$disk_max") (il doit rester au moins 10 % du disque libre)."
  echo "          Chaque serveur réserve 5 Go par défaut."
  CAP_DISK=$(ask_amount "Disque prêté" "$CAP_DISK" "$MIN_DISK_MB" "$disk_max" mb)

  echo
  echo "    Sauvegardes : chaque serveur est sauvegardé chaque jour sur le serveur de sauvegarde"
  echo "          de MCS. Tu peux aussi en garder une copie ici : restauration plus rapide, et"
  echo "          moins de charge pour le réseau. Elles restent dans l'espace disque de chaque"
  echo "          serveur (déjà compté dans le disque prêté) : rien de plus à prévoir."
  local def=o answer
  [[ "${CAP_LOCAL:-}" == "false" ]] && def=n
  CAP_LOCAL=true
  [[ "$def" == "n" ]] && CAP_LOCAL=false
  if has_tty; then
    read -r -p "    Garder des sauvegardes des serveurs sur cette machine ? [$( [[ $def == o ]] && echo O/n || echo o/N )] " answer </dev/tty || answer=""
    case "${answer,,}" in
      o | oui | y | yes) CAP_LOCAL=true ;;
      n | non | no) CAP_LOCAL=false ;;
    esac
  fi

  echo
  ok "Prêté à MCS : $(fmt_mb "$CAP_RAM") de RAM, $CAP_CPU cœur(s), $(fmt_mb "$CAP_DISK") de disque"
  if [[ "$CAP_LOCAL" == "true" ]]; then
    ok "Sauvegardes gardées aussi sur cette machine"
  else
    ok "Sauvegardes seulement sur le serveur de sauvegarde MCS"
  fi
}

# Remplace (ou ajoute) la section capacity de config.yaml
write_capacity() {
  local tmp
  tmp=$(mktemp)
  awk '/^# Part de la machine prêtée à MCS/ { next }
       /^# Sauvegardes gardées sur cette machine/ { next }
       /^(capacity|backups):/ { skip = 1; next }
       skip && /^[[:space:]]/ { next }
       { skip = 0; print }' "$AGENT_CFG" > "$tmp"
  cat >> "$tmp" <<EOF

# Part de la machine prêtée à MCS (pour la changer : node-setup.sh --capacity)
capacity:
  ram_mb: $CAP_RAM
  cpu_cores: $CAP_CPU
  disk_mb: $CAP_DISK

# Sauvegardes gardées sur cette machine (comptées dans le disque de chaque serveur)
backups:
  local: ${CAP_LOCAL:-false}
EOF
  install -m 600 "$tmp" "$AGENT_CFG"
  rm -f "$tmp"
}

agent_data_path() {
  local p
  p=$(awk '$1 == "data_path:" { gsub(/"/, "", $2); print $2 }' "$AGENT_CFG" 2>/dev/null || true)
  echo "${p:-/opt/mcs-data/servers}"
}

# ========================================== changement de capacité seul ====
if [[ "$CAPACITY_ONLY" == "true" ]]; then
  [[ -f "$AGENT_CFG" ]] || die "Aucune config d'agent ($AGENT_CFG) : jumelle d'abord la machine avec un code"
  load_capacity
  choose_capacity "$(agent_data_path)"
  write_capacity
  systemctl restart mcs-agent
  show_agent_log
  echo
  ok "Capacité mise à jour"
  exit 0
fi

# ============================================== mise à jour de l'agent seule ====
if [[ "$UPDATE_ONLY" == "true" ]]; then
  [[ -f "$AGENT_CFG" ]] || die "Aucune config d'agent ($AGENT_CFG) : jumelle d'abord la machine avec un code"
  prerequisites
  step "Mise à jour de l'agent MCS"
  install_agent_binary
  if ! grep -q '^capacity:' "$AGENT_CFG"; then
    choose_capacity "$(agent_data_path)"
    write_capacity
  fi
  write_agent_unit
  systemctl restart mcs-agent
  show_agent_log
  echo
  ok "Agent à jour"
  exit 0
fi

# ============================================================ jumelage ====
prerequisites

# --------------------------------------------------------- lecture du code ----
json=$(printf '%s' "$ARG" | base64 -d 2>/dev/null) || die "Code invalide"
jq -e . >/dev/null 2>&1 <<<"$json" || die "Code invalide"
field() { jq -r "$1 // empty" <<<"$json"; }
[[ "$(field .v)" == "3" ]] || die "Code d'une ancienne version : génère-en un nouveau sur le VPS (à jour) avec 'sudo mcs-add-node' (ou 'sudo mcs-add-node --renew <id>' pour cette machine)"
NODE_ID=$(field .node_id)
VPS_IP=$(field .vps)
RATHOLE_TOKEN=$(field .rathole_token)
NODE_TOKEN=$(field .node_token)
PORT_START=$(field '.ports[0]')
PORT_END=$(field '.ports[1]')
SSH_TUNNEL=$(field .ssh)
SSH_PUBLIC_PORT=$(field .ssh_public_port)
WG_PRIVATE=$(field .wg_private)
WG_IP=$(field .wg_ip)
WG_VPS_PUBLIC=$(field .wg_vps_public)
WG_PORT=$(field .wg_port)
# Adresse de l'API pour l'agent (wss:// via HTTPS si le VPS a un domaine)
API_URL=$(field .api_url)
API_URL=${API_URL:-ws://$VPS_IP:8081/ws/agent}
[[ "$NODE_ID" =~ ^[0-9]+$ && -n "$VPS_IP" && -n "$RATHOLE_TOKEN" && -n "$NODE_TOKEN" \
   && "$PORT_START" =~ ^[0-9]+$ && "$PORT_END" =~ ^[0-9]+$ \
   && -n "$WG_PRIVATE" && -n "$WG_IP" && -n "$WG_VPS_PUBLIC" && "$WG_PORT" =~ ^[0-9]+$ ]] || die "Code incomplet"

# Machine déjà jumelée sous ce même numéro ? (renouvellement : ses serveurs sont conservés)
PREVIOUS_NODE_ID=$(grep -oE 'client\.services\.n[0-9]+-' /etc/rathole/client.toml 2>/dev/null \
  | head -1 | grep -oE '[0-9]+' || true)

# --------------------------------------------------------- détection locale ----
step "Détection de la machine"
SSH_PORT=$(sshd -T 2>/dev/null | awk '$1 == "port" { print $2; exit }' || true)
SSH_PORT=${SSH_PORT:-22}
ok "Machine $(hostname) = node $NODE_ID, SSH local sur le port $SSH_PORT, VPS $VPS_IP"

# ----------------------------------------------------------------- rathole ----
step "Tunnel rathole"
RATHOLE_BIN=$(command -v rathole || true)
if [[ -z "$RATHOLE_BIN" ]]; then
  url=$(curl -fsSL https://api.github.com/repos/rathole-org/rathole/releases/latest \
    | jq -r '.assets[] | select(.name == "rathole-x86_64-unknown-linux-gnu.zip") | .browser_download_url')
  [[ -n "$url" ]] || die "Release rathole introuvable"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/rathole.zip" "$url"
  unzip -q -o "$tmp/rathole.zip" -d "$tmp"
  install -m 755 "$tmp/rathole" /usr/local/bin/rathole
  rm -rf "$tmp"
  RATHOLE_BIN=/usr/local/bin/rathole
  ok "rathole installé"
fi

mkdir -p /etc/rathole
[[ -f /etc/rathole/client.toml ]] && cp /etc/rathole/client.toml "/etc/rathole/client.toml.bak.$(date +%s)"
{
  echo "# Généré par node-setup.sh (VPS $VPS_IP, node $NODE_ID)"
  echo "[client]"
  echo "remote_addr = \"$VPS_IP:2333\""
  echo "default_token = \"$RATHOLE_TOKEN\""
  for ((p = PORT_START; p <= PORT_END; p++)); do
    printf '\n[client.services.n%s-%d]\nlocal_addr = "127.0.0.1:%d"\n' "$NODE_ID" "$p" "$p"
  done
  if [[ "$SSH_TUNNEL" == "true" ]]; then
    printf '\n[client.services.n%s-ssh]\nlocal_addr = "127.0.0.1:%d"\n' "$NODE_ID" "$SSH_PORT"
  fi
} > /etc/rathole/client.toml
chmod 600 /etc/rathole/client.toml

if ! systemctl cat rathole-client >/dev/null 2>&1; then
  cat > /etc/systemd/system/rathole-client.service <<EOF
[Unit]
Description=MCS - Rathole client (tunnels vers le VPS)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$RATHOLE_BIN --client /etc/rathole/client.toml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
fi
ok "client.toml écrit (ports $PORT_START-$PORT_END$([[ "$SSH_TUNNEL" == "true" ]] && echo ", SSH $SSH_PORT"))"

# --------------------------------------------------------------- wireguard ----
step "Sortie Internet des serveurs (WireGuard vers le VPS)"
ip link add wg-mcs-test type wireguard 2>/dev/null && ip link del wg-mcs-test \
  || die "Le noyau de cette machine ne prend pas en charge WireGuard"
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard
cat > /etc/wireguard/wg-mcs.conf <<EOF
# Généré par node-setup.sh (VPS $VPS_IP, node $NODE_ID)
# Seuls les serveurs joueurs (pont mcs-br0) passent par ce tunnel : l'agent
# route leur trafic vers la table 51820. "Table = off" : le reste de la
# machine n'est pas touché.
[Interface]
PrivateKey = $WG_PRIVATE
Address = $WG_IP/32
MTU = 1420
Table = off
PostUp = ip -4 route replace default dev %i table 51820
PostUp = ip -4 route replace 10.99.0.1/32 dev %i
PostUp = sysctl -qw net.ipv4.conf.%i.rp_filter=2
PostUp = iptables -w -t mangle -A FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
PostUp = iptables -w -t mangle -A FORWARD -i %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
PostDown = iptables -w -t mangle -D FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
PostDown = iptables -w -t mangle -D FORWARD -i %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

[Peer]
PublicKey = $WG_VPS_PUBLIC
Endpoint = $VPS_IP:$WG_PORT
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
chmod 600 /etc/wireguard/wg-mcs.conf
systemctl enable wg-quick@wg-mcs >/dev/null 2>&1
systemctl restart wg-quick@wg-mcs || die "Tunnel WireGuard impossible à démarrer : journalctl -u wg-quick@wg-mcs -n 30"
hs=0
for _ in $(seq 1 15); do
  hs=$(wg show wg-mcs latest-handshakes | awk '{ print $2; exit }')
  [[ "${hs:-0}" != "0" ]] && break
  sleep 1
done
if [[ "${hs:-0}" == "0" ]]; then
  warn "Aucun échange avec le VPS pour l'instant (port UDP $WG_PORT bloqué ?). Vérifie : sudo wg show wg-mcs"
else
  ok "Tunnel wg-mcs actif ($WG_IP ↔ $VPS_IP:$WG_PORT)"
fi

# ------------------------------------------------------------------- agent ----
step "Agent MCS"
install_agent_binary

DATA_PATH=/opt/mcs-data/servers
if [[ -f "$AGENT_CFG" ]]; then
  DATA_PATH=$(agent_data_path)
  load_capacity
  cp "$AGENT_CFG" "$AGENT_CFG.bak.$(date +%s)"
fi
choose_capacity "$DATA_PATH"
step "Agent MCS (configuration)"
cat > "$AGENT_CFG" <<EOF
# Généré par node-setup.sh (VPS $VPS_IP, node $NODE_ID)
api:
  url: $API_URL

node:
  token: $NODE_TOKEN

heartbeat:
  interval_seconds: 30

docker:
  data_path: $DATA_PATH

network:
  # Les serveurs sortent sur Internet par le tunnel WireGuard du VPS
  egress: vps

# Part de la machine prêtée à MCS (pour la changer : node-setup.sh --capacity)
capacity:
  ram_mb: $CAP_RAM
  cpu_cores: $CAP_CPU
  disk_mb: $CAP_DISK

# Sauvegardes gardées sur cette machine (comptées dans le disque de chaque serveur)
backups:
  local: ${CAP_LOCAL:-false}
EOF
chmod 600 "$AGENT_CFG"
write_agent_unit
ok "config.yaml écrit (API : $API_URL, données : $DATA_PATH)"

# -------------------------------------------------- conteneurs orphelins ----
# Seulement si cette machine change de numéro : un renouvellement garde ses serveurs
if [[ "$PREVIOUS_NODE_ID" != "$NODE_ID" && -n "$(docker ps -aq --filter label=mcs.managed=true)" ]]; then
  step "Anciens serveurs MCS"
  echo "    Ces conteneurs appartiennent à une ancienne base de données et"
  echo "    entreront en conflit avec les nouveaux serveurs :"
  docker ps -a --filter label=mcs.managed=true --format '      {{.Names}}  ({{.Status}})'
  if has_tty && read -rp "    Les supprimer, avec le contenu de $DATA_PATH ? [o/N] " rep </dev/tty \
     && [[ "$rep" =~ ^[oOyY]$ ]]; then
    docker ps -aq --filter label=mcs.managed=true | xargs -r docker rm -f >/dev/null
    if [[ -n "$DATA_PATH" && -d "$DATA_PATH" ]]; then
      rm -rf "${DATA_PATH:?}"/* "${DATA_PATH:?}/.backups"
    fi
    ok "Nettoyé"
  else
    warn "Conservés"
  fi
fi

# --------------------------------------------------------------- services ----
step "Démarrage"
systemctl daemon-reload
systemctl enable rathole-client >/dev/null 2>&1
systemctl restart rathole-client
systemctl restart mcs-agent
show_agent_log

echo
ok "Machine connectée au VPS $VPS_IP (node $NODE_ID, ports $PORT_START-$PORT_END, sortie via le VPS)"
if [[ "$SSH_TUNNEL" == "true" ]]; then
  echo "    SSH depuis l'extérieur : ssh -p $SSH_PUBLIC_PORT ${SUDO_USER:-<utilisateur>}@$VPS_IP"
fi
echo
