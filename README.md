# DataSource Oracle avec Spring 5 (XML, hors Spring Boot), sur JBoss

Ce dossier reproduit la structure de votre projet (`config`, `metier`). Chaque fichier
indique s'il est **à créer** ou **à fusionner** avec un fichier existant.

```
config/src/main/resources/
    application.properties          AUCUNE clé datasource.* (datasource.jndi vient de JBoss, § 6.3)
    application-springbean.xml      à FUSIONNER : bean "dataSource" (lookup JNDI)
metier/
    pom.xml                         à FUSIONNER : spring-jdbc + dépendances de test
    src/main/java/com/monentreprise/monappli/metier/dao/JdbcQueryDao.java   à CRÉER
    src/main/resources/metier-context.xml                                   INCHANGÉ : le component-scan existant suffit
    src/test/java/.../dao/JdbcQueryDaoTest.java              à CRÉER : test unitaire avec Mockito
    src/test/java/.../dao/JdbcQueryDaoIntegrationTest.java   à CRÉER : test avec une base H2 en mémoire
    src/test/java/.../dao/MigrationResultSetVersDtoTest.java à CRÉER : exemple de migration ResultSet → DTO (§ 4)
    src/test/resources/test-context.xml, sql/schema.sql, sql/data.sql      à CRÉER
docker-local/                       OUTIL LOCAL (hors livrable) : JBoss sous Docker pour vérifier la config (§ 8)
```

Remplacez `com.monentreprise.monappli` par le package racine réel du projet.
Les modules `ws` et `ear` ne changent pas (voir § 9).
Pour la valorisation des propriétés selon l'environnement, voir § 6.

---

## 1. L'architecture en trois étages

```
 [JBoss]  datasource Oracle (pool, driver, mot de passe protégé)
              │  publiée dans l'annuaire JNDI : java:jboss/datasources/MonAppliDS
              ▼
 [config]  application-springbean.xml : <jee:jndi-lookup id="dataSource" .../>   → bean javax.sql.DataSource
              ▼  (injection)
 [metier]  @Repository JdbcQueryDao (détectée par component-scan)  ← JdbcTemplate : connexion, statement, close
              ▼  (injection)
           vos services métier
```

**Pourquoi JNDI ?** Les propriétés demandées (`driver-name`, `connection-url`, `min-pool-size`,
`max-pool-size`, `security-*`) portent les noms des attributs d'une datasource **JBoss**. Sur un
serveur d'applications, la bonne pratique (la Javadoc Spring de `DriverManagerDataSource` la donne)
consiste à laisser le serveur créer le pool et à le récupérer par son nom JNDI. Côté application,
seule `datasource.jndi` est lue, et elle est fournie par JBoss sous forme de propriété système (§ 6.3).
Les autres propriétés servent à créer la datasource dans JBoss (CLI, script, équipe d'exploitation) :
elles ne figurent pas dans le livrable.

Si JBoss **ne** déclare **pas** cette datasource, utilisez l'alternative B, en commentaire dans
`application-springbean.xml`. Elle crée un pool commons-dbcp2 à partir des propriétés
`datasource.*`, qu'il faudra alors fournir à l'application (propriétés système JBoss, § 6.3), et
nécessite d'embarquer `ojdbc8` dans l'EAR.

**Pourquoi un `JdbcQueryDao` plutôt qu'une classe utilitaire statique ?** C'est un bean Spring
(singleton, thread-safe) injecté dans les services. On peut donc le remplacer par un mock dans les
tests, ce qu'une classe statique ne permet pas facilement.

**Package `...metier.dao`** : c'est la couche d'accès aux données du module métier. On y met les
classes qui parlent JDBC. Les services restent dans `...metier.service`.

---

## 2. Ce que JdbcTemplate fait à votre place

L'ancienne classe utilitaire enchaînait : ouvrir la connexion, préparer le statement, lier les
paramètres, exécuter, lire le ResultSet, puis tout fermer. `JdbcTemplate` fait exactement cela,
fermeture comprise en cas d'exception. Voici l'équivalent « à la main » en Java 8, à titre
d'explication uniquement (**ne pas l'utiliser**, `JdbcQueryDao` le remplace) :

