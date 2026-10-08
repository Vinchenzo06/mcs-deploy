#!/usr/bin/env bash
# =============================================================================
#  MCS - Première commande sur un VPS neuf (Ubuntu 24.04)
#
#    curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/bootstrap.sh -o bootstrap.sh && sudo bash bootstrap.sh
#
#  Repo privé : ajoute un token GitHub (lecture seule) :
#    curl -fsSL -H "Authorization: Bearer TOKEN" https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/bootstrap.sh \
#      -o bootstrap.sh && sudo GITHUB_TOKEN=TOKEN bash bootstrap.sh
#
#  Résultat : le dépôt est cloné dans /opt/mcs-deploy et la commande
#  "mcs-deploy" est installée. Rien n'est encore installé côté Minecraft.
# =============================================================================
set -euo pipefail

REPO="${MCS_REPO:-Vinchenzo06/mcs-deploy}"
BRANCH="${MCS_BRANCH:-main}"
INSTALL_DIR=/opt/mcs-deploy
ETC_DIR=/etc/mcs

[[ $EUID -eq 0 ]] || { echo "Lance avec sudo"; exit 1; }

echo "==> Paquets de base"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq git curl jq ca-certificates >/dev/null

mkdir -p "$ETC_DIR"
chmod 700 "$ETC_DIR"
cat > "$ETC_DIR/deploy.env" <<CONF
MCS_REPO=$REPO
MCS_BRANCH=$BRANCH
GITHUB_TOKEN=${GITHUB_TOKEN:-}
CONF
chmod 600 "$ETC_DIR/deploy.env"

git_auth=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  git_auth=(-c "http.extraHeader=Authorization: Basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)")
fi

echo "==> Récupération de $REPO ($BRANCH)"
if [[ -d "$INSTALL_DIR/.git" ]]; then
  git -C "$INSTALL_DIR" reset -q --hard
  git "${git_auth[@]}" -C "$INSTALL_DIR" pull --ff-only -q
else
  rm -rf "$INSTALL_DIR"
  git "${git_auth[@]}" clone -q --depth 1 -b "$BRANCH" "https://github.com/$REPO.git" "$INSTALL_DIR"
fi

# Lanceur indépendant des permissions des fichiers (venant de Windows)
printf '#!/bin/sh\nexec bash %s/deploy/mcs-deploy "$@"\n' "$INSTALL_DIR" > /usr/local/bin/mcs-deploy
chmod 755 /usr/local/bin/mcs-deploy

cat <<MSG

  Dépôt récupéré dans $INSTALL_DIR

  Commande suivante :
    sudo mcs-deploy

MSG
