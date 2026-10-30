package com.monentreprise.monappli.metier.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test d'INTÉGRATION : démarre un vrai contexte Spring à partir des fichiers XML
 * (metier-context.xml + une base H2 en mémoire en mode Oracle à la place du JNDI JBoss),
 * et exécute de vraies requêtes SQL.
 *
 * Équivalent Spring Boot : @SpringBootTest. Ici, hors Boot, c'est
 * @RunWith(SpringJUnit4ClassRunner.class) + @ContextConfiguration(fichiers XML).
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:test-context.xml")
public class JdbcQueryDaoIntegrationTest {

    @Autowired
    private JdbcQueryDao dao;

    @Test
    public void select_retourneLesLignesSousFormeDeMap() {
        String[] params = {"Paris"};

        List<Map<String, Object>> lignes =
                dao.select("SELECT ID, NOM FROM CLIENT WHERE VILLE = ? ORDER BY ID", params);

        assertEquals(2, lignes.size());
        assertEquals("DUPONT", lignes.get(0).get("NOM"));
        assertEquals("DUPONT", lignes.get(0).get("nom")); // clés insensibles à la casse
    }

    @Test
    public void select_avecRowMapper_etPlusieursParametres() {
        List<String> noms = dao.select(
                "SELECT NOM FROM CLIENT WHERE VILLE = ? AND STATUT = ?",
                (rs, rowNum) -> rs.getString("NOM"),
                "Paris", "ACTIF");

        assertEquals(1, noms.size());
        assertEquals("DUPONT", noms.get(0));
    }

    @Test
    public void select_sansResultat_retourneUneListeVide() {
        assertTrue(dao.select("SELECT * FROM CLIENT WHERE VILLE = ?", "Lyon").isEmpty());
    }

    @Test
    public void select_syntaxeOracle_fonctionneEnModeOracle() {
        // NVL et DUAL sont propres à Oracle : H2 en MODE=Oracle les accepte
        List<Map<String, Object>> lignes = dao.select("SELECT NVL(NULL, 'défaut') AS V FROM DUAL");
        assertEquals("défaut", lignes.get(0).get("V"));
    }
}
