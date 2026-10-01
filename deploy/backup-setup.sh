#!/usr/bin/env bash
# =============================================================================
#  MCS - Serveur de sauvegarde (à la maison)
#
#  Le code vient de "sudo mcs-add-backup" sur le VPS, qui affiche la commande :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/backup-setup.sh -o backup-setup.sh && sudo bash backup-setup.sh <CODE>
#
#  Mise à jour des outils (sans code, garde la clé et le dossier) :
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/backup-setup.sh -o backup-setup.sh && sudo bash backup-setup.sh --update
#
#  État : sudo mcs-backup-status   ·   Tri des vieilles sauvegardes : sudo mcs-backup-prune
#
#  Fonctionnement :
#   - rest-server garde les dépôts restic (un par machine + "vps"), en ajout seul :
#     une machine ne peut ni lire les dépôts des autres ni effacer ses sauvegardes ;
#   - ce serveur ouvre lui-même un tunnel SSH vers le VPS et y publie rest-server
#     sur 10.99.0.1:8100 : rien à ouvrir sur ta box, et ton IP n'est jamais donnée
#     aux volontaires (ils ne voient que le VPS) ;
#   - toutes les 2 min, il récupère sur le VPS les identifiants des dépôts et la
#     liste des instantanés expirés (c'est l'API qui décide, selon les types de
#     sauvegardes) ; chaque nuit, il les supprime (restic forget + prune) puis
#     confirme au VPS ce qui reste.
#  Les données sont chiffrées par restic : les mots de passe des dépôts sont
#  gardés ici (/etc/mcs-backup) et sur le VPS.
# =============================================================================
set -euo pipefail

ETC=/etc/mcs-backup
STATE=/var/lib/mcs-backup
SVC_USER=mcs-backup
REST_BIN=/usr/local/bin/rest-server
REST_FALLBACK_VERSION=0.13.0

C_STEP='\033[1;36m'; C_OK='\033[1;32m'; C_WARN='\033[1;33m'; C_ERR='\033[1;31m'; C_OFF='\033[0m'
step() { echo -e "\n${C_STEP}==> $*${C_OFF}"; }
ok()   { echo -e "${C_OK}    ✔ $*${C_OFF}"; }
warn() { echo -e "${C_WARN}    ! $*${C_OFF}"; }
die()  { echo -e "${C_ERR}    ✘ $*${C_OFF}" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Lance avec sudo"
# apt occupé (mises à jour automatiques d'Ubuntu) : attendre jusqu'à 10 min au lieu d'échouer
mkdir -p /etc/apt/apt.conf.d && echo 'DPkg::Lock::Timeout "600";' > /etc/apt/apt.conf.d/90mcs-lock-timeout
CODE="${1:-}"
UPDATE=false
if [[ "$CODE" == "--update" ]]; then
  UPDATE=true
  [[ -f "$ETC/config.env" ]] || die "Pas encore installé ici : lance d'abord avec le code de 'sudo mcs-add-backup'"
fi
[[ -n "$CODE" ]] || die "Code manquant (obtiens-le avec 'sudo mcs-add-backup' sur le VPS)"

# Questions posées au clavier (pas via "curl | sudo bash", voir en-tête)
has_tty() { ( : </dev/tty ) 2>/dev/null; }

# ================================================================ étapes ====

decode_code() {
  step "Code du VPS"
  command -v jq >/dev/null || { apt-get update -qq; apt-get install -y -qq jq >/dev/null; }
  local json
  json=$(base64 -d <<<"$CODE" 2>/dev/null) || die "Code illisible (copie-le en entier)"
  jq -e '.v == 1 and .vps and .key and .known_hosts' <<<"$json" >/dev/null 2>&1 || die "Code invalide ou trop ancien"
  VPS=$(jq -r .vps <<<"$json")
  SSH_PORT=$(jq -r .ssh_port <<<"$json")
  SSH_USER=$(jq -r .user <<<"$json")
  LISTEN=$(jq -r .listen <<<"$json")
  KEY_B64=$(jq -r .key <<<"$json")
  KNOWN=$(jq -r .known_hosts <<<"$json")
  [[ "$SSH_PORT" =~ ^[0-9]+$ && "$SSH_USER" =~ ^[a-z_-]+$ && "$LISTEN" =~ ^[0-9.]+:[0-9]+$ ]] || die "Code invalide"
  ok "VPS $VPS (SSH $SSH_PORT), publication sur $LISTEN"
}

prerequisites() {
  step "Prérequis"
  apt-get update -qq
  apt-get install -y -qq restic openssh-client apache2-utils curl jq ca-certificates >/dev/null
  ok "restic $(restic version 2>/dev/null | awk '{print $2}'), ssh, htpasswd"

  if [[ ! -x "$REST_BIN" ]]; then
    local arch url tmp
    case "$(uname -m)" in
      x86_64) arch=amd64 ;;
      aarch64 | arm64) arch=arm64 ;;
      *) die "Architecture non prise en charge : $(uname -m)" ;;
    esac
    url=$(curl -fsSL https://api.github.com/repos/restic/rest-server/releases/latest 2>/dev/null \
      | jq -r --arg a "linux_${arch}.tar.gz" '.assets[] | select(.name | endswith($a)) | .browser_download_url' 2>/dev/null | head -1 || true)
    [[ -n "$url" ]] || url="https://github.com/restic/rest-server/releases/download/v${REST_FALLBACK_VERSION}/rest-server_${REST_FALLBACK_VERSION}_linux_${arch}.tar.gz"
    tmp=$(mktemp -d)
    curl -fsSL "$url" -o "$tmp/rs.tgz" || die "Téléchargement de rest-server impossible : $url"
    tar -xzf "$tmp/rs.tgz" -C "$tmp"
    install -m 755 "$(find "$tmp" -type f -name rest-server | head -1)" "$REST_BIN"
    rm -rf "$tmp"
  fi
  ok "$("$REST_BIN" --version 2>/dev/null | head -1)"
}

