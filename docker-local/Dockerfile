# JBoss LOCAL pour vérifier la configuration (propriétés système + datasource + déploiement de l'EAR).
# WildFly = version communautaire de JBoss EAP. Choisissez la version correspondant à votre EAP :
#   EAP 7.0 -> 10.1.0.Final | EAP 7.1 -> 11.0.0.Final | EAP 7.2 -> 14.0.1.Final
#   EAP 7.3 -> 18.0.1.Final | EAP 7.4 -> 23.0.2.Final
# Base Java 8, comme en production (les images WildFly officielles récentes tournent en Java 11+).
FROM eclipse-temurin:8-jdk

ARG WILDFLY_VERSION=23.0.2.Final
ENV JBOSS_HOME=/opt/wildfly

RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://download.jboss.org/wildfly/${WILDFLY_VERSION}/wildfly-${WILDFLY_VERSION}.tar.gz" \
    | tar -xz -C /opt \
 && mv "/opt/wildfly-${WILDFLY_VERSION}" "${JBOSS_HOME}"

# Crée la datasource MonAppliDS (même nom JNDI qu'en production) sur la base H2 livrée avec WildFly,
# pour ne pas avoir besoin d'Oracle en local.
COPY setup-datasource.cli /tmp/setup-datasource.cli
RUN "${JBOSS_HOME}/bin/jboss-cli.sh" --file=/tmp/setup-datasource.cli \
 && rm -rf "${JBOSS_HOME}/standalone/configuration/standalone_xml_history"

# Utilisateur de la console d'administration (http://localhost:9990) : USAGE LOCAL UNIQUEMENT
RUN "${JBOSS_HOME}/bin/add-user.sh" -u admin -p 'Admin#local1' --silent

EXPOSE 8080 9990

# -P : charge le fichier en PROPRIÉTÉS SYSTÈME JVM au démarrage (c'est ce qu'on veut vérifier).
# Le fichier est monté depuis l'hôte par docker-compose.yml.
CMD ["/opt/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0", \
     "-P", "file:///opt/config/monappli.properties"]
