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
Il affiche une commande `curl ... -o node-setup.sh && sudo bash node-setup.sh <CODE>`.
Copie-la et lance-la sur la **machine volontaire**. Elle détecte seule son port SSH,
configure le tunnel rathole et l'agent, puis se connecte au VPS.

La commande installe aussi Docker s'il manque, et l'agent MCS (téléchargé depuis
la release GitHub `agent-latest`, compilée automatiquement par GitHub Actions à
chaque modification de `agent/`). Pour mettre à jour l'agent d'une machine déjà
jumelée :
```bash
curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh -o node-setup.sh && sudo bash node-setup.sh --update-agent
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
| `sudo mcs-add-node [--ssh] [--owner <pseudo>] [nom]` | Code de jumelage pour une nouvelle machine |
| `sudo mcs-node-owner [<id> <pseudo>\|--none]` | Joueur propriétaire (hébergeur) d'une machine |
| `sudo mcs-add-node --renew <id>` | Nouveau code pour une machine existante |
| `sudo mcs-disk-quota [<nom> <Mo>]` | Quotas disque des serveurs |
| `sudo mcs-remove-node <id>` | Révoque une machine (jeton refusé, tunnels supprimés) |
| `sudo mcs-admin <pseudo>` | Groupe admin LuckPerms (vérifié) |
| `sudo mcs-lp-export` | État LuckPerms en JSON (groupes, joueurs) |
| `sudo mcs-rcon "<commande>"` | Commande dans la console du lobby |

## Capacité des machines et quotas disque

Chaque volontaire choisit, au jumelage, la part de sa machine qu'il prête (RAM, cœurs
CPU, disque ; proposé : la moitié). Le script affiche ce qui est disponible, accepte les
réponses en Go (`8G`) ou en Mo (`8192`), et garantit qu'il reste toujours au volontaire
au moins 10 % de sa RAM, 10 % de son disque et un cœur (l'agent applique les mêmes
limites). Pour changer d'avis plus tard, sur la machine :
```bash
curl -fsSL https://raw.githubusercontent.com/Vinchenzo06/mcs-deploy/main/deploy/node-setup.sh -o node-setup.sh && sudo bash node-setup.sh --capacity
```

Un nouveau serveur va sur la machine en ligne qui a la place et garde le plus de RAM
libre ; s'il n'y en a aucune, la création est refusée.

La RAM choisie par le joueur (1 Go minimum) est la **limite totale** de son serveur :
rien n'est ajouté par-dessus, c'est exactement ce qui est compté sur la machine et dans
son quota. Java n'en reçoit qu'une partie pour son tas (`-Xmx`) : la JVM a besoin de
mémoire hors du tas (classes, code compilé, threads, tampons réseau, GC), soit 20 % de
la RAM, au moins 512 Mo et au plus 3 Go. Exemples : 2 Go → tas 1,5 Go ; 4 Go → tas
3,2 Go ; 8 Go → tas 6,4 Go.

Chaque serveur reçoit un quota disque **proportionnel à sa RAM** : s'il occupe 10 % de la
RAM prêtée par sa machine, il reçoit 10 % de son disque prêté (le joueur ne choisit pas son
disque ; la somme des quotas ne dépasse jamais le disque prêté). Mesuré toutes les 10 minutes : au-delà,
il ne peut plus démarrer ; au-delà de 110 %, il est arrêté proprement.

**Réserve de sécurité** : une machine n'est remplie qu'à 90 % de ce que le volontaire
prête (RAM, CPU, disque ; réglable avec `MCS_NODES_RESERVE_PERCENT`). Comme les quotas
disque suivent la RAM, même si tous les serveurs dépassaient leur quota en même temps,
ils n'occuperaient au plus que 90 % × 110 % = 99 % du disque prêté, qui laisse lui-même
au moins 10 % du disque libre au volontaire. La RAM est une limite dure (Docker). Voir ou changer les
quotas sur le VPS : `sudo mcs-disk-quota [<nom> <Mo>]`. Vue d'ensemble : `sudo mcs-status`.

## Cohérence machines ↔ API

**En direct** : l'agent suit les événements Docker et prévient l'API à la seconde
près quand un serveur démarre, devient joignable (healthcheck de l'image : Minecraft
répond), plante ou s'arrête. Un serveur n'est ouvert dans Velocity que lorsqu'il est
réellement joignable. Toutes les 30 s, l'agent envoie aussi les mesures (CPU, RAM,
réseau ; disque toutes les 10 min), visibles avec `/mcs info <nom>` et sur la route
`GET /api/v1/servers/{id}/stats` (future base du panneau web).

En filet de sécurité, à chaque connexion (puis toutes les 5 minutes), l'agent envoie l'inventaire de ses
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

## Accès aux serveurs

Un serveur est **privé** à sa création. Le proxy vérifie auprès de l'API chaque
connexion à un serveur joueur (`/mcs join`, `/goto`, `/server`...) : impossible à
contourner, et refusé si l'API ne répond pas.

| | rejoindre | start/stop | console, fichiers | inviter | retirer | public/privé | supprimer | amener un joueur |
|---|---|---|---|---|---|---|---|---|
| propriétaire (créateur) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| admin (`mcs.admin`) | ✔ | ✔ | ✔ | | ✔ | ✔ | ✔ | ✔ |
| hébergeur (sa machine) | ✔ | ✔ | ✔ | | | | | ✔ |
| technicien (invité) | ✔ | ✔ | ✔ | | | | | |
| gérant (invité) | ✔ | ✔ | | | | | | |
| membre (invité) | ✔ | | | | | | | |
| tout le monde | ✔ si public | | | | | | | |

- Invités : `/mcs invite <serveur> <joueur> [membre|gerant|technicien]` (relancer change
  le rôle), `/mcs remove`, `/mcs members`, `/mcs leave` ; `/mcs public|private`.
- Serveur d'un autre joueur : `pseudo/nom` (ex. `/mcs join bob/survie`) ; le nom seul
  suffit s'il n'y a pas d'ambiguïté.
- Console : `/mcs console <serveur> <commande>` (rcon-cli dans le conteneur, rien
  d'exposé). L'hébergeur peut s'y mettre OP (`op <pseudo>`) ; le créateur et les
  admins le sont automatiquement.
- `/mcs move <joueur> <serveur>` (hébergeur, admins) : envoie un joueur connecté sur le
  serveur avec un laissez-passer d'une minute, à usage unique. Il ne peut pas revenir seul.
- L'**hôte** d'une machine est choisi **uniquement par un admin** : en jeu `/mcs host list`,
  `/mcs host set <machine> <joueur>`, `/mcs host remove <machine>` (ou sur le VPS
  `sudo mcs-node-owner <id> <pseudo>`). Le volontaire ne peut pas se déclarer hôte ni nommer
  quelqu'un d'autre. Ses droits ne valent que pour les serveurs de SA machine
  (le joueur doit s'être connecté une fois). Il reçoit le groupe LuckPerms `host` (titre
  `ʜᴏsᴛ`), retiré quand il ne possède plus de machine.
- Les fichiers (rôle technicien, hébergeur) arriveront avec le panneau web.

### Sur les serveurs de jeu (sans plugin imposé)

- `/mcs` est une commande du **proxy** : elle marche depuis le lobby et depuis tous les
  serveurs de jeu (Velocity la traite avant le serveur).
- En arrivant sur un serveur, par sa console : le **créateur** et les **admins** sont OP ;
  un **titre** discret s'affiche (petites capitales, dans le chat, le Tab et au-dessus de la
  tête) grâce aux équipes vanilla `mcs_<ordre><titre>` : `ᴀᴅᴍɪɴ` passe avant `ᴏᴡɴᴇʀ` (créateur du
  serveur), qui passe avant le rang réseau (`ʜᴏsᴛ`, `ᴘʀᴇᴍɪᴜᴍ`, `ᴠɪᴘ`). Même titre dans le lobby. Le Tab est trié dans cet ordre. Les commandes de
  MCS ne s'affichent pas aux OP (`broadcast-rcon-to-ops=false`). Paper, Fabric, Forge, Vanilla.
- Couleur du pseudo : gris pour tout le monde (seul le titre est coloré) ; modifiable par groupe
  avec la méta LuckPerms `name-color` (ex. `lp group vip meta set name-color green`). Limite : **Paper n'affiche pas les équipes dans le chat** (seulement Tab et
  au-dessus de la tête) ; Fabric, Forge et Vanilla les affichent aussi dans le chat. Le lobby,
  lui, affiche titre et couleur partout.
- Le propriétaire garde la main sur son serveur (son propre LuckPerms, ses rôles...). S'il
  utilise ses propres équipes : `/mcs display <serveur> off`.

### LuckPerms du réseau

Proxy et lobby partagent la même base LuckPerms (PostgreSQL `luckperms`, sur le VPS
seulement). Les serveurs de jeu n'y sont **jamais** branchés : leur propriétaire a accès aux
fichiers, donc au mot de passe. Titre d'un groupe : `lp group vip meta setprefix 20 "&aᴠɪᴘ "`
(dans `sudo mcs-rcon`). L'étape `bootstrap` ne réécrit plus les quotas et préfixes déjà réglés.

## Sauvegardes

Un **serveur de sauvegarde** (chez toi) garde des sauvegardes chiffrées (restic) de tous
les serveurs joueurs et du VPS. Il ouvre lui-même un tunnel SSH vers le VPS : rien à
ouvrir sur ta box, et les volontaires ne voient jamais son adresse.

```
machine volontaire ──wg-mcs──▶ VPS 10.99.0.1:8100 ──tunnel SSH──▶ serveur de sauvegarde (rest-server)
```

Mise en place, une fois :
1. Sur le VPS : `sudo mcs-add-backup` (affiche une commande avec un code).
2. Sur le serveur de sauvegarde (Ubuntu) : la commande affichée,
   `curl ... backup-setup.sh -o backup-setup.sh && sudo bash backup-setup.sh <CODE>`.
   Il demande le dossier de stockage (défaut `/srv/mcs-backup`).

- Chaque machine a son dépôt (`node<id>`), en **ajout seul** : elle ne peut ni lire les
  autres dépôts ni effacer ses sauvegardes. Le tri se fait sur le serveur de sauvegarde.
- Pendant la copie, l'écriture du monde est suspendue quelques secondes (`save-off`), le
  serveur ne s'arrête pas. Un serveur arrêté est sauvegardé une fois après son arrêt.
- **Types** : chacun a un nombre max (au-delà, la plus ancienne est supprimée) et une
  durée de vie. Une sauvegarde peut compter pour plusieurs types (la quotidienne du lundi
  est aussi l'hebdomadaire) ; elle part quand plus aucun ne la retient. La plus récente
  d'un serveur n'est jamais supprimée.

  | Type | Défaut du réseau | Qui la lance |
  |---|---|---|
  | quotidienne | 1 par jour, 3 max, 3 jours | automatique |
  | hebdomadaire | 1 par semaine, 2 max, 14 jours | automatique |
  | mensuelle | coupée | automatique |
  | manuelle | 2 max, 3 jours | propriétaire, gérants, techniciens, hôte, admins |
  | permanente | 1 max, gardée tant que le serveur existe, puis 24 h | propriétaire, admins |

- **Qui règle quoi** : les admins fixent les défauts du réseau et les limites de chaque
  rôle ; le propriétaire règle son serveur sans dépasser les limites de son rôle ; les
  admins règlent tout. Invités et hôte ne changent rien (ils peuvent lancer une manuelle).
- **Suppression** : c'est l'API qui décide ce qui expire. Le serveur de sauvegarde
  supprime chaque nuit les instantanés expirés, puis confirme au VPS ce qui reste
  (EXPIRED → DELETED). Un instantané inconnu de l'API part après 14 jours.
- En jeu :
  - `/mcs backup <serveur>` : sauvegarde manuelle ;
  - `/mcs backups <serveur>` : historique (n°, types, expiration ; ☆/★ pour garder) ;
  - `/mcs backup keep <serveur> [n°]` / `unkeep <serveur> <n°>` : permanente ;
  - `/mcs backup settings <serveur>`, `/mcs backup set <serveur> <type> <max> [durée]`
    (ex. `quotidienne 5 7j`, `permanente 1 48h`, `0` = coupée) ou `set <serveur> reset` ;
  - admins : `/mcs backup defaults [type max durée]`,
    `/mcs backup limits <rôle> [type max durée | reset]`.
- Mise à jour du serveur de sauvegarde (sans code) :
  `curl ... backup-setup.sh -o backup-setup.sh && sudo bash backup-setup.sh --update`.
- Le VPS (bases, `/etc/mcs`, lobby, proxy) est sauvegardé chaque nuit (`sudo mcs-backup-vps`).
- État : `sudo mcs-backup-status` (sur le VPS et sur le serveur de sauvegarde).
- **À garder précieusement** : `/etc/mcs-backup` sur le serveur de sauvegarde (mots de
  passe des dépôts). Le VPS en a aussi une copie.

## Limites connues

- Sans serveur de sauvegarde (`mcs-add-backup`), la base et `/etc/mcs/secrets.env` ne sont que sur le VPS.
- L'API est en HTTP clair sur le port 8081 (protégée par clé). À terme : TLS.
- Le code de jumelage contient des secrets et reste valable : à terme, un code
  à usage unique qui expire.
