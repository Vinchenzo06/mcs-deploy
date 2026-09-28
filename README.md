# MCS - Minecraft Community Server

Dépôt unique du projet : le code (API, plugins, agent) et le kit qui installe le VPS.

```
mcs-deploy/
├── api/             API Spring Boot        (ancien dossier mcs-api)
├── proxymanager/    plugin Velocity        (ancien dossier proxymanager)
├── lobby-plugin/    plugin du lobby Paper  (ancien dossier mcs-lobby-plugin)
├── agent/           agent Go des volontaires (ancien dossier mcs-agent)
└── deploy/          scripts d'installation du VPS et des machines volontaires
```

Le VPS récupère ce dépôt et **compile lui-même** l'API et les plugins.
Aucun secret n'est dans le dépôt : ils sont générés sur le VPS (`/etc/mcs/secrets.env`).

---

## 1. Installer un VPS (Termius, sur le VPS)

```bash
curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/bootstrap.sh | sudo bash
sudo mcs-deploy
```

Compte 5 à 10 minutes. À la fin, le serveur Minecraft répond sur `IP_DU_VPS:25565`.

## 2. Connecter une machine volontaire

Sur le **VPS** :
```bash
sudo mcs-add-node --ssh maison
```
Il affiche une commande `curl ... | sudo bash -s -- <CODE>`.
Copie-la et lance-la sur la **machine volontaire**. Elle détecte seule son port SSH,
configure le tunnel rathole et l'agent, puis se connecte au VPS.

La commande installe aussi Docker s'il manque, et l'agent MCS (téléchargé depuis
la release GitHub `agent-latest`, compilée automatiquement par GitHub Actions à
chaque modification de `agent/`). Pour mettre à jour l'agent d'une machine déjà
jumelée :
```bash
curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh | sudo bash -s -- --update-agent
```

Les serveurs joueurs tournent isolés : réseau Docker dédié, sans accès aux autres
serveurs, à la machine ni au réseau local du volontaire, utilisateur non-root,
limites de RAM et de processus. Ils sortent sur Internet **par le VPS** (tunnel
WireGuard `wg-mcs`) : l'IP du volontaire n'est jamais visible. Si le tunnel tombe,
ils n'ont plus Internet (coupe-circuit) au lieu de sortir par la connexion du volontaire.
Le courriel sortant (SMTP) est bloqué.

Nouveau code pour une machine déjà jumelée (code divulgué, mise à niveau) :
`sudo mcs-add-node --renew <id>` (mêmes ports, ses serveurs sont conservés).

Chaque machine reçoit sa propre plage de ports et son propre jeton rathole :
elle ne peut pas intercepter les serveurs d'une autre. Pour retirer une machine :
`sudo mcs-remove-node <id>` (les id sont affichés par `sudo mcs-status`).

`--ssh` : la machine devient joignable depuis l'extérieur avec `ssh -p 2222 <user>@IP_DU_VPS`.
Une seule machine peut avoir cette option à la fois.

## 3. Te donner les droits admin

Connecte-toi une fois au serveur en jeu, puis sur le **VPS** :
```bash
sudo mcs-admin TonPseudo
```

---

## Au quotidien

1. Tu modifies le code dans IntelliJ.
2. **Git > Commit** (coche "Push" ou fais **Git > Push** ensuite).
3. Sur le VPS :

| Tu as modifié | Commande sur le VPS |
|---|---|
| l'API (`api/`) | `sudo mcs-deploy api` |
| le plugin lobby (`lobby-plugin/`) | `sudo mcs-deploy lobby` |
| le plugin Velocity (`proxymanager/`) | `sudo mcs-deploy velocity` |
| les scripts (`deploy/`) ou plusieurs choses | `sudo mcs-deploy` |

## Commandes du VPS

| Commande | Rôle |
|---|---|
| `sudo mcs-deploy [étapes]` | Récupère GitHub, compile, installe |
| `sudo mcs-status` | État des services, ports et machines volontaires |
| `sudo mcs-add-node [--ssh] [nom]` | Code de jumelage pour une nouvelle machine |
| `sudo mcs-add-node --renew <id>` | Nouveau code pour une machine existante |
| `sudo mcs-remove-node <id>` | Révoque une machine (jeton refusé, tunnels supprimés) |
| `sudo mcs-admin <pseudo>` | Groupe admin LuckPerms (vérifié) |
| `sudo mcs-lp-export` | État LuckPerms en JSON (groupes, joueurs) |
| `sudo mcs-rcon "<commande>"` | Commande dans la console du lobby |

## Cohérence machines ↔ API

À chaque connexion (puis toutes les 5 minutes), l'agent envoie l'inventaire de ses
conteneurs. L'API corrige les statuts (serveur arrêté ou redémarré hors de son
contrôle, conteneur disparu → `ERROR`) et met en **quarantaine** les conteneurs
qu'elle ne connaît pas : arrêtés, renommés `mcs-orphan-<id>-<date>`, **données
conservées** (jamais de suppression automatique). Pour les supprimer, sur la machine :
`docker rm mcs-orphan-…` puis le dossier `/opt/mcs-data/servers/<id>`.

Un serveur ne peut pas être supprimé pendant que sa machine est hors ligne (sauf
machine révoquée).

## Vie privée des joueurs

- Les serveurs joueurs ne reçoivent jamais l'IP réelle des joueurs : le plugin
  proxymanager la remplace par une pseudo-IP stable (`10.x.y.z`, dérivée d'une clé
  secrète du VPS). Un `ban-ip` sur un serveur fonctionne toujours. Si ce masquage ne
  peut pas s'installer (version de Velocity incompatible), aucun serveur joueur n'est
  enregistré.
- Le canal « BungeeCord » de Velocity est désactivé : il permettait à n'importe quel
  serveur de lire l'IP de tous les joueurs du réseau, de les expulser ou de les
  déplacer. Le lobby utilise son propre canal `mcs:connect`, accepté uniquement
  depuis le lobby.

## HTTPS de l'API

Avec `API_DOMAIN=api.ton-domaine` dans `/etc/mcs/mcs.env` (enregistrement DNS A vers
le VPS), `sudo mcs-deploy` installe Caddy avec un certificat Let's Encrypt : les agents
se connectent en `wss://`, et l'API n'est plus joignable depuis Internet (seul le
canal des agents est exposé). Les machines déjà jumelées doivent recevoir un nouveau
code (`sudo mcs-add-node --renew <id>`) pour passer en HTTPS.

## Réglages

Tout fonctionne sans configuration. Pour changer un réglage commun à la plateforme
(versions, mémoire, quotas...), copie `deploy/mcs.env.example` vers `/etc/mcs/mcs.env`
sur le VPS, modifie-le, puis relance `sudo mcs-deploy`.

## Limites connues

- La base PostgreSQL et `/etc/mcs/secrets.env` ne sont que sur le VPS : **à sauvegarder ailleurs**.
- L'API est en HTTP clair sur le port 8081 (protégée par clé). À terme : TLS.
- Le code de jumelage contient des secrets et reste valable : à terme, un code
  à usage unique qui expire.
