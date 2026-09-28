#!/usr/bin/env bash
# =============================================================================
#  MCS - Connexion d'une machine volontaire au VPS
#
#  Le code vient de "sudo mcs-add-node" sur le VPS, qui affiche la commande
#  complète à copier ici :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh | sudo bash -s -- <CODE>
#
#  Tout ce qui est propre à cette machine (port SSH, nom...) est détecté ici :
#  le VPS n'a pas besoin de le connaître.
# =============================================================================
set -euo pipefail

C_STEP='\033[1;36m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OFF='\033[0m'
step() { echo -e "\n${C_STEP}==> $*${C_OFF}"; }
ok()   { echo -e "${C_OK}    ✔ $*${C_OFF}"; }
warn() { echo -e "${C_WARN}    ! $*${C_OFF}"; }
die()  { echo -e "${C_ERR}    ✘ $*${C_OFF}" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Lance avec sudo"
CODE="${1:-}"
[[ -n "$CODE" ]] || die "Code de jumelage manquant (obtiens-le avec 'sudo mcs-add-node' sur le VPS)"

# ------------------------------------------------------------ prérequis ----
step "Prérequis"
missing=()
for bin in jq curl unzip; do
  command -v "$bin" >/dev/null || missing+=("$bin")
done
if (( ${#missing[@]} )); then
  command -v apt-get >/dev/null || die "Installe d'abord : ${missing[*]}"
  apt-get update -qq
  apt-get install -y -qq "${missing[@]}" >/dev/null
fi
ok "jq, curl, unzip"

# --------------------------------------------------------- lecture du code ----
json=$(printf '%s' "$CODE" | base64 -d 2>/dev/null) || die "Code invalide"
jq -e . >/dev/null 2>&1 <<<"$json" || die "Code invalide"
field() { jq -r "$1 // empty" <<<"$json"; }
[[ "$(field .v)" == "2" ]] || die "Code d'une ancienne version : génère-en un nouveau avec 'sudo mcs-add-node' sur le VPS (à jour)"
NODE_ID=$(field .node_id)
VPS_IP=$(field .vps)
RATHOLE_TOKEN=$(field .rathole_token)
NODE_TOKEN=$(field .node_token)
PORT_START=$(field '.ports[0]')
PORT_END=$(field '.ports[1]')
SSH_TUNNEL=$(field .ssh)
SSH_PUBLIC_PORT=$(field .ssh_public_port)
[[ "$NODE_ID" =~ ^[0-9]+$ && -n "$VPS_IP" && -n "$RATHOLE_TOKEN" && -n "$NODE_TOKEN" \
   && "$PORT_START" =~ ^[0-9]+$ && "$PORT_END" =~ ^[0-9]+$ ]] || die "Code incomplet"

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

# ------------------------------------------------------------------- agent ----
step "Agent MCS"
AGENT_CFG=/opt/mcs-agent/config.yaml
if [[ -f "$AGENT_CFG" ]]; then
  cp "$AGENT_CFG" "$AGENT_CFG.bak.$(date +%s)"
  sed -i -E "s|^(\s*url:\s*).*|\1ws://$VPS_IP:8081/ws/agent|" "$AGENT_CFG"
  sed -i -E "s|^(\s*token:\s*).*|\1$NODE_TOKEN|" "$AGENT_CFG"
  ok "config.yaml mis à jour (URL du VPS + token du node)"
else
  warn "Agent absent de /opt/mcs-agent : installation automatique prévue dans une version suivante"
fi

# -------------------------------------------------- conteneurs orphelins ----
if command -v docker >/dev/null && [[ -n "$(docker ps -aq --filter label=mcs.managed=true)" ]]; then
  step "Anciens serveurs MCS"
  echo "    Ces conteneurs appartiennent à une ancienne base de données et"
  echo "    entreront en conflit avec les nouveaux serveurs :"
  docker ps -a --filter label=mcs.managed=true --format '      {{.Names}}  ({{.Status}})'
  DATA_PATH=$(grep -E '^\s*data_path:' "$AGENT_CFG" 2>/dev/null | awk '{print $2}' | tr -d '"' || true)
  if read -rp "    Les supprimer, avec le contenu de ${DATA_PATH:-<data_path inconnu>} ? [o/N] " rep </dev/tty 2>/dev/null \
     && [[ "$rep" =~ ^[oOyY]$ ]]; then
    docker ps -aq --filter label=mcs.managed=true | xargs -r docker rm -f >/dev/null
    if [[ -n "$DATA_PATH" && -d "$DATA_PATH" ]]; then
      rm -rf "${DATA_PATH:?}"/*
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
if [[ -f "$AGENT_CFG" ]]; then
  systemctl restart mcs-agent
  sleep 5
  journalctl -u mcs-agent -n 5 --no-pager || true
fi

echo
ok "Machine connectée au VPS $VPS_IP (node $NODE_ID, ports $PORT_START-$PORT_END)"
if [[ "$SSH_TUNNEL" == "true" ]]; then
  echo "    SSH depuis l'extérieur : ssh -p $SSH_PUBLIC_PORT ${SUDO_USER:-<utilisateur>}@$VPS_IP"
fi
echo
