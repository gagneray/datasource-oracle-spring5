package com.monentreprise.monappli.metier.dao;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Accès générique en lecture à la base Oracle, en remplacement de la classe utilitaire
 * du framework interne.
 *
 * <p>Le cycle de vie JDBC complet est délégué à {@link JdbcTemplate} :
 * obtention de la connexion depuis le {@link DataSource} (pool JBoss), création du
 * {@code PreparedStatement}, binding des paramètres, exécution, parcours du {@code ResultSet},
 * puis fermeture de ResultSet, Statement et Connection (dans un {@code finally}), y compris en cas d'erreur.
 * Les {@code SQLException} sont traduites en {@link DataAccessException} (non vérifiées).</p>
 *
 * <p>Les paramètres sont liés aux marqueurs {@code ?} de la requête, dans l'ordre :
 * ne jamais concaténer une valeur dans la chaîne SQL (risque d'injection SQL).</p>
 *
 * <p>Classe sans état modifiable, donc thread-safe : un seul bean (singleton) partagé par tous les services.</p>
 *
 * <p>Détectée par le {@code <context:component-scan>} de metier-context.xml grâce à {@code @Repository}
 * (bean nommé "jdbcQueryDao"). Ne pas la déclarer AUSSI en XML, sinon il y aurait deux beans.</p>
 */
@Repository
public class JdbcQueryDao {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructeur unique : Spring (4.3+) l'utilise automatiquement pour l'injection, sans {@code @Autowired}.
     * Le bean "dataSource" (lookup JNDI déclaré dans application-springbean.xml) est injecté par type.
     */
    public JdbcQueryDao(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Exécute un SELECT et renvoie chaque ligne sous forme de Map (nom de colonne → valeur).
     * Les clés sont insensibles à la casse (Oracle renvoie les noms de colonnes en MAJUSCULES).
     *
     * <pre>{@code
     * List<Map<String, Object>> lignes =
     *     dao.select("SELECT ID, LIBELLE FROM CLIENT WHERE VILLE = ? AND STATUT = ?", "Paris", "ACTIF");
     * // ou avec un tableau existant : dao.select(query, params) où params est un String[]
     * }</pre>
     *
     * @param query  requête SQL avec des marqueurs {@code ?}
     * @param params valeurs des marqueurs, dans l'ordre (un {@code String[]} est accepté tel quel)
     * @return les lignes trouvées, jamais {@code null} (liste vide si aucun résultat)
     * @throws DataAccessException en cas d'erreur SQL ou d'accès à la base
     */
    public List<Map<String, Object>> select(String query, String... params) {
        return jdbcTemplate.queryForList(query, (Object[]) params);
    }

    /**
     * Exécute un SELECT et convertit chaque ligne en objet métier via un {@link RowMapper}.
     *
     * <pre>{@code
     * List<Client> clients = dao.select(
     *     "SELECT ID, NOM FROM CLIENT WHERE VILLE = ?",
     *     (rs, rowNum) -> new Client(rs.getLong("ID"), rs.getString("NOM")),
     *     "Paris");
     * }</pre>
     *
     * @param query     requête SQL avec des marqueurs {@code ?}
     * @param rowMapper conversion d'une ligne du ResultSet en objet (ne pas fermer le ResultSet)
     * @param params    valeurs des marqueurs, dans l'ordre
     * @return les objets construits, jamais {@code null}
     * @throws DataAccessException en cas d'erreur SQL ou d'accès à la base
     */
    public <T> List<T> select(String query, RowMapper<T> rowMapper, String... params) {
        return jdbcTemplate.query(query, rowMapper, (Object[]) params);
    }

    /**
     * <b>Méthode de TRANSITION</b> pour migrer l'existant avec un minimum de changements :
     * remplace {@code statement.executeQuery(sql)} et renvoie un vrai {@link ResultSet},
     * que le code appelant parcourt comme avant ({@code while (rs.next()) { rs.getString(...) } }).
     *
     * <p>Le ResultSet renvoyé est une copie <b>déconnectée</b> ({@link CachedRowSet}) : toutes les lignes
     * sont chargées en mémoire, puis connexion, statement et ResultSet JDBC sont fermés et la connexion
     * est rendue au pool <i>avant</i> le retour de la méthode. L'appelant n'a donc plus rien à libérer
     * (un {@code rs.close()} restant dans l'ancien code est sans effet néfaste).</p>
     *
     * <p>À éviter pour des requêtes renvoyant un très grand nombre de lignes (tout est en mémoire).
     * Cible à terme : {@link #select(String, RowMapper, String...)}.</p>
     *
     * <pre>{@code
     * // Avant : rs = con.createStatement().executeQuery("SELECT ... WHERE VILLE = '" + ville + "'");
     * ResultSet rs = dao.executeQuery("SELECT ID, NOM FROM CLIENT WHERE VILLE = ?", ville);
     * while (rs.next()) { ... code existant inchangé ... }
     * }</pre>
     *
     * @param query  requête SQL, avec ou sans marqueurs {@code ?} (une requête existante sans paramètre
     *               fonctionne telle quelle)
     * @param params valeurs des marqueurs, dans l'ordre (aucune si la requête n'a pas de {@code ?})
     * @return un ResultSet déconnecté, positionné avant la première ligne
     * @throws DataAccessException en cas d'erreur SQL pendant l'exécution (et non plus SQLException)
     */
    public ResultSet executeQuery(String query, String... params) {
        return jdbcTemplate.query(query, COPIE_DECONNECTEE, (Object[]) params);
    }

    /**
     * Copie le ResultSet JDBC (encore connecté) dans un CachedRowSet autonome.
     * La fabrique est l'implémentation standard du JDK, chargée explicitement via le class loader
     * système : avec le chargement modulaire de JBoss, {@code RowSetProvider.newFactory()} sans argument
     * peut ne pas trouver cette implémentation depuis le class loader de l'application.
     */
    private static final ResultSetExtractor<ResultSet> COPIE_DECONNECTEE = rs -> {
        CachedRowSet copie = RowSetProvider
                .newFactory("com.sun.rowset.RowSetFactoryImpl", ClassLoader.getSystemClassLoader())
                .createCachedRowSet();
        copie.populate(rs);
        return copie;
    };
}
