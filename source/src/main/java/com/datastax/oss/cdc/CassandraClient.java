package com.datastax.oss.cdc;

import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.internal.core.auth.DseGssApiAuthProvider;
import com.datastax.oss.common.sink.config.AuthenticatorConfig;
import com.datastax.oss.common.sink.config.ContactPointsValidator;
import com.datastax.oss.common.sink.config.SslConfig;
import com.datastax.oss.common.sink.ssl.SessionBuilder;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.SchemaChangeListener;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.internal.core.auth.PlainTextAuthProvider;
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultDriverConfigLoader;
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static com.datastax.dse.driver.api.core.config.DseDriverOption.AUTH_PROVIDER_SASL_PROPERTIES;
import static com.datastax.dse.driver.api.core.config.DseDriverOption.AUTH_PROVIDER_SERVICE;
import static com.datastax.oss.common.sink.util.UUIDUtil.generateClientId;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.*;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.selectFrom;

/**
 * Async read from Cassandra with downgrade consistency retry.
 */
@Slf4j
@Getter
@SuppressWarnings("try")
public class CassandraClient implements AutoCloseable {

    final CqlSession cqlSession;

    public CassandraClient(CassandraSourceConnectorConfig config, String version, String applicationName, SchemaChangeListener schemaChangeListener) {
        this.cqlSession = buildCqlSession(config, version, applicationName, schemaChangeListener);
    }

    public static CqlSession buildCqlSession(
            CassandraSourceConnectorConfig config,
            String version, String applicationName,
            SchemaChangeListener schemaChangeListener) {
        log.info("CassandraSinkTask starting with config:\n{}\n", config.toString());
        SslConfig sslConfig = config.getSslConfig();
        CqlSessionBuilder builder =
                new SessionBuilder(sslConfig)
                        .withApplicationVersion(version)
                        .withApplicationName(applicationName)
                        .withClientId(generateClientId(config.getInstanceName()))
                        .withSchemaChangeListener(schemaChangeListener);

        ContactPointsValidator.validateContactPoints(config.getContactPoints());

        if (sslConfig != null && sslConfig.requireHostnameValidation()) {
            // if requireHostnameValidation then InetSocketAddress must be resolved
            config
                    .getContactPoints()
                    .stream()
                    .map(hostStr -> new InetSocketAddress(hostStr, config.getPort()))
                    .forEach(builder::addContactPoint);
        } else {
            config
                    .getContactPoints()
                    .stream()
                    .map(hostStr -> InetSocketAddress.createUnresolved(hostStr, config.getPort()))
                    .forEach(builder::addContactPoint);
        }

        ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder =
                dseProgrammaticBuilderWithFallback(
                        ConfigFactory.parseMap(config.getJavaDriverSettings(), "Connector properties"));

        processAuthenticatorConfig(config, configLoaderBuilder);
        if (sslConfig != null) {
            processSslConfig(sslConfig, configLoaderBuilder);
        }
        builder.withConfigLoader(configLoaderBuilder.build());

        CqlSession cqlSession = builder.build();
        cqlSession.setSchemaMetadataEnabled(true);
        return cqlSession;
    }

    /**
     * Process ssl settings in the config; essentially map them to settings in the session builder.
     *
     * @param sslConfig the ssl config
     * @param configLoaderBuilder the config loader builder
     */
    private static void processSslConfig(
            SslConfig sslConfig, ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder) {
        if (sslConfig.getProvider() == SslConfig.Provider.JDK) {
            configLoaderBuilder.withString(SSL_ENGINE_FACTORY_CLASS, "DefaultSslEngineFactory");
            List<String> cipherSuites = sslConfig.getCipherSuites();
            if (!cipherSuites.isEmpty()) {
                configLoaderBuilder.withStringList(SSL_CIPHER_SUITES, cipherSuites);
            }
            configLoaderBuilder
                    .withBoolean(SSL_HOSTNAME_VALIDATION, sslConfig.requireHostnameValidation())
                    .withString(SSL_TRUSTSTORE_PASSWORD, sslConfig.getTruststorePassword())
                    .withString(SSL_KEYSTORE_PASSWORD, sslConfig.getKeystorePassword());

            Path truststorePath = sslConfig.getTruststorePath();
            if (truststorePath != null) {
                configLoaderBuilder.withString(SSL_TRUSTSTORE_PATH, truststorePath.toString());
            }
            Path keystorePath = sslConfig.getKeystorePath();
            if (keystorePath != null) {
                configLoaderBuilder.withString(SSL_KEYSTORE_PATH, keystorePath.toString());
            }
        }
    }

