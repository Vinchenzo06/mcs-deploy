# IMG Farm — mod client Fabric (lot 64)

Les sessions de farm du plugin `img-farm` (`/farm`), mais sur **n'importe quel serveur**, même en solo :
tout se passe chez le joueur, rien à installer sur le serveur. Les sessions arrivent sur le site de la
team (Farming → Mes sessions), comme celles du plugin.

- **Fabric seulement** (pas Forge), Minecraft **26.2 et plus**, avec [Fabric API](https://modrinth.com/mod/fabric-api).
- Téléchargement : release GitHub [`mod-latest`](https://github.com/Vinchenzo06/mcs-deploy/releases/tag/mod-latest),
  un fichier par version de Minecraft (`img-farm-mod-1.0.0+mc26.3.jar`…). Pas de fichier pour ta version ?
  Le plus récent se charge aussi sur les versions suivantes et marche en général.

## Sur les serveurs de la team : /farm

Les serveurs de la team ont le plugin `img-farm` : la même session se mesure avec `/farm`, côté serveur.
Le mod le voit (les commandes `/farm` et `/menu` du plugin sont dans l'arbre de commandes du serveur) :
`/imgfarm start`, `auto` ou `construction` y proposent `/farm` (bouton) au lieu de lancer une 2e session
qui compterait la même chose. Une session du mod déjà en cours continue, avec un avertissement une fois.

## Lier le mod au site

1. Sur le site : **Mon profil → Mod Fabric → Lier le mod** (code de 8 caractères, 10 minutes).
2. En jeu : `/imgfarm lier CODE`. Le mod reçoit une clé secrète gardée dans `config/img-farm.json`
   (ne la partage pas). Les appareils liés sont listés dans le profil (Retirer).
3. `/imgfarm compte` : à quel compte le mod est lié ; `/imgfarm delier` : oublie la clé.

`/imgfarm lier` doit être **tapé** : un lien cliquable envoyé par un serveur ne peut pas lier le mod à un
autre compte.

## Commandes

| Commande | Effet |
|---|---|
| `/imgfarm <item>` | les meilleures méthodes du site pour cet item (`/imgfarm fer`) |
| `/imgfarm start <item> [30m \| 640]` | farm à la main |
| `/imgfarm auto <item> [30m \| 640]` | ferme auto, en regardant le coffre de collecte |
| `/imgfarm coffre` | ajoute un autre coffre de collecte (regarde-le) |
| `/imgfarm construction [base \| module]` | chrono de construction |
| `/imgfarm pause \| reprendre \| stop \| annuler` | |
| `/imgfarm type <type>`, `envoyer`, `jeter` | à la fin (boutons dans le chat) |
| `/imgfarm hud` | affiche ou cache le cadre en haut à gauche |

Quitter le serveur met la session en pause ; elle est gardée dans `config/img-farm-session.json`
(même si le jeu plante) et revient à la connexion suivante.

## Ce qui compte

Comme le plugin, un item à la fois :

- **À la main** : ce qui arrive dans l'inventaire quand aucun écran n'est ouvert (items ramassés),
  **sauf** ce que le joueur avait lui-même jeté ou posé : jeter puis reprendre ne compte pas deux fois,
  poser un bloc puis le recasser non plus. Plus ce qui sort des fours, fumoirs, hauts fourneaux, de
  l'alambic et des villageois. Ranger dans un coffre ou un shulker, ou en ressortir, ne change rien ;
  fabriquer non plus.
- **Ferme auto** : le client ne voit l'intérieur d'un coffre que quand il est ouvert. Le mod retient
  ce que contient chaque coffre de collecte à chaque visite : ce qui y est apparu entre deux visites
  compte, ce que le joueur y range ou en retire non. **Ouvre chaque coffre une fois au début** (point de
  départ) **et à la fin** avant `/imgfarm stop`. Ce que le joueur ramasse compte aussi.
- Créatif et spectateur : rien ne compte.

Le comptage est dans `Tracker.java`, sans rien de Minecraft (testable seul).

## Compiler

L'action GitHub « Mod » (`.github/workflows/mod.yml`) compile une fois par ligne de `versions.json`
(Java 25, Gradle 9.8, Fabric Loom 1.19) et publie la release `mod-latest`. À la main :

```
cd img-farm-mod
gradle build -Pminecraft_version=26.3 -Pfabric_api_version=0.166.2+26.3
```

**Nouvelle version de Minecraft** (lot 72) : rien à faire dans le cas normal. Chaque lundi, l'action
regarde s'il existe une version stable plus récente que la dernière de `versions.json`
(`versions.sh` : meta.fabricmc.net, Fabric API sur Modrinth, dernier Fabric Loader) ; si oui, elle la
compile avec les autres et publie son fichier si ça passe (« compilée automatiquement : pas encore
essayée en jeu » dans la release). Rien n'est écrit dans le dépôt : une fois essayée, ajouter
`{ "minecraft": "26.4", "fabric_api": "…+26.4" }` à `versions.json` pour la garder (sinon elle reste
recalculée à chaque passage). Si la compilation échoue, l'erreur est sur la page de l'action
(annotation) et les autres fichiers sont quand même publiés.

**Filet de sécurité** (`Guard`) : le fichier d'une version se charge aussi sur les suivantes. Si le jeu
a changé ce qu'une fonction utilise (comptage, cadre, coffres, commandes…), cette fonction est coupée
au lieu de planter le jeu, le joueur est prévenu une fois dans le chat (et dans `/imgfarm compte`), et
le reste du mod continue.
Le site importe les nouveaux items tout seul (Admin → Items).