```java
public List<Map<String, Object>> select(String query, String... params) throws SQLException {
    List<Map<String, Object>> lignes = new ArrayList<>();
    // try-with-resources : fermeture automatique, dans l'ordre inverse (rs, ps, con), même en cas d'erreur
    try (Connection con = dataSource.getConnection();          // emprunt au pool JBoss
         PreparedStatement ps = con.prepareStatement(query)) {
        for (int i = 0; i < params.length; i++) {
            ps.setString(i + 1, params[i]);                     // les index JDBC commencent à 1
        }
        try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> ligne = new LinkedHashMap<>();
                for (int c = 1; c <= md.getColumnCount(); c++) {
                    ligne.put(md.getColumnLabel(c), rs.getObject(c));
                }
                lignes.add(ligne);
            }
        }
    } // con.close() ne coupe pas la connexion Oracle : il la RESTITUE au pool
    return lignes;
}
```

Avec `JdbcTemplate`, tout cela tient en une ligne :
`jdbcTemplate.queryForList(query, (Object[]) params)`. Les `SQLException` y sont converties en
`DataAccessException`, une exception non vérifiée.

> ⚠️ **Statement → PreparedStatement.** Si l'ancien code concaténait les valeurs dans la chaîne SQL
> (`"... WHERE NOM = '" + nom + "'"`), réécrivez les requêtes avec des `?` et passez les valeurs dans
> `params`. Cela supprime le risque d'injection SQL et permet à Oracle de réutiliser le plan d'exécution.

> ⚠️ **Si l'ancien utilitaire renvoyait un `ResultSet`** que l'appelant parcourait avant d'appeler
> une méthode de libération, ce schéma n'est pas transposable : le ResultSet est fermé à la sortie de
> `select`. Il faut faire le mapping dans un `RowMapper` (2e méthode `select`) ou travailler sur les
> `Map` renvoyées.

---

## 3. Utilisation dans un service

```java
@Service
public class ClientService {
    private final JdbcQueryDao jdbcQueryDao;

    public ClientService(JdbcQueryDao jdbcQueryDao) {   // constructeur unique : injection automatique
        this.jdbcQueryDao = jdbcQueryDao;
    }

    public List<String> nomsClientsActifs(String ville) {
        return jdbcQueryDao.select(
                "SELECT NOM FROM CLIENT WHERE VILLE = ? AND STATUT = ?",
                (rs, rowNum) -> rs.getString("NOM"),
                ville, "ACTIF");                          // ou un String[] existant
    }
}
```

`JdbcQueryDao` est annotée `@Repository`. Le `<context:component-scan>` déjà présent dans
`metier-context.xml` la détecte, à condition qu'elle soit **sous le package scanné**
(ex. `fr.blablabla.metier.dao`). Dans vos services (`@Service`), injectez-la comme le reste du
code : par constructeur, ou avec `@Autowired` si c'est la convention du projet. Ne la déclarez
**pas aussi** en XML, sinon il y aurait deux beans.

---

## 4. Migrer l'existant : du ResultSet au DTO, en deux étapes

Le code existant fait `statement.executeQuery(sql)` puis parcourt le `ResultSet`. L'objectif est de
migrer d'abord **sans toucher à la lecture des lignes**, puis de passer aux DTO.

### 4.1 Pourquoi on ne renvoie pas le ResultSet JDBC « vivant »

Un ResultSet JDBC n'est lisible que tant que sa connexion est ouverte. Le renvoyer tel quel
obligerait chaque appelant à refermer la connexion lui-même : c'est exactement le risque de fuite
qu'on veut supprimer. Avec un pool de `max-pool-size` connexions, quelques oublis suffisent à bloquer
l'application. `JdbcTemplate` ferme donc tout dès la fin de la requête.