    /**
     * Process auth settings in the config; essentially map them to settings in the session builder.
     *
     * @param config the sink config
     * @param configLoaderBuilder the config loader builder
     */
    private static void processAuthenticatorConfig(
            CassandraSourceConnectorConfig config, ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder) {
        AuthenticatorConfig authConfig = config.getAuthenticatorConfig();
        if (authConfig.getProvider() == AuthenticatorConfig.Provider.PLAIN) {
            configLoaderBuilder
                    .withClass(AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
                    .withString(AUTH_PROVIDER_USER_NAME, authConfig.getUsername())
                    .withString(AUTH_PROVIDER_PASSWORD, authConfig.getPassword());
        } else if (authConfig.getProvider() == AuthenticatorConfig.Provider.GSSAPI) {
            Path keyTabPath = authConfig.getKeyTabPath();
            Map<String, String> loginConfig;
            if (keyTabPath == null) {
                // Rely on the ticket cache.
                ImmutableMap.Builder<String, String> loginConfigBuilder =
                        ImmutableMap.<String, String>builder()
                                .put("useTicketCache", "true")
                                .put("refreshKrb5Config", "true")
                                .put("renewTGT", "true");
                if (!authConfig.getPrincipal().isEmpty()) {
                    loginConfigBuilder.put("principal", authConfig.getPrincipal());
                }
                loginConfig = loginConfigBuilder.build();
            } else {
                // Authenticate with the keytab file
                loginConfig =
                        ImmutableMap.of(
                                "principal",
                                authConfig.getPrincipal(),
                                "useKeyTab",
                                "true",
                                "refreshKrb5Config",
                                "true",
                                "keyTab",
                                authConfig.getKeyTabPath().toString());
            }
            configLoaderBuilder
                    .withClass(AUTH_PROVIDER_CLASS, DseGssApiAuthProvider.class)
                    .withString(AUTH_PROVIDER_SERVICE, authConfig.getService())
                    .withStringMap(
                            AUTH_PROVIDER_SASL_PROPERTIES, ImmutableMap.of("javax.security.sasl.qop", "auth"))
                    .withStringMap(DseDriverOption.AUTH_PROVIDER_LOGIN_CONFIGURATION, loginConfig);
        }
    }

    @NonNull
    private static ProgrammaticDriverConfigLoaderBuilder dseProgrammaticBuilderWithFallback(
            Config properties) {
        ConfigFactory.invalidateCaches();
        return new DefaultProgrammaticDriverConfigLoaderBuilder(
                () ->
                        ConfigFactory.defaultApplication()
                                .withFallback(properties)
                                .withFallback(ConfigFactory.parseResourcesAnySyntax("dse-reference"))
                                .withFallback(ConfigFactory.defaultReference()),
                DefaultDriverConfigLoader.DEFAULT_ROOT_PATH);
    }

    @Override
    public void close() throws Exception {
        this.cqlSession.close();
    }

    public Tuple3<Row, ConsistencyLevel, KeyspaceMetadata> selectRow(String keyspaceName,
                                                                     String tableName,
                                                                     Map<String, Object> pk,
                                                                     UUID nodeId,
                                                                     List<ConsistencyLevel> consistencyLevels)
            throws ExecutionException, InterruptedException {
        return selectRowAsync(keyspaceName, tableName, pk, nodeId, consistencyLevels)
                .toCompletableFuture().get();
    }

    public Tuple2<KeyspaceMetadata, TableMetadata> getTableMetadata(String keyspace, String table) {
        Metadata metadata = cqlSession.getMetadata();
        Optional<KeyspaceMetadata> keyspaceMetadataOptional = metadata.getKeyspace(keyspace);
        if(!keyspaceMetadataOptional.isPresent()) {
            throw new IllegalArgumentException("No metadata for keyspace " + keyspace);
        }
        Optional<TableMetadata> tableMetadataOptional = keyspaceMetadataOptional.get().getTable(table);
        if(!tableMetadataOptional.isPresent()) {
            throw new IllegalArgumentException("No metadata for table " + keyspace + "." + table);
        }
        return new Tuple2<>(keyspaceMetadataOptional.get(), tableMetadataOptional.get());
    }

    /**
     * Try to read CL=ALL (could be LOCAL_ALL), retry LOCAL_QUORUM, retry LOCAL_ONE.
     *
     */
    public CompletionStage<Tuple3<Row, ConsistencyLevel, KeyspaceMetadata>> selectRowAsync(String keyspaceName,
                                                                                           String tableName,
                                                                                           Map<String, Object> pk,
                                                                                           UUID nodeId,
                                                                                           List<ConsistencyLevel> consistencyLevels) {
        Select query = selectFrom(keyspaceName, tableName).all();
        List<Object> values = new ArrayList<>(pk.size());
        for(Map.Entry<String, Object> entry : pk.entrySet()) {
            values.add(entry.getValue());
            query = query.whereColumn(entry.getKey()).isEqualTo(bindMarker());
        }
        SimpleStatement statement = query.build(pk);

        // set the coordinator node
        Node node = null;
        if(nodeId != null) {
            node = cqlSession.getMetadata().getNodes().get(nodeId);
            if(node != null) {
                statement.setNode(node);
            }
        }
        log.debug("Executing query={} pk={} coordinator={}", query.toString(), pk, node);

        return executeWithDowngradeConsistencyRetry(cqlSession, keyspaceName, statement, consistencyLevels)
                .thenApply(tuple -> {
                    log.debug("Read cl={} coordinator={} pk={}",
                            tuple._2, tuple._1.getExecutionInfo().getCoordinator().getHostId(), pk);
                    KeyspaceMetadata keyspaceMetadata = cqlSession.getMetadata().getKeyspace(keyspaceName).get();
                    Row row = tuple._1.one();
                    return new Tuple3<>(
                            row,
                            tuple._2,
                            keyspaceMetadata);
                })
                .whenComplete((tuple, error) -> {
                    if(error != null) {
                        log.warn("Failed to retrieve row: {}", error); }
                });
    }

    CompletionStage<Tuple2<AsyncResultSet, ConsistencyLevel>> executeWithDowngradeConsistencyRetry(
            CqlSession cqlSession,
            String keyspaceName,
            SimpleStatement statement,
            List<ConsistencyLevel> consistencyLevels) {
        final ConsistencyLevel cl = consistencyLevels.remove(0);
        statement.setConsistencyLevel(cl);
        log.debug("Trying with CL={} statement={}", cl, statement);
        final CompletionStage<Tuple2<AsyncResultSet, ConsistencyLevel>> completionStage =
                cqlSession.executeAsync(statement).thenApply(rx -> new Tuple2<>(rx, cl));
        return completionStage
                .handle((r, ex) -> {
                    if(ex == null || !(ex instanceof UnavailableException) || consistencyLevels.isEmpty()) {
                        log.debug("Executed CL={} statement={}", cl, statement);
                        return completionStage;
                    }
                    return completionStage
                            .handleAsync((r1, ex1) ->
                                    executeWithDowngradeConsistencyRetry(cqlSession, keyspaceName, statement, consistencyLevels))
                            .thenCompose(Function.identity());
                })
                .thenCompose(Function.identity());
    }
}