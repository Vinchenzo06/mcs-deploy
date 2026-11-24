#!/usr/bin/env bash
# Lot 72 : liste des versions à compiler = img-farm-mod/versions.json + chaque version stable de
# Minecraft plus récente que la dernière de la liste (au plus 4), avec sa Fabric API (Modrinth) et
# le dernier Fabric Loader. Rien n'est écrit dans le dépôt : les versions ajoutées ainsi sont
# recalculées à chaque passage, jusqu'à ce qu'on les mette dans versions.json.
# Sortie (GITHUB_OUTPUT) : matrix (JSON) et skip=true si une exécution planifiée n'a rien de neuf.
set -uo pipefail
FILE=${1:-img-farm-mod/versions.json}
EVENT=${EVENT:-push}
META=${META:-https://meta.fabricmc.net/v2/versions}
MODRINTH=${MODRINTH:-https://api.modrinth.com/v2}
out=${GITHUB_OUTPUT:-/dev/stdout}

list=$(jq -c . "$FILE")
newest=$(jq -r '.[].minecraft' "$FILE" | sort -V | tail -1)
added=0
games=$(curl -fsSL --retry 2 "$META/game" 2>/dev/null | jq -r '.[] | select(.stable) | .version' 2>/dev/null || true)
loader=$(curl -fsSL --retry 2 "$META/loader" 2>/dev/null | jq -r '[.[] | select(.stable)][0].version // empty' 2>/dev/null || true)
if [ -z "$games" ]; then
  echo "::warning::Versions de Minecraft illisibles (meta.fabricmc.net) : seulement celles de versions.json"
fi
newer=$(printf '%s\n' $games | while read -r v; do
  [ -n "$v" ] && [ "$v" != "$newest" ] && [ "$(printf '%s\n%s\n' "$newest" "$v" | sort -V | tail -1)" = "$v" ] && echo "$v"
done | sort -V | tail -4)
for v in $newer; do
  api=$(curl -fsSL --retry 2 -G "$MODRINTH/project/fabric-api/version" \
          --data-urlencode "game_versions=[\"$v\"]" --data-urlencode 'loaders=["fabric"]' 2>/dev/null \
        | jq -r '([.[] | select(.version_type == "release")][0] // .[0]).version_number // empty' 2>/dev/null || true)
  if [ -z "$api" ]; then
    echo "::warning title=Minecraft $v::Fabric API pas encore sortie pour $v : ce sera pour le prochain passage (lundi) ou relance l'action « Mod »."
    continue
  fi
  list=$(jq -c --arg v "$v" --arg a "$api" --arg l "$loader" '. + [{minecraft: $v, fabric_api: $a, loader: $l, auto: true}]' <<<"$list")
  echo "::notice title=Minecraft $v::Pas encore dans versions.json : compilée automatiquement (Fabric API $api, Loader $loader)."
  added=$((added + 1))
done
skip=false
# Le passage du lundi ne republie rien s'il n'y a pas de nouvelle version
if [ "$EVENT" = "schedule" ] && [ "$added" -eq 0 ]; then
  skip=true
fi
{
  echo "matrix=$list"
  echo "skip=$skip"
} >> "$out"
