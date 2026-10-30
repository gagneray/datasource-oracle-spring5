package com.monentreprise.monappli.metier.dao;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
}