`executeQuery` renvoie à la place une **copie déconnectée** : un `CachedRowSet`, l'implémentation
standard du JDK de l'interface `java.sql.ResultSet`. Les lignes sont copiées en mémoire, la connexion
est rendue au pool, et l'appelant reçoit un objet qui se lit exactement comme avant.

### 4.2 Étape 1 : refacto minimal avec `executeQuery`

**Avant :**
```java
Connection con = null; Statement stmt = null; ResultSet rs = null;
try {
    con = UtilitaireBdd.getConnection();
    stmt = con.createStatement();
    rs = stmt.executeQuery("SELECT ID, NOM FROM CLIENT WHERE VILLE = '" + ville + "'");
    while (rs.next()) {
        Client c = new Client();
        c.setId(rs.getLong("ID"));
        c.setNom(rs.getString("NOM"));
        clients.add(c);
    }
} catch (SQLException e) {
    throw new TechniqueException("Erreur BDD", e);
} finally {
    UtilitaireBdd.release(con, stmt, rs);
}
```

**Après :**
```java
try {
    ResultSet rs = jdbcQueryDao.executeQuery("SELECT ID, NOM FROM CLIENT WHERE VILLE = ?", ville);
    while (rs.next()) {                      // inchangé
        Client c = new Client();
        c.setId(rs.getLong("ID"));
        c.setNom(rs.getString("NOM"));
        clients.add(c);
    }
} catch (SQLException | DataAccessException e) {   // ⚠️ voir ci-dessous
    throw new TechniqueException("Erreur BDD", e);
}
```

Ce qui change :
1. **Obtention du ResultSet** : une seule ligne, `jdbcQueryDao.executeQuery(sql, params...)`.
   Une requête existante dont les valeurs sont déjà dans la chaîne fonctionne **telle quelle**, sans
   paramètre. Passer aux `?` est recommandé, mais peut se faire au fil de l'eau.
2. **Plus de connexion, de statement ni de `release`** à gérer. Un `rs.close()` qui resterait est sans effet néfaste.
3. ⚠️ **Gestion des erreurs** : une erreur d'exécution SQL (table absente, syntaxe, base
   indisponible) arrive maintenant sous forme de `DataAccessException` (non vérifiée), **plus** de
   `SQLException`. Un `catch (SQLException e)` existant reste compilable, parce que `rs.next()` et les
   `getXxx` la déclarent toujours, mais il **ne verra plus** ces erreurs. Si l'existant les convertit
   en exception métier ou en faute SOAP, ajoutez `DataAccessException` au `catch`, comme ci-dessus.

Points d'attention de cette étape :
- **Mémoire** : toutes les lignes sont chargées. C'est parfait pour des résultats de taille courante,
  mais à éviter pour des extractions massives (plusieurs centaines de milliers de lignes) : passez
  alors directement à l'étape 2.
- **Alias de colonnes** : le `CachedRowSet` retrouve une colonne par son *nom*. Avec Oracle, le nom
  d'une colonne aliasée (`SELECT NOM AS N`) est l'alias, donc `rs.getString("N")` fonctionne.
  D'autres bases, dont H2 dans les tests, peuvent renvoyer le nom d'origine : dans les tests, préférez
  les colonnes sans alias, ou la lecture par index.
- **Colonnes Oracle de type `TIMESTAMP`** (pas `DATE`) : selon la version du driver, la copie peut
  contenir des objets `oracle.sql.TIMESTAMP`, et `rs.getTimestamp(...)` lève alors une
  `ClassCastException`. Le correctif est un réglage de JBoss, pas du code : la propriété système
  `-Doracle.jdbc.J2EE13Compliant=true`, ou la même propriété de connexion sur la datasource. Testez
  une requête avec un `TIMESTAMP` en recette dès la première livraison.

### 4.3 Étape 1 bis : isoler la lecture d'une ligne

Sans rien changer d'autre, sortez le corps de la boucle dans une méthode dont la signature est
`(ResultSet, int) → objet` :

