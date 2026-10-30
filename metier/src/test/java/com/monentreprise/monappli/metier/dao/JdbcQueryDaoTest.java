package com.monentreprise.monappli.metier.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessException;

/**
 * Test UNITAIRE : aucun Spring, aucune base. On simule (mock) toute la chaîne JDBC
 * DataSource → Connection → PreparedStatement → ResultSet, puis on vérifie :
 *  - que la requête et les paramètres sont bien transmis au driver ;
 *  - que TOUTES les ressources JDBC sont libérées, y compris quand une erreur survient.
 */
public class JdbcQueryDaoTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet resultSet;

    private JdbcQueryDao dao;

    @Before
    public void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        dao = new JdbcQueryDao(dataSource);
    }

    @Test
    public void select_lieLesParametresEtLibereLesRessources() throws SQLException {
        // Le ResultSet simulé contient 2 lignes
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("NOM")).thenReturn("DUPONT", "MARTIN");

        String[] params = {"Paris", "ACTIF"};
        List<String> noms = dao.select(
                "SELECT NOM FROM CLIENT WHERE VILLE = ? AND STATUT = ?",
                (rs, rowNum) -> rs.getString("NOM"),
                params);

        assertEquals(2, noms.size());
        assertEquals("DUPONT", noms.get(0));
        assertEquals("MARTIN", noms.get(1));

        // Requête préparée et paramètres liés dans l'ordre
        verify(connection).prepareStatement("SELECT NOM FROM CLIENT WHERE VILLE = ? AND STATUT = ?");
        verify(statement).setString(1, "Paris");
        verify(statement).setString(2, "ACTIF");

        // Libération des ressources
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();   // = restitution de la connexion au pool JBoss
    }

    @Test
    public void select_libereLesRessourcesMemeEnCasDErreurSql() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("ORA-00942: table or view does not exist"));

        try {
            dao.select("SELECT * FROM TABLE_INCONNUE");
            fail("Une DataAccessException était attendue");
        } catch (DataAccessException e) {
            // SQLException traduite par Spring en exception non vérifiée
        }

        verify(statement).close();
        // atLeastOnce : pour traduire l'erreur, Spring ouvre/ferme une 2e fois une connexion
        // afin de lire le type de base (codes d'erreur Oracle). L'important : elle est fermée.
        verify(connection, atLeastOnce()).close();
    }
}
