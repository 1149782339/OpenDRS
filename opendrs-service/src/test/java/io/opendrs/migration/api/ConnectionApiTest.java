package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConnectionApiTest {

    private static final String BASE = "/api/v1/migration/connections";
    private static final String TASKS = "/api/v1/migration/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @MockitoBean
    private JdbcConnectionFactory jdbcConnectionFactory;

    @BeforeEach
    void resetFactory() {
        Mockito.reset(jdbcConnectionFactory);
    }

    @Test
    void createPersistsExtraAndMasksPassword() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("oracle-hr", oracleConnectionJson("secret-oracle"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.name").value("oracle-hr"))
                .andExpect(jsonPath("$.data.type").value("ORACLE"))
                .andExpect(jsonPath("$.data.host").value("10.0.0.1"))
                .andExpect(jsonPath("$.data.port").value(1521))
                .andExpect(jsonPath("$.data.database").value("ORCL"))
                .andExpect(jsonPath("$.data.username").value("cdc"))
                .andExpect(jsonPath("$.data.password").value("***"))
                .andExpect(jsonPath("$.data.extra.pdb").value("ORCLPDB1"))
                .andExpect(jsonPath("$.data.extra.connectionType").value("SERVICE"))
                .andExpect(jsonPath("$.data.extra.customFlag").value(1))
                .andReturn();

        long id = jsonMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        ConnectionInfo stored = connectionInfoMapper.findById(id);
        assertThat(stored.getPassword()).isEqualTo("secret-oracle");
        assertThat(stored.getExtra()).containsEntry("pdb", "ORCLPDB1");
        assertThat(stored.getExtra()).containsEntry("connectionType", "SERVICE");
        assertThat(stored.getExtra().get("customFlag")).isIn(1, 1L);
    }

    @Test
    void createAcceptsPostgresqlType() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("pg-app", postgresqlConnectionJson("secret-pg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.name").value("pg-app"))
                .andExpect(jsonPath("$.data.type").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data.host").value("10.0.0.3"))
                .andExpect(jsonPath("$.data.port").value(5432))
                .andExpect(jsonPath("$.data.database").value("appdb"))
                .andExpect(jsonPath("$.data.extra.sslmode").value("require"));
    }

    @Test
    void listReturnsCreatedConnectionsAndMasksPasswords() throws Exception {
        long mysqlId = createConnection("list-mysql", mysqlConnectionJson("secret-mysql"));
        long pgId = createConnection("list-pg", postgresqlConnectionJson("secret-pg"));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].name").value("list-mysql"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].type").value("MYSQL"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].host").value("10.0.0.2"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].port").value(3306))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].database").value("hr"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].username").value("drs"))
                .andExpect(jsonPath("$.data[?(@.id==" + mysqlId + ")].password").value("***"))
                .andExpect(jsonPath("$.data[?(@.id==" + pgId + ")].name").value("list-pg"))
                .andExpect(jsonPath("$.data[?(@.id==" + pgId + ")].type").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data[?(@.id==" + pgId + ")].password").value("***"));
    }

    @Test
    void duplicateNameReturns1001() throws Exception {
        createConnection("dup-conn", mysqlConnectionJson("pw"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("dup-conn", mysqlConnectionJson("pw"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Connection name already exists: dup-conn"));
    }

    @Test
    void deleteOkThenMissingReturns1004() throws Exception {
        long id = createConnection("to-delete", mysqlConnectionJson("pw"));

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data").value(nullValue()));

        assertThat(connectionInfoMapper.findById(id)).isNull();

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("Connection not found: " + id));
    }

    @Test
    void deleteMissingReturns1004() throws Exception {
        mockMvc.perform(delete(BASE + "/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("Connection not found: 999999"));
    }

    @Test
    void deleteWhileReferencedByTaskReturns1006() throws Exception {
        mockMvc.perform(post(TASKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("conn-ref-task")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));

        ConnectionInfo source = connectionInfoMapper.findByName("conn-ref-task-source");
        assertThat(source).isNotNull();

        mockMvc.perform(delete(BASE + "/" + source.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1006))
                .andExpect(jsonPath("$.message").value(
                        "Connection " + source.getId() + " is referenced by a migration task"));
        assertThat(connectionInfoMapper.findById(source.getId())).isNotNull();
    }

    @Test
    void testAdHocSuccessAndFailure() throws Exception {
        JdbcConnection ok = Mockito.mock(JdbcConnection.class);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class))).thenReturn(ok);

        mockMvc.perform(post(BASE + "/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oracleConnectionJson("secret-oracle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true));

        verify(ok).ping();
        verify(ok).close();

        Mockito.reset(jdbcConnectionFactory);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class)))
                .thenThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "refused"));

        mockMvc.perform(post(BASE + "/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oracleConnectionJson("secret-oracle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("refused"));
    }

    @Test
    void testByIdLoadsPersistedPasswordAndPings() throws Exception {
        long id = createConnection("stored-test", oracleConnectionJson("stored-secret"));

        JdbcConnection ok = Mockito.mock(JdbcConnection.class);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class))).thenReturn(ok);

        mockMvc.perform(post(BASE + "/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true));

        ArgumentCaptor<ConnectionInfo> captor = ArgumentCaptor.forClass(ConnectionInfo.class);
        verify(jdbcConnectionFactory).open(captor.capture());
        ConnectionInfo passed = captor.getValue();
        assertThat(passed.getPassword()).isEqualTo("stored-secret");
        assertThat(passed.getHost()).isEqualTo("10.0.0.1");
        assertThat(passed.getExtra()).containsEntry("pdb", "ORCLPDB1");
        verify(ok).ping();

        Mockito.reset(jdbcConnectionFactory);
        JdbcConnection failing = Mockito.mock(JdbcConnection.class);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class))).thenReturn(failing);
        doThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "auth failed")).when(failing).ping();

        mockMvc.perform(post(BASE + "/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("auth failed"));
    }

    private long createConnection(String name, String connectionJson) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, connectionJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.password").value("***"))
                .andExpect(jsonPath("$.data.name").value(name))
                .andReturn();
        JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private static String createBody(String name, String connectionJson) {
        return """
                {
                  "name": "%s",
                  "connection": %s
                }
                """.formatted(name, connectionJson);
    }

    private static String oracleConnectionJson(String password) {
        return """
                {
                  "type": "ORACLE",
                  "host": "10.0.0.1",
                  "port": 1521,
                  "database": "ORCL",
                  "username": "cdc",
                  "password": "%s",
                  "extra": {
                    "pdb": "ORCLPDB1",
                    "connectionType": "SERVICE",
                    "customFlag": 1
                  }
                }
                """.formatted(password);
    }

    private static String postgresqlConnectionJson(String password) {
        return """
                {
                  "type": "POSTGRESQL",
                  "host": "10.0.0.3",
                  "port": 5432,
                  "database": "appdb",
                  "username": "drs",
                  "password": "%s",
                  "extra": {
                    "sslmode": "require"
                  }
                }
                """.formatted(password);
    }

    private static String mysqlConnectionJson(String password) {
        return """
                {
                  "type": "MYSQL",
                  "host": "10.0.0.2",
                  "port": 3306,
                  "database": "hr",
                  "username": "drs",
                  "password": "%s",
                  "extra": {
                    "useSsl": false,
                    "serverTimezone": "UTC"
                  }
                }
                """.formatted(password);
    }

    private static String taskBody(String name) {
        return """
                {
                  "name": "%s",
                  "mode": "FULL_ONLY",
                  "source": {
                    "type": "ORACLE",
                    "host": "10.0.0.1",
                    "port": 1521,
                    "database": "ORCL",
                    "username": "cdc",
                    "password": "secret-source",
                    "extra": { "pdb": "ORCLPDB1" }
                  },
                  "target": {
                    "type": "MYSQL",
                    "host": "10.0.0.2",
                    "port": 3306,
                    "database": "hr",
                    "username": "drs",
                    "password": "secret-target"
                  },
                  "tables": {
                    "objects": [
                      { "schema": "HR", "tables": ["EMPLOYEES"] }
                    ]
                  }
                }
                """.formatted(name);
    }
}