```java
ResultSet rs = jdbcQueryDao.executeQuery(SQL_CLIENTS_PAR_VILLE, ville);
while (rs.next()) {
    clients.add(mapClient(rs, rs.getRow()));
}

private Client mapClient(ResultSet rs, int rowNum) throws SQLException {
    Client c = new Client();
    c.setId(rs.getLong("ID"));
    c.setNom(rs.getString("NOM"));
    return c;
}
```

### 4.4 Étape 2 : la même méthode devient un `RowMapper`, la méthode renvoie des DTO

`mapClient` a exactement la signature d'un `RowMapper<Client>`. On la passe à `select`, et le
ResultSet disparaît du code appelant :

```java
List<Client> clients = jdbcQueryDao.select(SQL_CLIENTS_PAR_VILLE, this::mapClient, ville);
```

Ce qu'on y gagne :
- plus de copie en mémoire intermédiaire, chaque ligne est convertie à la volée ;
- plus de `try/catch` sur `SQLException` : seules les `DataAccessException` remontent ;
- un DTO typé, prêt à être renvoyé par le service ou converti en réponse SOAP.

Pour aller plus loin, uniquement si le besoin se présente :
- **Mapper partagé** : si plusieurs services lisent le même DTO, déplacez la méthode dans une classe
  `ClientRowMapper implements RowMapper<Client>`, dans `...metier.dao.mapper`.
- **Mapping automatique** : `BeanPropertyRowMapper.newInstance(Client.class)` remplit le DTO quand
  les noms de colonnes correspondent aux propriétés (`NOM_CLIENT` → `nomClient`). C'est pratique,
  mais une colonne mal nommée donne une propriété `null` sans erreur. Le mapping explicite reste plus
  sûr pour du code métier.

`MigrationResultSetVersDtoTest` exécute ces trois étapes sur la base H2, avec la même méthode `mapClient`.

---

## 5. Tester sans JBoss ni Oracle

| Test | Ce qu'il vérifie | Ce qu'il lui faut |
|---|---|---|
| `JdbcQueryDaoTest` | Requête préparée, paramètres liés dans l'ordre, **fermeture de ResultSet/Statement/Connection**, y compris sur erreur | Mockito uniquement |
| `MigrationResultSetVersDtoTest` | Les étapes de migration du § 4 : `executeQuery` avec la boucle existante, puis la même logique en `RowMapper` | H2 en mémoire |
| `JdbcQueryDaoIntegrationTest` | Le câblage Spring réel (scan du package `dao`, injection du DataSource) et de vraies requêtes SQL | H2 en mémoire, `MODE=Oracle` (accepte `DUAL`, `NVL`, `VARCHAR2`…) |

Lancement : `mvn test -pl metier`.

**Mocker le DAO dans les tests de vos services** (le cas le plus fréquent) :

```java
JdbcQueryDao dao = Mockito.mock(JdbcQueryDao.class);
Map<String, Object> ligne = new HashMap<>();
ligne.put("NOM", "DUPONT");
Mockito.when(dao.select("SELECT NOM FROM CLIENT WHERE VILLE = ?", "Paris"))
       .thenReturn(Collections.singletonList(ligne));

MonService service = new MonService(dao);   // aucun Spring, aucune base
```

Limite : H2 n'est pas Oracle. Les requêtes très spécifiques (hints, `CONNECT BY` complexes, PL/SQL)
doivent être validées sur une vraie base Oracle de recette.

---

## 6. Valorisation des propriétés selon l'environnement

### 6.1 Principe : le même EAR partout, la configuration dans chaque JBoss

Côté Spring, l'application ne lit qu'**une** propriété : `datasource.jndi`. Sa valeur est en général
**identique sur tous les environnements**. Ce nom sert d'étiquette ; ce qu'il désigne change d'un
serveur à l'autre :

```
              même EAR, même nom JNDI
                        │
     ┌──────────────────┼──────────────────┐
  JBoss DEV          JBoss RECETTE       JBoss PROD
  MonAppliDS →       MonAppliDS →        MonAppliDS →
  oracle-dev:1521    oracle-rec:1521     oracle-prod:1521
  user DEV / mdp     user REC / mdp      user PROD / mdp (vault)
```

