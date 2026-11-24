# =============================================================================
#  appliquer-patchs.ps1 (lot 69) : applique les patchs en attente dans les deux
#  dépôts, dans l'ordre, puis pousse sur GitHub.
#
#    Les patchs arrivent dans :
#      <mcs-deploy>\patchs\   (ex. 70-mises-a-jour.patch)
#      <img-farmdb>\patchs\   (ex. farmdb-27-....patch)
#
#    Depuis le dossier mcs-deploy :
#      powershell -ExecutionPolicy Bypass -File outils\appliquer-patchs.ps1
#      powershell -ExecutionPolicy Bypass -File outils\appliquer-patchs.ps1 -SansPush
#
#    Chaque patch appliqué est rangé dans patchs\appliques\. Au premier échec, le
#    script s'arrête sans rien pousser pour ce dépôt.
#    Ensuite, sur le VPS : sudo img-update
# =============================================================================
param(
  [string]$Mcs = (Split-Path -Parent $PSScriptRoot),
  [string]$Farmdb = (Join-Path $env:USERPROFILE 'Downloads\img-farmdb'),
  [switch]$SansPush
)

$ErrorActionPreference = 'Continue'

function Get-Numero([string]$nom) {
  if ($nom -match '(\d+)') { return [int]$Matches[1] }
  return 0
}

function Invoke-Depot([string]$depot, [string]$titre) {
  if (-not (Test-Path (Join-Path $depot '.git'))) {
    Write-Host "$titre : dépôt introuvable ($depot), ignoré" -ForegroundColor Yellow
    return $true
  }
  $dossier = Join-Path $depot 'patchs'
  $patchs = @()
  if (Test-Path $dossier) {
    $patchs = @(Get-ChildItem -Path $dossier -Filter '*.patch' -File |
      Sort-Object @{ Expression = { Get-Numero $_.Name } }, Name)
  }
  if ($patchs.Count -eq 0) {
    Write-Host "$titre : aucun patch en attente"
    return $true
  }
  if (Test-Path (Join-Path $depot '.git\rebase-apply')) {
    Write-Host "$titre : un « git am » précédent n'est pas terminé." -ForegroundColor Red
    Write-Host "  Dans $depot : git am --abort   (puis relance ce script)"
    return $false
  }
  $faits = Join-Path $dossier 'appliques'
  New-Item -ItemType Directory -Force -Path $faits | Out-Null

  foreach ($p in $patchs) {
    Write-Host "$titre : $($p.Name)" -ForegroundColor Cyan
    & git -C $depot am $p.FullName | Out-Host
    if ($LASTEXITCODE -ne 0) {
      Write-Host "$titre : échec sur $($p.Name). Rien n'a été poussé pour ce dépôt." -ForegroundColor Red
      Write-Host "  Pour revenir en arrière : git -C `"$depot`" am --abort"
      Write-Host "  Puis envoie-moi le message d'erreur ci-dessus."
      return $false
    }
    Move-Item -Force -Path $p.FullName -Destination $faits
  }

  if (-not $SansPush) {
    & git -C $depot push | Out-Host
    if ($LASTEXITCODE -ne 0) {
      Write-Host "$titre : patchs appliqués mais « git push » a échoué." -ForegroundColor Red
      return $false
    }
  }
  Write-Host "$titre : $($patchs.Count) patch(s) appliqué(s)" -ForegroundColor Green
  return $true
}

$okMcs = Invoke-Depot $Mcs 'MCS'
$okFarmdb = Invoke-Depot $Farmdb 'Site IMG'
Write-Host ''
if ($okMcs -and $okFarmdb) {
  if ($SansPush) { Write-Host 'Terminé (sans push).' -ForegroundColor Green }
  else { Write-Host 'Terminé. Sur le VPS : sudo img-update' -ForegroundColor Green }
  exit 0
}
exit 1
