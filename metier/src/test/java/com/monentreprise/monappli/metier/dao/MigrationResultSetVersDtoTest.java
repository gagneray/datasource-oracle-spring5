package com.monentreprise.monappli.metier.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Illustre (et vérifie) le chemin de migration en deux étapes, sur la base H2 en mémoire :
 *
 *  Étape 1 : executeQuery(...) renvoie un ResultSet -> la boucle while (rs.next()) existante est conservée.
 *  Étape 2 : le corps de cette boucle devient un RowMapper -> select(...) renvoie directement des DTO.
 *
 * La même méthode mapClient(ResultSet, int) sert aux deux étapes : c'est le pont entre les deux.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:test-context.xml")
public class MigrationResultSetVersDtoTest {

    private static final String SQL_CLIENTS_PAR_VILLE = "SELECT ID, NOM FROM CLIENT WHERE VILLE = ? ORDER BY ID";

    @Autowired
    private JdbcQueryDao dao;

    // ----------------------------------------------------------------------------------------------
    // Étape 1 : refacto minimal, le code appelant garde son ResultSet
    // ----------------------------------------------------------------------------------------------

    @Test
    public void etape1_executeQuery_renvoieUnResultSetParcourableCommeAvant() throws SQLException {
        List<Client> clients = new ArrayList<>();

        ResultSet rs = dao.executeQuery(SQL_CLIENTS_PAR_VILLE, "Paris");
        while (rs.next()) {                       // boucle de l'ancien code, inchangée
            Client c = new Client();
            c.setId(rs.getLong("ID"));
            c.setNom(rs.getString("NOM"));
            clients.add(c);
        }
        rs.close();                               // reste de l'ancien code : sans effet néfaste

        assertEquals(2, clients.size());
        assertEquals("DUPONT", clients.get(0).getNom());
        assertEquals("MARTIN", clients.get(1).getNom());
    }

    @Test
    public void etape1_requeteExistanteSansParametre_fonctionneTelleQuelle() throws SQLException {
        // Requête "à l'ancienne", valeurs déjà dans la chaîne : accepté pour la transition
        ResultSet rs = dao.executeQuery("SELECT NOM FROM CLIENT WHERE ID = 3");

        assertEquals(true, rs.next());
        assertEquals("DURAND", rs.getString("NOM"));
        assertFalse(rs.next());
    }

    @Test
    public void etape1bis_corpsDeBoucleExtraitDansUneMethode() throws SQLException {
        List<Client> clients = new ArrayList<>();

        ResultSet rs = dao.executeQuery(SQL_CLIENTS_PAR_VILLE, "Paris");
        while (rs.next()) {
            clients.add(mapClient(rs, rs.getRow()));   // même résultat, logique isolée
        }

        assertEquals(2, clients.size());
        assertEquals(Long.valueOf(1L), clients.get(0).getId());
    }

    // ----------------------------------------------------------------------------------------------
    // Étape 2 : la méthode extraite devient un RowMapper, plus de ResultSet dans le code appelant
    // ----------------------------------------------------------------------------------------------

    @Test
    public void etape2_select_avecLaMemeMethodeCommeRowMapper_renvoieDesDto() {
        List<Client> clients = dao.select(SQL_CLIENTS_PAR_VILLE, this::mapClient, "Paris");

        assertEquals(2, clients.size());
        assertEquals("DUPONT", clients.get(0).getNom());
        assertEquals("MARTIN", clients.get(1).getNom());
    }

    /** Ancien corps de boucle. Signature compatible avec RowMapper&lt;Client&gt; : (ResultSet, int) -> Client. */
    private Client mapClient(ResultSet rs, int rowNum) throws SQLException {
        Client c = new Client();
        c.setId(rs.getLong("ID"));
        c.setNom(rs.getString("NOM"));
        return c;
    }

    /** DTO d'exemple (dans le projet : une vraie classe, par exemple dans ...metier.dto). */
    public static class Client {
        private Long id;
        private String nom;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getNom() { return nom; }
        public void setNom(String nom) { this.nom = nom; }
    }
}