On construit donc l'EAR une seule fois : celui qui a été testé en recette est celui qui part en production.

### 6.2 URL, login, mot de passe, pool : dans la configuration de chaque JBoss

Ces valeurs vivent dans le `standalone.xml` ou le `domain.xml` de chaque serveur. On les y met en
général par une commande jboss-cli, exécutée par l'exploitation ou par l'outil de déploiement. Les
propriétés `datasource.*` correspondent une à une aux paramètres de cette commande :

```
data-source add --name=MonAppliDS \
  --jndi-name=${datasource.jndi} \
  --driver-name=${datasource.driver-name} \
  --connection-url=${datasource.connection-url} \
  --user-name=${datasource.security-login} \
  --password=${datasource.security-password-secured} \
  --min-pool-size=${datasource.min-pool-size} \
  --max-pool-size=${datasource.max-pool-size}
```

Le mot de passe (`-secured`) est normalement stocké chiffré par l'outillage, puis injecté côté JBoss
via le vault ou une expression `${VAULT::...}`. Il n'apparaît jamais en clair dans le livrable ni dans Git.

### 6.3 `datasource.jndi` est fourni par JBoss, pas par le livrable

Aucune clé `datasource.*` n'est présente dans `application.properties`. La seule utile à
l'application, `datasource.jndi`, est fournie **par chaque JBoss** sous forme de **propriété système
JVM**. `<context:property-placeholder>` résout `${datasource.jndi}` en consultant aussi les propriétés
système, sans configuration supplémentaire.

Attention à ne pas confondre les trois maillons de la chaîne. `datasource.jndi` n'est **pas** un
chemin de fichier : c'est le **nom JNDI** de la datasource.

```
 propriété système JVM          nom JNDI                                 datasource JBoss (standalone.xml)
 datasource.jndi  ───────────►  java:jboss/datasources/MonAppliDS  ───►  URL, login, mot de passe, pool
 (fournie par JBoss)            (valeur de la propriété)                 (créée par jboss-cli, § 6.2)
```

Deux façons de fournir la propriété système sur un serveur, à choisir avec l'exploitation :

- **Directement dans `standalone.xml`** (ou `domain.xml`) :
  ```xml
  <system-properties>
      <property name="datasource.jndi" value="java:jboss/datasources/MonAppliDS"/>
  </system-properties>
  ```
  Ou en CLI : `/system-property=datasource.jndi:add(value="java:jboss/datasources/MonAppliDS")`.
- **Via un fichier chargé au démarrage** avec l'option `-P` : `standalone.sh -P file:///.../monappli.properties`.
  Chaque ligne `cle=valeur` du fichier devient une propriété système. C'est ici, et seulement ici,
  qu'intervient un chemin de fichier : c'est la **commande de démarrage** qui l'indique, pas
  `datasource.jndi`. Par convention, on range ce fichier dans `standalone/configuration/`
  (`${jboss.server.config.dir}`).

**Si la propriété est absente** d'un serveur, le déploiement échoue immédiatement avec
« Could not resolve placeholder 'datasource.jndi' ». C'est voulu : un oubli de configuration se voit
au déploiement, pas à la première requête. Si vous préférez une valeur de secours, utilisez
`jndi-name="${datasource.jndi:java:jboss/datasources/MonAppliDS}"` dans `application-springbean.xml` :
tout ce qui suit le premier `:` est la valeur par défaut.

> Contrairement à Spring Boot, il n'y a pas de correspondance automatique avec les variables
> d'environnement du système : `DATASOURCE_JNDI` ne remplacera **pas** `datasource.jndi`.
> Il faut une propriété système (`<system-properties>`, `-P` ou `-D...`).

### 6.4 Le fichier `datasource.*` éventuellement présent sur le serveur

Si un fichier contenant `datasource.connection-url`, `min-pool-size`, etc. existe sur le serveur, il
sert au **script de création de la datasource**, pas à l'application. Par exemple :
`jboss-cli.sh --properties=/chemin/datasource-prod.properties --file=creer-datasource.cli`, où le
script contient la commande du § 6.2. Ces valeurs finissent dans `standalone.xml` ; l'application ne
les lit jamais.

