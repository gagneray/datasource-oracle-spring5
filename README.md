# DataSource Oracle avec Spring 5 (XML, hors Spring Boot), sur JBoss

Ce dossier reproduit la structure de votre projet (`config`, `metier`). Chaque fichier
indique s'il est **à créer** ou **à fusionner** avec un fichier existant.

```
config/src/main/resources/
    application.properties          à FUSIONNER : propriétés datasource.*
    application-springbean.xml      à FUSIONNER : bean "dataSource" (lookup JNDI)
metier/
    pom.xml                         à FUSIONNER : spring-jdbc + dépendances de test
    src/main/java/com/monentreprise/monappli/metier/dao/JdbcQueryDao.java   à CRÉER
    src/main/resources/metier-context.xml                                   INCHANGÉ : le component-scan existant suffit
    src/test/java/.../dao/JdbcQueryDaoTest.java              à CRÉER : test unitaire avec Mockito
    src/test/java/.../dao/JdbcQueryDaoIntegrationTest.java   à CRÉER : test avec une base H2 en mémoire
    src/test/resources/test-context.xml, sql/schema.sql, sql/data.sql      à CRÉER
```

Remplacez `com.monentreprise.monappli` par le package racine réel du projet.
Les modules `ws` et `ear` ne changent pas (voir § 7).
Pour la valorisation des propriétés selon l'environnement, voir § 5.

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
seule `datasource.jndi` est lue. Les autres propriétés servent à créer la datasource dans JBoss
(CLI, script, équipe d'exploitation).

Si JBoss **ne** déclare **pas** cette datasource, utilisez l'alternative B, en commentaire dans
`application-springbean.xml`. Elle crée un pool commons-dbcp2 à partir des propriétés et
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

## 4. Tester sans JBoss ni Oracle

| Test | Ce qu'il vérifie | Ce qu'il lui faut |
|---|---|---|
| `JdbcQueryDaoTest` | Requête préparée, paramètres liés dans l'ordre, **fermeture de ResultSet/Statement/Connection**, y compris sur erreur | Mockito uniquement |
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

## 5. Valorisation des propriétés selon l'environnement

### 5.1 Principe : le même EAR partout, la configuration dans chaque JBoss

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

### 5.2 URL, login, mot de passe, pool : dans la configuration de chaque JBoss

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

### 5.3 Comment `application.properties` est fourni par environnement

Trois mécanismes se rencontrent dans ce type de projet. **Suivez celui qui est déjà utilisé** pour les
autres propriétés qui changent selon l'environnement, comme une URL de webservice :

| Mécanisme | Comment le reconnaître | Ce qu'il faut faire |
|---|---|---|
| **Module JBoss externe** (le plus probable, vu le module `config` sans code) | `jboss-deployment-structure.xml` contient une dépendance du type `<module name="...config..."/>` ; les fichiers sont déposés sur chaque serveur dans `modules/.../config/main/` | Ajouter les clés `datasource.*` au gabarit ; l'exploitation les renseigne pour chaque environnement |
| **Substitution au déploiement** (Jenkins, Ansible, XL Deploy…) | Jetons ou valeurs vides dans le fichier versionné, remplacés avant la pose sur le serveur | Ajouter les clés et déclarer leurs valeurs dans l'outil, pour chaque environnement |
| **Profils Maven** (un build par environnement) | `<profiles>` et `<filtering>true</filtering>` dans les pom | Ça fonctionne, mais c'est à éviter : le binaire de recette n'est plus celui de production |

### 5.4 Surcharger une valeur sans toucher au fichier

`<context:property-placeholder>` ne cherche pas les valeurs uniquement dans `application.properties`.
Il regarde aussi les **propriétés système de la JVM**, et celles-ci ont **la priorité** sur le fichier.
On peut donc surcharger une valeur sur un serveur donné :

- au lancement : `-Ddatasource.jndi=java:jboss/datasources/AutreDS` ;
- ou dans le `standalone.xml` :
  ```xml
  <system-properties>
      <property name="datasource.jndi" value="java:jboss/datasources/AutreDS"/>
  </system-properties>
  ```

> Contrairement à Spring Boot, il n'y a pas de correspondance automatique avec les variables
> d'environnement du système : `DATASOURCE_JNDI` ne remplacera **pas** `datasource.jndi`.
> Passez par des propriétés système (`-D...`).

### 5.5 À transmettre à l'équipe qui déploie

- la liste des clés `datasource.*` et le nom JNDI attendu (`datasource.jndi`) ;
- la demande de création de la datasource sur chaque JBoss (commande du § 5.2) ;
- la vérification par `test-connection-in-pool` (§ 6) avant le premier déploiement.

---

## 6. Vérifier sur le JBoss distant

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

## 7. Points de vigilance

- **Version Spring** : « 5.12 » n'existe pas. Il s'agit probablement de 5.1.2, 5.2.12 ou 5.3.12.
  Le code fonctionne avec toute version 5.x. `spring-jdbc` doit avoir **la même version** que
  `spring-context` : ne mettez pas de version en dur si le pom parent la gère, sinon utilisez
  `${spring.version}`.
- **Un seul property-placeholder** : si `application-springbean.xml` déclare déjà un
  `<context:property-placeholder>` ou un `PropertyPlaceholderConfigurer`, ajoutez simplement les
  propriétés dans `application.properties`. N'en déclarez pas un deuxième : deux placeholders sans
  `ignore-unresolvable="true"` provoquent des erreurs « Could not resolve placeholder ».
- **Schémas XSD** : les fichiers utilisent `http://www.springframework.org/schema/...`, résolu hors
  ligne depuis les jars Spring quelle que soit la version 5.x. Gardez le même style que vos fichiers
  existants.
- **Driver Oracle** : ne l'embarquez pas dans l'EAR avec la solution JNDI, JBoss le fournit.
  `jboss-deployment-structure.xml` ne change pas, sauf si le code utilise des classes
  `oracle.jdbc.*` (cast, `unwrap`). Dans ce cas, ajoutez la dépendance vers le module Oracle de JBoss.
- **Mot de passe** : `datasource.security-password-secured` est géré côté JBoss (vault ou
  security-domain). Ne le versionnez jamais en clair (voir § 5.2).
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

## 8. Correspondance Spring Boot → Spring XML

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
| `application-prod.properties` / profils par environnement | Même EAR partout ; valeurs fournies par chaque JBoss (datasource, module de config) — § 5 |
| Variables d'environnement `SPRING_DATASOURCE_URL` (relaxed binding) | Pas d'équivalent automatique : propriétés système `-Dcle=valeur` (§ 5.4) |

---

### Sources (documentation officielle)
- Spring : [Controlling Database Connections](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html)
- Spring : [JdbcTemplate – Using the JDBC Core Classes](https://docs.spring.io/spring/reference/data-access/jdbc/core.html)
- Spring : [Embedded Database Support](https://docs.spring.io/spring/reference/data-access/jdbc/embedded-database-support.html)
- Spring : [XML Schemas – jee:jndi-lookup](https://docs.spring.io/spring-framework/reference/integration/appendix.html)
- Spring 5.3 Javadoc : [DriverManagerDataSource](https://docs.spring.io/spring/docs/5.3.x/javadoc-api/org/springframework/jdbc/datasource/DriverManagerDataSource.html)
- Java 8 : [The try-with-resources Statement](https://docs.oracle.com/javase/8/docs/technotes/guides/language/try-with-resources.html)
