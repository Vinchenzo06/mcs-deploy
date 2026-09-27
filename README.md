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
| `sudo mcs-admin <pseudo>` | Groupe admin LuckPerms |
| `sudo mcs-rcon "<commande>"` | Commande dans la console du lobby |

## Réglages

Tout fonctionne sans configuration. Pour changer un réglage commun à la plateforme
(versions, mémoire, quotas...), copie `deploy/mcs.env.example` vers `/etc/mcs/mcs.env`
sur le VPS, modifie-le, puis relance `sudo mcs-deploy`.

## Limites connues

- La base PostgreSQL et `/etc/mcs/secrets.env` ne sont que sur le VPS : **à sauvegarder ailleurs**.
- L'API est en HTTP clair sur le port 8081 (protégée par clé). À terme : TLS.
- Toutes les machines volontaires partagent le même token rathole.
- Le code de jumelage contient des secrets et reste valable : à terme, un code
  à usage unique qui expire.