### 6.5 À transmettre à l'équipe qui déploie

- le nom de la propriété système à définir sur chaque JBoss (`datasource.jndi`) et sa valeur (le
  `jndi-name` de la datasource) ;
- la demande de création de la datasource sur chaque JBoss (commande du § 6.2) ;
- la vérification par `test-connection-in-pool` (§ 7) avant le premier déploiement.

---

## 7. Vérifier sur le JBoss distant

1. La datasource existe et répond (jboss-cli) :
   ```
   /subsystem=datasources/data-source=MonAppliDS:read-resource
   /subsystem=datasources/data-source=MonAppliDS:test-connection-in-pool
   ```
2. `datasource.jndi` doit être **identique** à l'attribut `jndi-name` de la datasource JBoss
   (souvent `java:jboss/datasources/...` ou `java:/...`).
3. Au déploiement, un nom JNDI erroné fait échouer le démarrage du contexte Spring avec une
   `NameNotFoundException` qui cite le nom recherché. C'est voulu : l'erreur apparaît au déploiement
   et non à la première requête.
4. Pour voir les requêtes et leurs paramètres dans les logs, ajoutez dans `logback.xml` :
   ```xml
   <logger name="org.springframework.jdbc.core.JdbcTemplate" level="DEBUG"/>
   <logger name="org.springframework.jdbc.core.StatementCreatorUtils" level="TRACE"/>
   ```

---

## 8. Tester en local avec un JBoss sous Docker

Le dossier `docker-local/` lance un WildFly, la version communautaire de JBoss EAP, en Java 8. Il
permet de vérifier, sans serveur distant ni Oracle :
1. qu'un fichier de propriétés chargé par JBoss (`-P`) devient bien une propriété système ;
2. que la datasource `java:jboss/datasources/MonAppliDS` existe et répond ;
3. que l'EAR démarre : Spring résout `${datasource.jndi}` et trouve la datasource en JNDI.

| Fichier | Rôle |
|---|---|
| `Dockerfile` | Java 8 + WildFly, création de la datasource, utilisateur de la console |
| `setup-datasource.cli` | Crée `MonAppliDS` (même nom JNDI qu'en prod) sur la base H2 livrée avec WildFly, en mode Oracle |
| `monappli.properties` | Le fichier « serveur » : `datasource.jndi=...`, chargé au démarrage par `-P` |
| `docker-compose.yml` | Monte le fichier de propriétés et le dossier `deployments/`, expose les ports 8080 et 9990 |

**Version** : alignez `WILDFLY_VERSION` (dans `docker-compose.yml`) sur votre EAP :
EAP 7.0 → `10.1.0.Final`, 7.1 → `11.0.0.Final`, 7.2 → `14.0.1.Final`, 7.3 → `18.0.1.Final`,
7.4 → `23.0.2.Final`. Le serveur indique sa version au démarrage, et `standalone.sh --version` la
donne aussi.

### 8.1 Démarrer

```bash
cd docker-local
docker compose up --build        # 1er lancement : télécharge Java 8 et WildFly (~200 Mo)
```

Attendez la ligne `WFLYSRV0025: WildFly Full ... started`.

### 8.2 Vérification 1 : la propriété est chargée

Dans un second terminal :

```bash
docker compose exec jboss /opt/wildfly/bin/jboss-cli.sh --connect \
  --command='/core-service=platform-mbean/type=runtime:read-attribute(name=system-properties)' \
  | grep datasource
```

Attendu : `"datasource.jndi" => "java:jboss/datasources/MonAppliDS"`.

### 8.3 Vérification 2 : la datasource répond

```bash
docker compose exec jboss /opt/wildfly/bin/jboss-cli.sh --connect \
  --command='/subsystem=datasources/data-source=MonAppliDS:test-connection-in-pool'
```

Attendu : `"outcome" => "success"`. La même commande vous servira sur les vrais serveurs (§ 7).

### 8.4 Vérification 3 : l'application démarre

1. Construisez l'EAR (`mvn package` à la racine du projet), puis copiez-le dans `docker-local/deployments/`.
2. WildFly le déploie automatiquement. Un fichier `monappli.ear.deployed` apparaît en cas de
   succès, ou `monappli.ear.failed` en cas d'échec (il contient l'erreur).