choose_storage() {
  step "Dossier des sauvegardes"
  local current="" answer free
  [[ -f "$ETC/config.env" ]] && current=$(. "$ETC/config.env"; echo "${DATA_DIR:-}")
  DATA_DIR=${current:-/srv/mcs-backup}
  echo "    Espace libre par disque :"
  df -h -x tmpfs -x devtmpfs -x squashfs -x overlay --output=target,size,avail 2>/dev/null | sed 's/^/      /'
  if has_tty; then
    read -r -p "    Dossier où garder les sauvegardes [$DATA_DIR] : " answer </dev/tty || answer=""
    [[ -n "$answer" ]] && DATA_DIR=$answer
  fi
  [[ "$DATA_DIR" == /* ]] || die "Chemin absolu attendu (ex. /srv/mcs-backup)"
  mkdir -p "$DATA_DIR"
  free=$(df -h --output=avail "$DATA_DIR" | tail -1 | tr -d ' ')
  ok "Sauvegardes dans $DATA_DIR ($free libres)"
}

setup_files() {
  step "Configuration"
  id "$SVC_USER" &>/dev/null || useradd --system --home-dir "$STATE" --shell /usr/sbin/nologin "$SVC_USER"
  install -d -m 750 -o root -g "$SVC_USER" "$ETC"
  install -d -m 750 -o "$SVC_USER" -g "$SVC_USER" "$STATE"
  chown "$SVC_USER:$SVC_USER" "$DATA_DIR"
  chmod 750 "$DATA_DIR"
  # Clé au service du tunnel, en 600 (ssh refuse une clé lisible par d'autres) ;
  # root la lit aussi pour mcs-backup-sync
  base64 -d <<<"$KEY_B64" > "$ETC/id_ed25519"
  chown "$SVC_USER:$SVC_USER" "$ETC/id_ed25519"
  chmod 600 "$ETC/id_ed25519"
  printf '%s\n' "$KNOWN" > "$ETC/known_hosts"
  chmod 644 "$ETC/known_hosts"
  cat > "$ETC/config.env" <<EOF
VPS=$VPS
SSH_PORT=$SSH_PORT
SSH_USER=$SSH_USER
LISTEN=$LISTEN
DATA_DIR=$DATA_DIR
EOF
  [[ -f "$ETC/htpasswd" ]] || install -m 640 -o root -g "$SVC_USER" /dev/null "$ETC/htpasswd"
  ok "Clé du tunnel et empreintes du VPS installées"
}

install_tools() {
  step "Outils (synchronisation, tri, état)"

  # Identifiants des dépôts et instantanés expirés, lus sur le VPS (commande forcée de la clé)
  cat > /usr/local/bin/mcs-backup-sync <<'SHEOF'
#!/usr/bin/env bash
set -euo pipefail
ETC=/etc/mcs-backup
# shellcheck disable=SC1091
source "$ETC/config.env"
tmp=$(mktemp)
trap 'rm -f "$tmp" "$tmp.h"' EXIT
ssh -T -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$ETC/known_hosts" -i "$ETC/id_ed25519" -p "$SSH_PORT" \
    "$SSH_USER@$VPS" > "$tmp"
jq -e '(.version == 1 or .version == 2) and (.repos | type == "array")' "$tmp" >/dev/null || { echo "Export du VPS invalide" >&2; exit 1; }
install -m 600 -o root -g root "$tmp" "$ETC/export.json"
# htpasswd (bcrypt) régénéré seulement si les identifiants ont changé
sum=$(jq -r '.repos[] | .user + ":" + .http_password' "$ETC/export.json" | sha256sum | cut -d' ' -f1)
if [[ "$sum" != "$(cat /var/lib/mcs-backup/htpasswd.sum 2>/dev/null)" ]]; then
  : > "$tmp.h"
  while IFS=$'\t' read -r user pass; do
    [[ "$user" =~ ^[a-z0-9]+$ ]] || continue
    printf '%s' "$pass" | htpasswd -niB -C 8 "$user" >> "$tmp.h"
  done < <(jq -r '.repos[] | [.user, .http_password] | @tsv' "$ETC/export.json")
  install -m 640 -o root -g mcs-backup "$tmp.h" "$ETC/htpasswd"
  echo "$sum" > /var/lib/mcs-backup/htpasswd.sum
  systemctl restart mcs-rest-server
  echo "Identifiants mis à jour ($(wc -l < "$ETC/htpasswd") dépôts)"
fi
SHEOF
  chmod 700 /usr/local/bin/mcs-backup-sync

  # Tri : supprime les instantanés que l'API a marqués expirés (liste "forget"),
  # puis envoie au VPS la liste de ceux qui restent (accusé). Filet de sécurité :
  # un instantané inconnu de l'API (sauvegarde non enregistrée) part après 14 jours.
  # Dépôt "vps" : règles restic classiques.
  cat > /usr/local/bin/mcs-backup-prune <<'SHEOF'
#!/usr/bin/env bash
set -uo pipefail
ETC=/etc/mcs-backup
# shellcheck disable=SC1091
source "$ETC/config.env"
[[ -f "$ETC/export.json" ]] || { echo "Pas encore d'export du VPS (mcs-backup-sync)"; exit 0; }
if ! jq -e '.version == 2' "$ETC/export.json" >/dev/null; then
  echo "Export du VPS trop ancien : mets le VPS à jour (sudo mcs-deploy), rien n'est supprimé"; exit 0
fi
export RESTIC_CACHE_DIR=/var/cache/mcs-backup
now=$(date +%s)
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ack=$(mktemp)
trap 'rm -f "$ack" "$ack.n" "$ack.o"' EXIT
echo '{}' > "$ack"
# Date restic -> secondes (fuseau ignoré : précision suffisante pour des jours)
TO_EPOCH='sub("\\.[0-9]+"; "") | sub("([+-][0-9]{2}:[0-9]{2}|Z)$"; "Z") | fromdateiso8601'
echo '{}' > "$ack.o"
while IFS= read -r repo; do
  user=$(jq -r .user <<<"$repo")
  dir="$DATA_DIR/$user"
  [[ "$user" =~ ^[a-z0-9]+$ ]] || continue
  RESTIC_PASSWORD=$(jq -r .repo_password <<<"$repo")
  export RESTIC_PASSWORD
  # Recopie depuis les anciens dépôts de machine (un dépôt par serveur depuis le lot 32)
  while IFS=$'\t' read -r from ids; do
    [[ "$from" =~ ^node[0-9]+$ && -f "$DATA_DIR/$from/config" ]] || continue
    RESTIC_FROM_PASSWORD=$(jq -r --arg f "$from" '.repos[] | select(.user == $f) | .repo_password' "$ETC/export.json")
    export RESTIC_FROM_PASSWORD
    if [[ ! -f "$dir/config" ]]; then
      restic -r "$dir" init --from-repo "$DATA_DIR/$from" --copy-chunker-params >/dev/null || { echo "  ! création de $user impossible"; continue; }
    fi
    # shellcheck disable=SC2086
    if restic -r "$dir" copy -q --from-repo "$DATA_DIR/$from" $ids; then
      echo "  $user : $(wc -w <<<"$ids") instantané(s) recopié(s) depuis $from"
    else
      echo "  ! recopie $from -> $user échouée"
    fi
    unset RESTIC_FROM_PASSWORD
  done < <(jq -r '[.copy // [] | .[] | select((.from | test("^node[0-9]+$")) and (.id | test("^[0-9a-f]{8,64}$")))]
                  | group_by(.from)[] | [.[0].from, (map(.id) | join(" "))] | @tsv' <<<"$repo")
  [[ -f "$dir/config" ]] || continue
  echo "== $user"
  restic -r "$dir" unlock >/dev/null 2>&1 || true
  if ! snaps=$(restic -r "$dir" snapshots --json 2>/dev/null); then
    echo "  ! lecture du dépôt impossible"; continue
  fi
  if jq -e 'has("policy")' <<<"$repo" >/dev/null; then
    # Dépôt du VPS lui-même
    restic -r "$dir" forget -q --group-by tags \
      --keep-last "$(jq -r '.policy.keep_last // 3' <<<"$repo")" \
      --keep-within-daily "$(jq -r '.policy.keep_daily // 7' <<<"$repo")d" \
      --keep-within-weekly "$(( $(jq -r '.policy.keep_weekly // 4' <<<"$repo") * 7 ))d" \
      || echo "  ! forget a échoué"
  else
    # Expirées selon l'API (identifiants courts ou longs)
    ids=$(jq -r --argjson f "$(jq -c '[.forget // [] | .[] | strings | select(test("^[0-9a-f]{8,64}$"))]' <<<"$repo")" \
      '.[] | .id as $id | select(any($f[]; . as $p | $id | startswith($p))) | $id' <<<"$snaps")
    # Inconnues de l'API depuis plus de 14 jours (ni gardées ni expirées)
    # (une copie depuis un ancien dépôt de machine est connue par son original)
    known=$(jq -c '[(.known // []), (.forget // []), ((.copy // []) | map(.id)) | .[] | strings | select(test("^[0-9a-f]{8,64}$"))]' <<<"$repo")
    unknown=$(jq -r --argjson k "$known" --argjson now "$now" "
      .[] | . as \$s | select(any(\$k[]; . as \$p | (\$s.id | startswith(\$p)) or ((\$s.original // \"\") | startswith(\$p))) | not)
      | select((.time | $TO_EPOCH) < (\$now - 14 * 86400)) | .id" <<<"$snaps")
    n_known=$(jq 'length' <<<"$known")
    n_unknown=$(grep -c . <<<"$unknown" || true)
    if (( n_unknown > 0 )); then
      if (( n_known == 0 || n_unknown > n_known + 5 )); then
        echo "  ! $n_unknown instantané(s) inconnu(s) de l'API : gardés par prudence (vérifie l'export du VPS)"
      else
        echo "  $n_unknown instantané(s) inconnu(s) de l'API depuis 14 jours : supprimés"
        ids=$(printf '%s\n%s\n' "$ids" "$unknown")
      fi
    fi
    ids=$(grep . <<<"$ids" | sort -u || true)
    if [[ -n "$ids" ]]; then
      echo "  $(grep -c . <<<"$ids") instantané(s) expiré(s) supprimé(s)"
      xargs -r restic -r "$dir" forget -q <<<"$ids" || echo "  ! forget a échoué"
    fi
  fi
  restic -r "$dir" prune -q --max-unused 10% || echo "  ! prune a échoué"
  chown -R mcs-backup:mcs-backup "$dir"
  # Ce qui reste, pour l'accusé (et l'original des copies)
  if all=$(restic -r "$dir" snapshots --json 2>/dev/null); then
    jq --arg u "$user" --argjson p "$(jq -c '[.[].id]' <<<"$all")" '.[$u] = $p' "$ack" > "$ack.n" && mv "$ack.n" "$ack"
    jq --arg u "$user" --argjson p "$(jq -c '[.[] | select(.original != null) | [.id, .original]]' <<<"$all")" \
      '.[$u] = $p' "$ack.o" > "$ack.n" && mv "$ack.n" "$ack.o"
  fi
done < <(jq -c '.repos[]' "$ETC/export.json")
# Accusé au VPS : les sauvegardes expirées dont l'instantané n'existe plus passent en DELETED
jq -n --arg g "$started" --slurpfile r "$ack" --slurpfile o "$ack.o" '{generated: $g, repos: $r[0], originals: $o[0]}' > "$ack.n"
if ssh -T -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=yes \
     -o UserKnownHostsFile="$ETC/known_hosts" -i "$ETC/id_ed25519" -p "$SSH_PORT" \
     "$SSH_USER@$VPS" ack < "$ack.n" >/dev/null; then
  echo "Tri terminé, accusé envoyé au VPS"
else
  echo "Tri terminé, mais accusé non envoyé (VPS injoignable ?)"
fi
SHEOF
  chmod 700 /usr/local/bin/mcs-backup-prune

  cat > /usr/local/bin/mcs-backup-status <<'SHEOF'
#!/usr/bin/env bash
set -uo pipefail
[[ $EUID -eq 0 ]] || { echo "Lance avec sudo"; exit 1; }
ETC=/etc/mcs-backup
# shellcheck disable=SC1091
source "$ETC/config.env"
echo "=== Services ==="
for s in mcs-rest-server mcs-backup-tunnel mcs-backup-sync.timer mcs-backup-prune.timer; do
  printf "  %-26s %s\n" "$s" "$(systemctl is-active "$s")"
done
echo
echo "=== Disque ($DATA_DIR) ==="
df -h --output=size,used,avail,pcent "$DATA_DIR" | sed 's/^/  /'
echo
echo "=== Dépôts ==="
export RESTIC_CACHE_DIR=/var/cache/mcs-backup
[[ -f "$ETC/export.json" ]] || { echo "  pas encore d'export du VPS"; exit 0; }
while IFS=$'\t' read -r user pass; do
  dir="$DATA_DIR/$user"
  if [[ ! -f "$dir/config" ]]; then
    printf "  %-8s (aucune sauvegarde encore)\n" "$user"; continue
  fi
  n=$(RESTIC_PASSWORD="$pass" restic -r "$dir" snapshots --json 2>/dev/null | jq 'length')
  last=$(RESTIC_PASSWORD="$pass" restic -r "$dir" snapshots --json --latest 1 2>/dev/null | jq -r 'map(.time[0:16]) | max // "?"')
  printf "  %-8s %6s  %3s instantanés  dernier : %s\n" "$user" "$(du -sh "$dir" 2>/dev/null | cut -f1)" "${n:-?}" "$last"
done < <(jq -r '.repos[] | [.user, .repo_password] | @tsv' "$ETC/export.json")
SHEOF
  chmod 755 /usr/local/bin/mcs-backup-status
  ok "mcs-backup-sync, mcs-backup-prune, mcs-backup-status"
}

install_services() {
  step "Services"
  local listen_host=${LISTEN%%:*} listen_port=${LISTEN##*:}
  cat > /etc/systemd/system/mcs-rest-server.service <<EOF
[Unit]
Description=MCS - dépôts de sauvegarde (rest-server, ajout seul)
After=network-online.target
Wants=network-online.target

[Service]
User=$SVC_USER
Group=$SVC_USER
ExecStart=$REST_BIN --path $DATA_DIR --listen 127.0.0.1:8000 --append-only --private-repos --htpasswd-file $ETC/htpasswd
Restart=always
RestartSec=5
NoNewPrivileges=yes
ProtectSystem=strict
ReadWritePaths=$DATA_DIR
ProtectHome=yes
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
  # Tunnel : publie rest-server sur le VPS ($LISTEN), reconnexion automatique
  cat > /etc/systemd/system/mcs-backup-tunnel.service <<EOF
[Unit]
Description=MCS - tunnel SSH vers le VPS (sauvegardes)
After=network-online.target mcs-rest-server.service
Wants=network-online.target

[Service]
User=$SVC_USER
ExecStart=/usr/bin/ssh -N -T -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -o ConnectTimeout=20 -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$ETC/known_hosts -i $ETC/id_ed25519 -p $SSH_PORT -R $listen_host:$listen_port:127.0.0.1:8000 $SSH_USER@$VPS
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
  cat > /etc/systemd/system/mcs-backup-sync.service <<'EOF'
[Unit]
Description=MCS - identifiants et rétention depuis le VPS
After=network-online.target
[Service]
Type=oneshot
ExecStart=/usr/local/bin/mcs-backup-sync
EOF
  cat > /etc/systemd/system/mcs-backup-sync.timer <<'EOF'
[Unit]
Description=MCS - synchronisation avec le VPS (toutes les 2 min)
[Timer]
OnBootSec=1min
OnUnitActiveSec=2min
[Install]
WantedBy=timers.target
EOF
  cat > /etc/systemd/system/mcs-backup-prune.service <<'EOF'
[Unit]
Description=MCS - tri des vieilles sauvegardes
[Service]
Type=oneshot
Nice=10
IOSchedulingClass=idle
ExecStart=/usr/local/bin/mcs-backup-prune
EOF
  cat > /etc/systemd/system/mcs-backup-prune.timer <<'EOF'
[Unit]
Description=MCS - tri des sauvegardes chaque nuit
[Timer]
OnCalendar=*-*-* 05:15
Persistent=true
[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now mcs-rest-server >/dev/null 2>&1
  systemctl restart mcs-rest-server
  if /usr/local/bin/mcs-backup-sync; then
    ok "Identifiants récupérés sur le VPS"
  else
    warn "Lecture sur le VPS impossible pour l'instant (nouvel essai toutes les 2 min)"
  fi
  systemctl enable --now mcs-backup-sync.timer mcs-backup-prune.timer >/dev/null 2>&1
  systemctl enable mcs-backup-tunnel >/dev/null 2>&1
  systemctl restart mcs-backup-tunnel
  sleep 5
  if systemctl is-active --quiet mcs-backup-tunnel; then
    ok "Tunnel vers le VPS actif ($LISTEN)"
  else
    warn "Tunnel pas encore actif : journalctl -u mcs-backup-tunnel -n 30"
  fi
}

summary() {
  step "Terminé"
  cat <<EOF

  Sauvegardes gardées dans : $DATA_DIR
  État ici :            sudo mcs-backup-status
  État côté VPS :       sudo mcs-backup-status   (sur le VPS)
  Tri manuel :          sudo mcs-backup-prune     (automatique chaque nuit)

  À garder précieusement : $ETC (mots de passe des dépôts, sans eux les
  sauvegardes sont illisibles). Le VPS en a aussi une copie.

EOF
}

if $UPDATE; then
  step "Mise à jour (configuration gardée : $ETC)"
  # shellcheck disable=SC1091
  source "$ETC/config.env"
  prerequisites
  install_tools
  install_services
  summary
else
  decode_code
  prerequisites
  choose_storage
  setup_files
  install_tools
  install_services
  summary
fi