3. Dans les logs (`docker compose logs -f`), le démarrage du contexte Spring ne doit afficher ni
   `Could not resolve placeholder`, ni `NameNotFoundException`.
4. Les endpoints SOAP sont accessibles sur `http://localhost:8080/...`. La console
   `http://localhost:9990` (admin / `Admin#local1`, usage local uniquement) liste les déploiements
   et les datasources.

### 8.5 Contre-épreuve : la propriété absente fait échouer le déploiement

Mettez la ligne `datasource.jndi` de `monappli.properties` en commentaire, puis
`docker compose restart`. Le déploiement doit échouer avec
`Could not resolve placeholder 'datasource.jndi'`. Remettez la ligne et redémarrez. Le fichier
n'est lu qu'au démarrage : chaque modification demande un `restart`.

### 8.6 Limites

- **H2 n'est pas Oracle** : les tables métier n'existent pas, donc les appels qui interrogent la base
  échoueront. Ce montage valide **la configuration et le câblage**, pas les requêtes. Pour cela, il
  faut une vraie base : celle de recette, ou une image Oracle XE en Docker avec le driver `ojdbc8`
  installé comme module WildFly. C'est plus lourd, et ce n'est utile que si vous devez tester les
  requêtes en local.
- **Autres dépendances du serveur** : si l'EAR attend d'autres ressources des JBoss réels (module de
  configuration déclaré dans `jboss-deployment-structure.xml`, autres datasources, files JMS,
  security-domain), le déploiement local échouera pour ces raisons-là. Le message de
  `monappli.ear.failed` les nomme ; il faut alors les ajouter dans `setup-datasource.cli` ou dans le Dockerfile.
- **WildFly ≈ EAP** : pour ce qui nous concerne (`-P`, `<system-properties>`, datasources, JNDI), le
  comportement est le même. Ce n'est pas pour autant une recette officielle de la production.

---

## 9. Points de vigilance

- **Version Spring** : « 5.12 » n'existe pas. Il s'agit probablement de 5.1.2, 5.2.12 ou 5.3.12.
  Le code fonctionne avec toute version 5.x. `spring-jdbc` doit avoir **la même version** que
  `spring-context` : ne mettez pas de version en dur si le pom parent la gère, sinon utilisez
  `${spring.version}`.
- **Un seul property-placeholder** : si `application-springbean.xml` déclare déjà un
  `<context:property-placeholder>` ou un `PropertyPlaceholderConfigurer`, gardez-le : il résout aussi
  les propriétés système, donc `${datasource.jndi}` fournie par JBoss. N'en déclarez pas un deuxième : deux placeholders sans
  `ignore-unresolvable="true"` provoquent des erreurs « Could not resolve placeholder ».
- **Schémas XSD** : les fichiers utilisent `http://www.springframework.org/schema/...`, résolu hors
  ligne depuis les jars Spring quelle que soit la version 5.x. Gardez le même style que vos fichiers
  existants.
- **Driver Oracle** : ne l'embarquez pas dans l'EAR avec la solution JNDI, JBoss le fournit.
  `jboss-deployment-structure.xml` ne change pas, sauf si le code utilise des classes
  `oracle.jdbc.*` (cast, `unwrap`). Dans ce cas, ajoutez la dépendance vers le module Oracle de JBoss.
- **Mot de passe** : `datasource.security-password-secured` est géré côté JBoss (vault ou
  security-domain). Ne le versionnez jamais en clair (voir § 6.2).
- **Les deux XML dans le même contexte** : au déploiement, `application-springbean.xml` (qui définit
  `dataSource`) et `metier-context.xml` (qui contient le scan de `JdbcQueryDao`) doivent être chargés
  ensemble. C'est le cas s'ils sont listés dans le `contextConfigLocation` du `web.xml` ou reliés
  par un `<import resource="..."/>`. Sinon, le démarrage sur JBoss échoue avec « No qualifying bean
  of type DataSource », et les tests ne peuvent pas le détecter.
- **IntelliJ, « Cannot resolve bean 'dataSource' »** : ce n'est pas une erreur d'exécution. IntelliJ
  analyse chaque XML isolément, alors que `dataSource` est défini dans un autre module. Pour faire
  disparaître l'avertissement, regroupez les deux fichiers dans un même contexte via
  *File > Project Structure > Facets > Spring*, ou passez par `@Repository` + component-scan (§ 3).
- **`@Repository` OU `<bean>`, jamais les deux** : une classe déclarée par `<bean>` en XML n'a pas
  besoin d'annotation ; une classe annotée et scannée ne doit pas être déclarée aussi en XML
  (sinon deux beans).
- **Écritures futures** (INSERT/UPDATE) : `JdbcTemplate.update(...)` suffit. Pour regrouper plusieurs
  écritures dans une transaction, il faudra un gestionnaire de transactions
  (`JtaTransactionManager` sur JBoss). Ce n'est pas nécessaire pour des SELECT.

---

## 10. Correspondance Spring Boot → Spring XML

| Ce que vous faites en Spring Boot | Ici (Spring 5, XML, JBoss) |
|---|---|
| `application.properties` lu automatiquement | `<context:property-placeholder location="classpath:application.properties"/>` |
| `@Value("${x}")` / `@ConfigurationProperties` | `value="${x}"` dans un fichier XML |
| `spring.datasource.jndi-name=...` | `<jee:jndi-lookup id="dataSource" jndi-name="${datasource.jndi}"/>` |
| `spring.datasource.url/username/...` (pool Hikari auto-configuré) | Alternative B : `<bean class="...BasicDataSource">` déclaré à la main |
| `JdbcTemplate` auto-configuré et injectable | `new JdbcTemplate(dataSource)` dans le constructeur du DAO |
| Scan automatique sous le package de la classe `@SpringBootApplication` | `<context:component-scan base-package="..."/>` explicite (déjà dans `metier-context.xml`) |
| Injection par constructeur implicite | Identique (Spring 4.3+), dès que la classe est scannée |
| `@SpringBootTest` | `@RunWith(SpringJUnit4ClassRunner.class)` + `@ContextConfiguration("classpath:test-context.xml")` |
| `@MockBean` | `Mockito.mock(...)` passé au constructeur du service |
| Profils `application-test.properties` | Un fichier XML de test qui redéfinit le bean `dataSource` (`test-context.xml`) |
| `application-prod.properties` / profils par environnement | Même EAR partout ; `datasource.jndi` en propriété système de chaque JBoss, datasource dans son `standalone.xml` — § 6 |
| Variables d'environnement `SPRING_DATASOURCE_URL` (relaxed binding) | Pas d'équivalent automatique : propriétés système (`<system-properties>`, `-P`, `-D`) — § 6.3 |

---

### Sources (documentation officielle)
- Spring : [Controlling Database Connections](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
- Spring : [JdbcTemplate – Using the JDBC Core Classes](https://docs.spring.io/spring/reference/data-access/jdbc/core.html)
- Spring : [Embedded Database Support](https://docs.spring.io/spring/reference/data-access/jdbc/embedded-database-support.html)
- Spring : [XML Schemas – jee:jndi-lookup](https://docs.spring.io/spring-framework/reference/integration/appendix.html)
- Spring 5.3 Javadoc : [DriverManagerDataSource](https://docs.spring.io/spring/docs/5.3.x/javadoc-api/org/springframework/jdbc/datasource/DriverManagerDataSource.html)
- Java 8 : [The try-with-resources Statement](https://docs.oracle.com/javase/8/docs/technotes/guides/language/try-with-resources.html)
