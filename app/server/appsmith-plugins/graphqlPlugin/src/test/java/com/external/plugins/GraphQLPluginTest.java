package com.external.plugins;

import com.appsmith.external.datatypes.ClientDataType;
import com.appsmith.external.dtos.ExecuteActionDTO;
import com.appsmith.external.exceptions.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.helpers.restApiUtils.connections.APIConnection;
import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionRequest;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.ApiKeyAuth;
import com.appsmith.external.models.AuthenticationDTO;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.OAuth2;
import com.appsmith.external.models.PaginationField;
import com.appsmith.external.models.PaginationType;
import com.appsmith.external.models.Param;
import com.appsmith.external.models.Property;
import com.appsmith.external.services.SharedConfig;
import com.external.plugins.exceptions.GraphQLPluginError;
import com.external.utils.GraphQLHintMessageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import javax.crypto.SecretKey;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.appsmith.external.constants.Authentication.API_KEY;
import static com.appsmith.external.constants.Authentication.OAUTH2;
import static com.appsmith.external.helpers.PluginUtils.setValueSafelyInFormData;
import static com.appsmith.external.helpers.restApiUtils.helpers.HintMessageUtils.DUPLICATE_ATTRIBUTE_LOCATION;
import static com.appsmith.external.helpers.restApiUtils.helpers.HintMessageUtils.DUPLICATE_ATTRIBUTE_LOCATION.ACTION_CONFIG_ONLY;
import static com.appsmith.external.helpers.restApiUtils.helpers.HintMessageUtils.DUPLICATE_ATTRIBUTE_LOCATION.DATASOURCE_AND_ACTION_CONFIG;
import static com.appsmith.external.helpers.restApiUtils.helpers.HintMessageUtils.DUPLICATE_ATTRIBUTE_LOCATION.DATASOURCE_CONFIG_ONLY;
import static com.external.utils.GraphQLBodyUtils.QUERY_VARIABLES_INDEX;
import static com.external.utils.GraphQLPaginationUtils.updateVariablesWithPaginationValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class GraphQLPluginTest {

    private static GraphQLHintMessageUtils hintMessageUtils;

    private static MockWebServer mockEndpoint;

    public static class MockSharedConfig implements SharedConfig {

        @Override
        public int getCodecSize() {
            return 10 * 1024 * 1024;
        }

        @Override
        public int getMaxResponseSize() {
            return 10000;
        }

        @Override
        public String getRemoteExecutionUrl() {
            return "";
        }
    }

    GraphQLPlugin.GraphQLPluginExecutor pluginExecutor =
            new GraphQLPlugin.GraphQLPluginExecutor(new MockSharedConfig());

    @SuppressWarnings("rawtypes")
    @Container
    public static GenericContainer graphqlContainer = new GenericContainer(
                    CompletableFuture.completedFuture("appsmith/test-event-driver"))
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    @BeforeEach
    public void setUp() throws IOException {
        hintMessageUtils = new GraphQLHintMessageUtils();
        mockEndpoint = new MockWebServer();
        mockEndpoint.start();
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockEndpoint.shutdown();
    }

    private DatasourceConfiguration getDefaultDatasourceConfig() {
        String address = graphqlContainer.getHost();
        Integer port = graphqlContainer.getFirstMappedPort();
        DatasourceConfiguration dsConfig = new DatasourceConfiguration();
        dsConfig.setUrl("http://" + address + ":" + port + "/graphql");
        return dsConfig;
    }

    private ActionConfiguration getDefaultActionConfiguration() {
        ActionConfiguration actionConfig = new ActionConfiguration();
        actionConfig.setHeaders(List.of(new Property("content-type", "application/json")));
        actionConfig.setHttpMethod(HttpMethod.POST);
        String requestBody =
                """
                query {
                  allPosts(first: 1) {
                    nodes {
                      id
                    }
                  }
                }""";
        actionConfig.setBody(requestBody);
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("smartSubstitution", "true"));
        properties.add(new Property("queryVariables", ""));
        actionConfig.setPluginSpecifiedTemplates(properties);
        return actionConfig;
    }

    @Test
    public void testValidGraphQLApiExecutionWithQueryVariablesWithHttpPost() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        String queryBody =
                """
                query($limit: Int) {
                  allPosts(first: $limit) {
                    nodes {
                      id
                    }
                  }
                }""";
        actionConfig.setBody(queryBody);
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("", "true"));
        properties.add(new Property("", """
                {
                  "limit": 2
                }"""));
        actionConfig.setPluginSpecifiedTemplates(properties);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    JsonNode data = ((ObjectNode) result.getBody()).get("data");
                    String expectedResult = "{\"allPosts\":{\"nodes\":[{\"id\":1},{\"id\":2}]}}";
                    assertEquals(expectedResult, data.toString());
                })
                .verifyComplete();
    }

    @Test
    public void testValidGraphQLApiExecutionWithWhitespacesInUrl() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();

        // changing the url to add whitespaces at the start and end of the url
        String url = dsConfig.getUrl();
        url = String.format("%-" + (url.length() + 4) + "s", url);
        url = String.format("%" + (url.length() + 4) + "s", url);
        dsConfig.setUrl(url);
        String queryBody =
                """
                query($limit: Int) {
                  allPosts(first: $limit) {
                    nodes {
                      id
                    }
                  }
                }""";
        actionConfig.setBody(queryBody);
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("", "true"));
        properties.add(new Property("", """
                {
                  "limit": 2
                }"""));
        actionConfig.setPluginSpecifiedTemplates(properties);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                })
                .verifyComplete();
    }

    @Test
    public void testValidGraphQLApiExecutionWithQueryVariablesWithHttpGet() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setUrl("https://rickandmortyapi.com/graphql");
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHttpMethod(HttpMethod.GET);
        actionConfig.setBody(
                """
                query Query {
                  character(id: 1) {
                    created
                  }
                }
                """);
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("smartSubstitution", "true"));
        properties.add(new Property("queryVariables", ""));
        actionConfig.setPluginSpecifiedTemplates(properties);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    JsonNode data = ((ObjectNode) result.getBody()).get("data");
                    String expectedResult = "{\"character\":{\"created\":\"2017-11-04T18:48:46.250Z\"}}";
                    assertEquals(expectedResult, data.toString());
                })
                .verifyComplete();
    }

    @Test
    public void testValidGraphQLApiExecutionWithoutQueryVariables() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHeaders(List.of(new Property("content-type", "application/json")));
        actionConfig.setHttpMethod(HttpMethod.POST);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    JsonNode data = ((ObjectNode) result.getBody()).get("data");
                    String expectedResult = "{\"allPosts\":{\"nodes\":[{\"id\":1}]}}";
                    assertEquals(expectedResult, data.toString());
                })
                .verifyComplete();
    }

    @Test
    public void testValidGraphQLApiExecutionWithContentTypeGraphql() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHeaders(List.of(new Property("content-type", "application/graphql"))); // content-type graphql
        actionConfig.setHttpMethod(HttpMethod.POST);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    String expectedResult = "{\"data\":{\"allPosts\":{\"nodes\":[{\"id\":1}]}}}";
                    assertEquals(result.getBody().toString(), expectedResult);
                })
                .verifyComplete();
    }

    @Test
    public void testInvalidQueryBodyError() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setBody(
                """
                query Capsules {
                  capsules(limit: 1, offset: 0) {
                    dragon\s
                      id
                      name
                    }
                  }
                }""");
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono).verifyErrorSatisfies(error -> {
            assertTrue(error instanceof AppsmithPluginException);
            String expectedMessage = "Invalid GraphQL body: Invalid syntax encountered. There are extra "
                    + "tokens in the text that have not been consumed. Offending token '}' at line 8 column 1";
            assertEquals(expectedMessage, error.getMessage());
        });
    }

    @Test
    public void testInvalidQueryVariablesError() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setBody(
                """
                query Capsules($limit: Int, $offset: Int) {
                  capsules(limit: $limit, offset: $offset) {
                    dragon {
                      id
                      name
                    }
                  }
                }""");
        actionConfig
                .getPluginSpecifiedTemplates()
                .get(QUERY_VARIABLES_INDEX)
                .setValue(
                        """
                {
                  "limit": 1
                  "offset": 0
                }""");
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);
        StepVerifier.create(resultMono).verifyErrorSatisfies(error -> {
            assertTrue(error instanceof AppsmithPluginException);
            String expectedMessage = "GraphQL query variables are not in proper JSON format: Expected a ',' "
                    + "or '}' at 18 [character 3 line 3]";
            assertEquals(expectedMessage, error.getMessage());
        });
    }

    @Test
    public void testValidSignature() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        final String secretKey = "a-random-key-that-should-be-32-chars-long-at-least";
        dsConfig.setProperties(
                List.of(new Property("isSendSessionEnabled", "Y"), new Property("sessionSignatureKey", secretKey)));

        ActionConfiguration actionConfig = getDefaultActionConfiguration();

        actionConfig.setHttpMethod(HttpMethod.GET);
        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());

                    try {
                        final RecordedRequest recordedRequest = mockEndpoint.takeRequest(30, TimeUnit.SECONDS);
                        assert recordedRequest != null;
                        String token = recordedRequest.getHeaders().get("X-Appsmith-Signature");

                        final SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
                        final String issuer = Jwts.parserBuilder()
                                .setSigningKey(key)
                                .build()
                                .parseClaimsJws(token)
                                .getBody()
                                .getIssuer();
                        assertEquals("Appsmith", issuer);
                        final Iterator<Map.Entry<String, JsonNode>> fields =
                                ((ObjectNode) result.getRequest().getHeaders()).fields();
                        fields.forEachRemaining(field -> {
                            if ("X-Appsmith-Signature".equalsIgnoreCase(field.getKey())) {
                                assertEquals(token, field.getValue().get(0).asText());
                            }
                        });
                    } catch (InterruptedException e) {
                        assert false : e.getMessage();
                    }
                })
                .verifyComplete();
    }

    @Test
    public void testValidateDatasource_invalidAuthentication() {
        DatasourceConfiguration datasourceConfiguration = getDefaultDatasourceConfig();
        OAuth2 oAuth2 = new OAuth2();
        oAuth2.setGrantType(OAuth2.Type.CLIENT_CREDENTIALS);
        datasourceConfiguration.setAuthentication(oAuth2);

        Mono<GraphQLPlugin.GraphQLPluginExecutor> pluginExecutorMono = Mono.just(pluginExecutor);
        Mono<Set<String>> invalidsMono =
                pluginExecutorMono.map(executor -> executor.validateDatasource(datasourceConfiguration));

        StepVerifier.create(invalidsMono)
                .assertNext(invalids -> assertTrue(invalids.containsAll(
                        Set.of("Missing Client ID", "Missing client secret", "Missing access token URL"))));
    }

    /**
     * This method tests for the following use cases:
     * 1. Substitute a number type value in place of a query parameter.
     * 2. Substitute a string type value in place of a query parameter.
     * 3. Substitute a boolean type value in place of a query parameter.
     * 4. Substitute a schema type value in the query body.
     */
    @Test
    public void testSmartSubstitutionInQueryBodyForNumberStringBooleanAndSchemaTypes() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();

        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setBody(
                """
                query Capsules {
                  capsules(myNum: {{Input1.text}}, myStr: {{Input2.text}}, myBool: {{Input3.text}}) {
                    dragon {
                      {{Input4.text}}
                      name
                    }
                  }
                }


                """);

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        List<Param> params = new ArrayList<>();
        Param param1 = new Param();
        param1.setKey("Input1.text");
        param1.setValue("3");
        param1.setClientDataType(ClientDataType.NUMBER);
        params.add(param1);
        Param param2 = new Param();
        param2.setKey("Input2.text");
        param2.setValue("this is a string! Yay :D");
        param2.setClientDataType(ClientDataType.STRING);
        params.add(param2);
        Param param3 = new Param();
        param3.setKey("Input3.text");
        param3.setValue("true");
        param3.setClientDataType(ClientDataType.BOOLEAN);
        params.add(param3);
        Param param4 = new Param();
        param4.setKey("Input4.text");
        param4.setValue("id");
        param4.setClientDataType(ClientDataType.STRING);
        params.add(param4);
        executeActionDTO.setParams(params);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, executeActionDTO, dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    String expectedQueryBody =
                            """
                            query Capsules {
                              capsules(myNum: 3, myStr: "this is a string! Yay :D", myBool: true) {
                                dragon {
                                  id
                                  name
                                }
                              }
                            }


                            """;
                    JSONParser jsonParser = new JSONParser(JSONParser.MODE_PERMISSIVE);
                    try {
                        final RecordedRequest recordedRequest = mockEndpoint.takeRequest(30, TimeUnit.SECONDS);
                        assert recordedRequest != null;
                        final Buffer recordedRequestBody = recordedRequest.getBody();
                        byte[] bodyBytes = new byte[(int) recordedRequestBody.size()];
                        recordedRequestBody.readFully(bodyBytes);
                        recordedRequestBody.close();
                        JSONObject resultJson = (JSONObject) jsonParser.parse(new String(bodyBytes));
                        String resultData = resultJson.getAsString("query");
                        assertEquals(expectedQueryBody, resultData);
                    } catch (ParseException | EOFException | InterruptedException e) {
                        assert false : e.getMessage();
                    }

                    // Assert the debug request parameters are getting set.
                    ActionExecutionRequest request = result.getRequest();
                    List<Map.Entry<String, String>> parameters = (List<Map.Entry<String, String>>)
                            request.getProperties().get("smart-substitution-parameters");
                    assertEquals(4, parameters.size());

                    Map.Entry<String, String> parameterEntry = parameters.get(0);
                    assertEquals("3", parameterEntry.getKey());
                    assertEquals("GRAPHQL_BODY_INTEGER", parameterEntry.getValue());

                    parameterEntry = parameters.get(1);
                    assertEquals("this is a string! Yay :D", parameterEntry.getKey());
                    assertEquals("GRAPHQL_BODY_STRING", parameterEntry.getValue());

                    parameterEntry = parameters.get(2);
                    assertEquals("true", parameterEntry.getKey());
                    assertEquals("GRAPHQL_BODY_BOOLEAN", parameterEntry.getValue());

                    parameterEntry = parameters.get(3);
                    assertEquals("id", parameterEntry.getKey());
                    assertEquals("GRAPHQL_BODY_PARTIAL", parameterEntry.getValue());
                })
                .verifyComplete();
    }

    /**
     * This method checks for the use case where the entire query body is provided via dynamic binding by the user.
     */
    @Test
    public void testSmartSubstitutionInQueryBodyForFullBodySubstitution() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();

        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setBody("{{Input1.text}}");

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        List<Param> params = new ArrayList<>();
        Param param1 = new Param();
        param1.setKey("Input1.text");
        param1.setValue(
                """
                query Capsules {
                  capsules(limit: 1, offset: 0) {
                    dragon {
                      id
                      name
                    }
                  }
                }""");
        param1.setClientDataType(ClientDataType.STRING);
        params.add(param1);
        executeActionDTO.setParams(params);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, executeActionDTO, dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    String expectedQueryBody =
                            """
                            query Capsules {
                              capsules(limit: 1, offset: 0) {
                                dragon {
                                  id
                                  name
                                }
                              }
                            }""";
                    JSONParser jsonParser = new JSONParser(JSONParser.MODE_PERMISSIVE);
                    try {

                        final RecordedRequest recordedRequest = mockEndpoint.takeRequest(30, TimeUnit.SECONDS);
                        assert recordedRequest != null;
                        final Buffer recordedRequestBody = recordedRequest.getBody();
                        byte[] bodyBytes = new byte[(int) recordedRequestBody.size()];
                        recordedRequestBody.readFully(bodyBytes);
                        recordedRequestBody.close();

                        JSONObject resultJson = (JSONObject) jsonParser.parse(new String(bodyBytes));
                        String resultData = resultJson.getAsString("query");
                        assertEquals(expectedQueryBody, resultData);
                    } catch (ParseException | EOFException | InterruptedException e) {
                        assert false : e.getMessage();
                    }

                    // Assert the debug request parameters are getting set.
                    ActionExecutionRequest request = result.getRequest();
                    List<Map.Entry<String, String>> parameters = (List<Map.Entry<String, String>>)
                            request.getProperties().get("smart-substitution-parameters");
                    assertEquals(1, parameters.size());

                    Map.Entry<String, String> parameterEntry = parameters.get(0);
                    assertEquals(
                            parameterEntry.getKey(),
                            """
                            query Capsules {
                              capsules(limit: 1, offset: 0) {
                                dragon {
                                  id
                                  name
                                }
                              }
                            }""");
                    assertEquals("GRAPHQL_BODY_FULL", parameterEntry.getValue());
                })
                .verifyComplete();
    }

    @Test
    public void testSmartSubstitutionQueryVariables() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();

        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        String queryVariables =
                """
                {
                  "name" : {{Input1.text}},
                  "email" : {{Input2.text}},
                  "username" : {{Input3.text}},
                  "password" : "{{Input4.text}}",
                  "newField" : "{{Input5.text}}",
                  "tableRow" : {{Table1.selectedRow}},
                  "table" : "{{Table1.tableData}}"
                }""";
        actionConfig.getPluginSpecifiedTemplates().get(QUERY_VARIABLES_INDEX).setValue(queryVariables);

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        List<Param> params = new ArrayList<>();
        Param param1 = new Param();
        param1.setKey("Input1.text");
        param1.setValue("this is a string! Yay :D");
        param1.setClientDataType(ClientDataType.STRING);
        params.add(param1);
        Param param3 = new Param();
        param3.setKey("Input2.text");
        param3.setValue("true");
        param3.setClientDataType(ClientDataType.BOOLEAN);
        params.add(param3);
        Param param4 = new Param();
        param4.setKey("Input3.text");
        param4.setValue("0");
        param4.setClientDataType(ClientDataType.NUMBER);
        params.add(param4);
        Param param5 = new Param();
        param5.setKey("Input4.text");
        param5.setValue("12/01/2018");
        param5.setClientDataType(ClientDataType.STRING);
        params.add(param5);
        Param param6 = new Param();
        param6.setKey("Input5.text");
        param6.setValue("null");
        param6.setClientDataType(ClientDataType.NULL);
        params.add(param6);
        Param param7 = new Param();
        param7.setKey("Table1.selectedRow");
        param7.setValue(
                "{  \"id\": 2381224,  \"email\": \"michael.lawson@reqres.in\",  \"userName\": \"Michael Lawson\",  \"productName\": \"Chicken Sandwich\",  \"orderAmount\": 4.99}");
        param7.setClientDataType(ClientDataType.OBJECT);
        params.add(param7);
        Param param8 = new Param();
        param8.setKey("Table1.tableData");
        param8.setValue(
                "[  {    \"id\": 2381224,    \"email\": \"michael.lawson@reqres.in\",    \"userName\": \"Michael Lawson\",    \"productName\": \"Chicken Sandwich\",    \"orderAmount\": 4.99  },  {    \"id\": 2736212,    \"email\": \"lindsay.ferguson@reqres.in\",    \"userName\": \"Lindsay Ferguson\",    \"productName\": \"Tuna Salad\",    \"orderAmount\": 9.99  },  {    \"id\": 6788734,    \"email\": \"tobias.funke@reqres.in\",    \"userName\": \"Tobias Funke\",    \"productName\": \"Beef steak\",    \"orderAmount\": 19.99  }]");
        param8.setClientDataType(ClientDataType.ARRAY);
        params.add(param8);
        executeActionDTO.setParams(params);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, executeActionDTO, dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());
                    String expectedResultData =
                            "{\"password\":\"12\\/01\\/2018\",\"name\":\"this is a string! Yay :D\",\"newField\":null,\"tableRow\":{\"orderAmount\":4.99,\"id\":2381224,\"userName\":\"Michael Lawson\",\"email\":\"michael.lawson@reqres.in\",\"productName\":\"Chicken Sandwich\"},\"email\":true,\"table\":[{\"orderAmount\":4.99,\"id\":2381224,\"userName\":\"Michael Lawson\",\"email\":\"michael.lawson@reqres.in\",\"productName\":\"Chicken Sandwich\"},{\"orderAmount\":9.99,\"id\":2736212,\"userName\":\"Lindsay Ferguson\",\"email\":\"lindsay.ferguson@reqres.in\",\"productName\":\"Tuna Salad\"},{\"orderAmount\":19.99,\"id\":6788734,\"userName\":\"Tobias Funke\",\"email\":\"tobias.funke@reqres.in\",\"productName\":\"Beef steak\"}],\"username\":0}";
                    JSONParser jsonParser = new JSONParser(JSONParser.MODE_PERMISSIVE);
                    try {
                        final RecordedRequest recordedRequest = mockEndpoint.takeRequest(30, TimeUnit.SECONDS);
                        assert recordedRequest != null;
                        final Buffer recordedRequestBody = recordedRequest.getBody();
                        byte[] bodyBytes = new byte[(int) recordedRequestBody.size()];
                        recordedRequestBody.readFully(bodyBytes);
                        recordedRequestBody.close();

                        JSONObject resultJson = (JSONObject) jsonParser.parse(new String(bodyBytes));
                        Object resultData = resultJson.getAsString("variables");
                        assertEquals(expectedResultData, resultData);
                    } catch (ParseException | EOFException | InterruptedException e) {
                        assert false : e.getMessage();
                    }

                    // Assert the debug request parameters are getting set.
                    ActionExecutionRequest request = result.getRequest();
                    List<Map.Entry<String, String>> parameters = (List<Map.Entry<String, String>>)
                            request.getProperties().get("smart-substitution-parameters");
                    assertEquals(7, parameters.size());

                    Map.Entry<String, String> parameterEntry = parameters.get(0);
                    assertEquals("this is a string! Yay :D", parameterEntry.getKey());
                    assertEquals("STRING", parameterEntry.getValue());

                    parameterEntry = parameters.get(1);
                    assertEquals("true", parameterEntry.getKey());
                    assertEquals("BOOLEAN", parameterEntry.getValue());

                    parameterEntry = parameters.get(2);
                    assertEquals("0", parameterEntry.getKey());
                    assertEquals("INTEGER", parameterEntry.getValue());

                    parameterEntry = parameters.get(3);
                    assertEquals("12/01/2018", parameterEntry.getKey());
                    assertEquals("STRING", parameterEntry.getValue());

                    parameterEntry = parameters.get(4);
                    assertEquals("null", parameterEntry.getKey());
                    assertEquals("NULL", parameterEntry.getValue());
                })
                .verifyComplete();
    }

    @Test
    public void testRequestWithApiKeyHeader() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();

        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        AuthenticationDTO authenticationDTO = new ApiKeyAuth(ApiKeyAuth.Type.HEADER, "api_key", "Token", "test");
        dsConfig.setAuthentication(authenticationDTO);

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHeaders(List.of(
                new Property("content-type", "application/json"),
                new Property(HttpHeaders.AUTHORIZATION, "auth-value")));

        final APIConnection apiConnection =
                pluginExecutor.datasourceCreate(dsConfig).block();

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(apiConnection, new ExecuteActionDTO(), dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getRequest().getBody());
                    final Iterator<Map.Entry<String, JsonNode>> fields =
                            ((ObjectNode) result.getRequest().getHeaders()).fields();
                    fields.forEachRemaining(field -> {
                        if ("api_key".equalsIgnoreCase(field.getKey())
                                || HttpHeaders.AUTHORIZATION.equalsIgnoreCase(field.getKey())) {
                            assertEquals("****", field.getValue().get(0).asText());
                        }
                    });
                })
                .verifyComplete();
    }

    @Test
    public void testGetDuplicateHeadersAndParams() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        List<Property> dsHeaders = new ArrayList<>();
        dsHeaders.add(new Property("myHeader1", "myVal"));
        dsHeaders.add(new Property("myHeader1", "myVal")); // duplicate header
        dsHeaders.add(new Property("myHeader2", "myVal"));
        dsHeaders.add(new Property("myHeader2", "myVal")); // duplicate header
        dsHeaders.add(new Property("myHeader3", "myVal")); // unique header in datasource config
        dsConfig.setHeaders(dsHeaders);

        // This authentication mechanism will add `apiKey` as header.
        AuthenticationDTO authenticationDTO = new ApiKeyAuth(ApiKeyAuth.Type.HEADER, "apiKey", "Token", "test");
        dsConfig.setAuthentication(authenticationDTO);
        dsConfig.getAuthentication().setAuthenticationType(API_KEY);

        List<Property> dsParams = new ArrayList<>();
        dsParams.add(new Property("myParam1", "myVal"));
        dsParams.add(new Property("myParam1", "myVal")); // duplicate param
        dsParams.add(new Property("myParam2", "myVal"));
        dsParams.add(new Property("myParam2", "myVal")); // duplicate param
        dsParams.add(new Property("myParam3", "myVal")); // unique param in datasource
        dsConfig.setQueryParameters(dsParams);

        // Add headers to API query editor page.
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        ArrayList<Property> actionHeaders = new ArrayList<>();
        actionHeaders.add(new Property("myHeader3", "myVal")); // duplicate - because also inherited from datasource.
        actionHeaders.add(new Property("myHeader4", "myVal"));
        actionHeaders.add(new Property("myHeader4", "myVal")); // duplicate
        actionHeaders.add(new Property("myHeader5", "myVal"));
        actionHeaders.add(new Property("apiKey", "myVal")); // duplicate - because also inherited from authentication
        actionConfig.setHeaders(actionHeaders);

        // Add params to API query editor page.
        ArrayList<Property> actionParams = new ArrayList<>();
        actionParams.add(new Property("myParam3", "myVal")); // duplicate - because also inherited from datasource.
        actionParams.add(new Property("myParam4", "myVal"));
        actionParams.add(new Property("myParam4", "myVal")); // duplicate
        actionParams.add(new Property("myParam5", "myVal"));
        actionConfig.setQueryParameters(actionParams);

        /* Test duplicate headers in datasource configuration only */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> duplicateHeadersWithDsConfigOnly =
                hintMessageUtils.getAllDuplicateHeaders(null, dsConfig);

        // Header duplicates
        Set<String> expectedDuplicateHeaders = new HashSet<>();
        expectedDuplicateHeaders.add("myHeader1");
        expectedDuplicateHeaders.add("myHeader2");
        assertEquals(expectedDuplicateHeaders, duplicateHeadersWithDsConfigOnly.get(DATASOURCE_CONFIG_ONLY));

        /* Test duplicate query params in datasource configuration only */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> duplicateParamsWithDsConfigOnly =
                hintMessageUtils.getAllDuplicateParams(null, dsConfig);

        // Query param duplicates
        Set<String> expectedDuplicateParams = new HashSet<>();
        expectedDuplicateParams.add("myParam1");
        expectedDuplicateParams.add("myParam2");
        assertEquals(expectedDuplicateParams, duplicateParamsWithDsConfigOnly.get(DATASOURCE_CONFIG_ONLY));

        /* Test duplicate headers in datasource + action configuration */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> allDuplicateHeaders =
                hintMessageUtils.getAllDuplicateHeaders(actionConfig, dsConfig);

        // Header duplicates in ds config only
        expectedDuplicateHeaders = new HashSet<>();
        expectedDuplicateHeaders.add("myHeader1");
        expectedDuplicateHeaders.add("myHeader2");
        assertEquals(expectedDuplicateHeaders, allDuplicateHeaders.get(DATASOURCE_CONFIG_ONLY));

        // Header duplicates in action config only
        expectedDuplicateHeaders = new HashSet<>();
        expectedDuplicateHeaders.add("myHeader4");
        expectedDuplicateHeaders.add("myHeader4");
        assertEquals(expectedDuplicateHeaders, allDuplicateHeaders.get(ACTION_CONFIG_ONLY));

        // Header duplicates with one instance in action and another in datasource config
        expectedDuplicateHeaders = new HashSet<>();
        expectedDuplicateHeaders.add("myHeader3");
        expectedDuplicateHeaders.add("apiKey");
        assertEquals(expectedDuplicateHeaders, allDuplicateHeaders.get(DATASOURCE_AND_ACTION_CONFIG));

        /* Test duplicate query params in action + datasource config */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> allDuplicateParams =
                hintMessageUtils.getAllDuplicateParams(actionConfig, dsConfig);

        // Query param duplicates in datasource config only
        expectedDuplicateParams = new HashSet<>();
        expectedDuplicateParams.add("myParam1");
        expectedDuplicateParams.add("myParam2");
        assertEquals(expectedDuplicateParams, allDuplicateParams.get(DATASOURCE_CONFIG_ONLY));

        // Query param duplicates in action config only
        expectedDuplicateParams = new HashSet<>();
        expectedDuplicateParams.add("myParam4");
        assertEquals(expectedDuplicateParams, allDuplicateParams.get(ACTION_CONFIG_ONLY));

        // Query param duplicates in action + datasource config
        expectedDuplicateParams = new HashSet<>();
        expectedDuplicateParams.add("myParam3");
        assertEquals(expectedDuplicateParams, allDuplicateParams.get(DATASOURCE_AND_ACTION_CONFIG));
    }

    @Test
    public void testGetDuplicateHeadersWithOAuth() {
        // This authentication mechanism will add `Authorization` as header.
        OAuth2 authenticationDTO = new OAuth2();
        authenticationDTO.setAuthenticationType(OAUTH2);
        authenticationDTO.setGrantType(OAuth2.Type.AUTHORIZATION_CODE);
        authenticationDTO.setIsTokenHeader(true); // adds header `Authorization`
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setAuthentication(authenticationDTO);

        // Add headers to API query editor page.
        ArrayList<Property> actionHeaders = new ArrayList<>();
        actionHeaders.add(new Property("myHeader1", "myVal"));
        actionHeaders.add(new Property("Authorization", "myVal")); // duplicate - because also inherited from dsConfig
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHeaders(actionHeaders);

        /* Test duplicate headers in datasource + action configuration */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> allDuplicateHeaders =
                hintMessageUtils.getAllDuplicateHeaders(actionConfig, dsConfig);

        // Header duplicates in ds config only
        assertTrue(allDuplicateHeaders.get(DATASOURCE_CONFIG_ONLY).isEmpty());

        // Header duplicates in action config only
        assertTrue(allDuplicateHeaders.get(ACTION_CONFIG_ONLY).isEmpty());

        // Header duplicates with one instance in action and another in datasource config
        HashSet<String> expectedDuplicateHeaders = new HashSet<>();
        expectedDuplicateHeaders.add("Authorization");
        assertEquals(expectedDuplicateHeaders, allDuplicateHeaders.get(DATASOURCE_AND_ACTION_CONFIG));
    }

    @Test
    public void testGetDuplicateParamsWithOAuth() {
        // This authentication mechanism will add `access_token` as query param.
        OAuth2 authenticationDTO = new OAuth2();
        authenticationDTO.setAuthenticationType(OAUTH2);
        authenticationDTO.setGrantType(OAuth2.Type.AUTHORIZATION_CODE);
        authenticationDTO.setIsTokenHeader(false); // adds query param `access_token`
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setAuthentication(authenticationDTO);

        // Add headers to API query editor page.
        // Add params to API query editor page.
        ArrayList<Property> actionParams = new ArrayList<>();
        actionParams.add(new Property("myParam1", "myVal")); // duplicate - because also inherited from datasource.
        actionParams.add(new Property("access_token", "myVal"));
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setQueryParameters(actionParams);

        /* Test duplicate params in datasource + action configuration */
        Map<DUPLICATE_ATTRIBUTE_LOCATION, Set<String>> allDuplicateParams =
                hintMessageUtils.getAllDuplicateParams(actionConfig, dsConfig);

        // Param duplicates in ds config only
        assertTrue(allDuplicateParams.get(DATASOURCE_CONFIG_ONLY).isEmpty());

        // Param duplicates in action config only
        assertTrue(allDuplicateParams.get(ACTION_CONFIG_ONLY).isEmpty());

        // Param duplicates with one instance in action and another in datasource config
        HashSet<String> expectedDuplicateParams = new HashSet<>();
        expectedDuplicateParams.add("access_token");
        assertEquals(expectedDuplicateParams, allDuplicateParams.get(DATASOURCE_AND_ACTION_CONFIG));
    }

    /**
     * This test case is only meant to test the actual hint statement i.e. how it is worded. It is not meant to test
     * the correctness of duplication finding flow - since it is done as part of the test case named
     * `testGetDuplicateHeadersAndParams`. A separate test is used instead of a single test because the list of
     * duplicates is returned as a set, hence order cannot be ascertained beforehand.
     */
    @Test
    public void testHintMessageForDuplicateHeadersAndParamsWithDatasourceConfigOnly() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        List<Property> headers = new ArrayList<>();
        headers.add(new Property("myHeader1", "myVal"));
        headers.add(new Property("myHeader1", "myVal")); // duplicate
        headers.add(new Property("myHeader2", "myVal"));
        dsConfig.setHeaders(headers);

        List<Property> params = new ArrayList<>();
        params.add(new Property("myParam1", "myVal"));
        params.add(new Property("myParam1", "myVal")); // duplicate
        params.add(new Property("myParam2", "myVal"));
        dsConfig.setQueryParameters(params);
        dsConfig.setUrl("some_non_loc@lhost_url");

        Mono<Tuple2<Set<String>, Set<String>>> hintMessagesMono = pluginExecutor.getHintMessages(null, dsConfig);
        StepVerifier.create(hintMessagesMono)
                .assertNext(tuple -> {
                    Set<String> datasourceHintMessages = tuple.getT1();
                    Set<String> expectedDatasourceHintMessages = new HashSet<>();
                    expectedDatasourceHintMessages.add("API queries linked to this datasource may not run as expected"
                            + " because this datasource has duplicate definition(s) for param(s): [myParam1]. Please "
                            + "remove the duplicate definition(s) to resolve this warning. Please note that some of the"
                            + " authentication mechanisms also implicitly define a param.");

                    expectedDatasourceHintMessages.add("API queries linked to this datasource may not run as expected"
                            + " because this datasource has duplicate definition(s) for header(s): [myHeader1]. Please "
                            + "remove the duplicate definition(s) to resolve this warning. Please note that some of the"
                            + " authentication mechanisms also implicitly define a header.");
                    assertEquals(expectedDatasourceHintMessages, datasourceHintMessages);

                    Set<String> actionHintMessages = tuple.getT2();
                    Set<String> expectedActionHintMessages = new HashSet<>();
                    expectedActionHintMessages.add("Your API query may not run as expected because its datasource has"
                            + " duplicate definition(s) for param(s): [myParam1]. Please remove the duplicate "
                            + "definition(s) from the datasource to resolve this warning.");

                    expectedActionHintMessages.add("Your API query may not run as expected because its datasource has"
                            + " duplicate definition(s) for header(s): [myHeader1]. Please remove the duplicate "
                            + "definition(s) from the datasource to resolve this warning.");
                    assertEquals(expectedActionHintMessages, actionHintMessages);
                })
                .verifyComplete();
    }

    /**
     * This test case is only meant to test the actual hint statement i.e. how it is worded. It is not meant to test
     * the correctness of duplication finding flow - since it is done as part of the test case named
     * `testGetDuplicateHeadersAndParams`. A separate test is used instead of a single test because the list of
     * duplicates is returned as a set, hence order cannot be ascertained beforehand.
     */
    @Test
    public void testHintMessageForDuplicateHeadersAndParamsWithActionConfigOnly() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        List<Property> headers = new ArrayList<>();
        headers.add(new Property("myHeader1", "myVal"));
        headers.add(new Property("myHeader1", "myVal")); // duplicate
        headers.add(new Property("myHeader2", "myVal"));
        actionConfig.setHeaders(headers);

        List<Property> params = new ArrayList<>();
        params.add(new Property("myParam1", "myVal"));
        params.add(new Property("myParam1", "myVal")); // duplicate
        params.add(new Property("myParam2", "myVal"));
        actionConfig.setQueryParameters(params);

        DatasourceConfiguration dsConfig = new DatasourceConfiguration();
        dsConfig.setUrl("some_non_loc@lhost_url");

        Mono<Tuple2<Set<String>, Set<String>>> hintMessagesMono =
                pluginExecutor.getHintMessages(actionConfig, dsConfig);
        StepVerifier.create(hintMessagesMono)
                .assertNext(tuple -> {
                    Set<String> datasourceHintMessages = tuple.getT1();
                    assertTrue(datasourceHintMessages.isEmpty());

                    Set<String> actionHintMessages = tuple.getT2();
                    Set<String> expectedActionHintMessages = new HashSet<>();
                    expectedActionHintMessages.add("Your API query may not run as expected because it has duplicate "
                            + "definition(s) for header(s): [myHeader1]. Please remove the duplicate definition(s) from"
                            + " the 'Headers' tab to resolve this warning.");

                    expectedActionHintMessages.add("Your API query may not run as expected because it has duplicate "
                            + "definition(s) for param(s): [myParam1]. Please remove the duplicate definition(s) from "
                            + "the 'Params' tab to resolve this warning.");
                    assertEquals(expectedActionHintMessages, actionHintMessages);
                })
                .verifyComplete();
    }

    /**
     * This test case is only meant to test the actual hint statement i.e. how it is worded. It is not meant to test
     * the correctness of duplication finding flow - since it is done as part of the test case named
     * `testGetDuplicateHeadersAndParams`. A separate test is used instead of a single test because the list of
     * duplicates is returned as a set, hence order cannot be ascertained beforehand.
     */
    @Test
    public void testHintMessageForDuplicateHeaderWithOneInstanceEachInActionAndDsConfig() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        List<Property> headers = new ArrayList<>();
        headers.add(new Property("myHeader1", "myVal"));
        actionConfig.setHeaders(headers);

        // This authentication mechanism will add `myHeader1` as header implicitly.
        AuthenticationDTO authenticationDTO = new ApiKeyAuth(ApiKeyAuth.Type.HEADER, "myHeader1", "Token", "test");
        authenticationDTO.setAuthenticationType(API_KEY);
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setAuthentication(authenticationDTO);
        dsConfig.setUrl("some_non_loc@lhost_url");

        Mono<Tuple2<Set<String>, Set<String>>> hintMessagesMono =
                pluginExecutor.getHintMessages(actionConfig, dsConfig);
        StepVerifier.create(hintMessagesMono)
                .assertNext(tuple -> {
                    Set<String> datasourceHintMessages = tuple.getT1();
                    assertTrue(datasourceHintMessages.isEmpty());

                    Set<String> actionHintMessages = tuple.getT2();
                    Set<String> expectedActionHintMessages = new HashSet<>();
                    expectedActionHintMessages.add("Your API query may not run as expected because it has duplicate "
                            + "definition(s) for header(s): [myHeader1]. Please remove the duplicate definition(s) from"
                            + " the 'Headers' section of either the API query or the datasource. Please note that some "
                            + "of the authentication mechanisms also implicitly define a header.");

                    assertEquals(expectedActionHintMessages, actionHintMessages);
                })
                .verifyComplete();
    }

    /**
     * This test case is only meant to test the actual hint statement i.e. how it is worded. It is not meant to test
     * the correctness of duplication finding flow - since it is done as part of the test case named
     * `testGetDuplicateHeadersAndParams`. A separate test is used instead of a single test because the list of
     * duplicates is returned as a set, hence order cannot be ascertained beforehand.
     */
    @Test
    public void testHintMessageForDuplicateParamWithOneInstanceEachInActionAndDsConfig() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        List<Property> params = new ArrayList<>();
        params.add(new Property("myParam1", "myVal"));
        actionConfig.setQueryParameters(params);

        // This authentication mechanism will add `myHeader1` as query param implicitly.
        AuthenticationDTO authenticationDTO = new ApiKeyAuth(ApiKeyAuth.Type.QUERY_PARAMS, "myParam1", "Token", "test");
        authenticationDTO.setAuthenticationType(API_KEY);
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setAuthentication(authenticationDTO);
        dsConfig.setUrl("some_non_loc@lhost_url");

        Mono<Tuple2<Set<String>, Set<String>>> hintMessagesMono =
                pluginExecutor.getHintMessages(actionConfig, dsConfig);
        StepVerifier.create(hintMessagesMono)
                .assertNext(tuple -> {
                    Set<String> datasourceHintMessages = tuple.getT1();
                    assertTrue(datasourceHintMessages.isEmpty());

                    Set<String> actionHintMessages = tuple.getT2();
                    Set<String> expectedActionHintMessages = new HashSet<>();
                    expectedActionHintMessages.add("Your API query may not run as expected because it has duplicate "
                            + "definition(s) for param(s): [myParam1]. Please remove the duplicate definition(s) from"
                            + " the 'Params' section of either the API query or the datasource. Please note that some "
                            + "of the authentication mechanisms also implicitly define a param.");

                    assertEquals(expectedActionHintMessages, actionHintMessages);
                })
                .verifyComplete();
    }

    @Test
    public void testQueryParamsInDatasource() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();

        String baseUrl = String.format("http://%s:%s", mockEndpoint.getHostName(), mockEndpoint.getPort());
        dsConfig.setUrl(baseUrl);

        mockEndpoint.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setEncodeParamsToggle(true);

        List<Property> queryParams = new ArrayList<>();
        queryParams.add(new Property("query_key", "query val")); /* encoding changes 'query val' to 'query+val' */
        dsConfig.setQueryParameters(queryParams);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertTrue(result.getIsExecutionSuccess());
                    assertNotNull(result.getBody());

                    try {
                        RecordedRequest recordedRequest = mockEndpoint.takeRequest(30, TimeUnit.SECONDS);

                        assert recordedRequest != null;
                        String recordedRequestPath = recordedRequest.getPath();

                        String expectedUrl = "/?query_key=query+val";
                        assertEquals(expectedUrl, recordedRequestPath);
                    } catch (InterruptedException e) {
                        assert false : e.getMessage();
                    }
                })
                .verifyComplete();
    }

    @Test
    public void testDenyInstanceMetadataAws() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setUrl("http://169.254.169.254/latest/meta-data");

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHttpMethod(HttpMethod.GET);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> assertFalse(result.getIsExecutionSuccess()))
                .verifyComplete();
    }

    @Test
    public void testDenyInstanceMetadataAwsViaCname() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setUrl("http://169.254.169.254.nip.io/latest/meta-data");

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHttpMethod(HttpMethod.GET);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> assertFalse(result.getIsExecutionSuccess()))
                .verifyComplete();
    }

    @Test
    public void testDenyInstanceMetadataGcp() {
        DatasourceConfiguration dsConfig = getDefaultDatasourceConfig();
        dsConfig.setUrl("http://metadata.google.internal/latest/meta-data");

        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setHttpMethod(HttpMethod.GET);

        Mono<ActionExecutionResult> resultMono =
                pluginExecutor.executeParameterized(null, new ExecuteActionDTO(), dsConfig, actionConfig);
        StepVerifier.create(resultMono)
                .assertNext(result -> assertFalse(result.getIsExecutionSuccess()))
                .verifyComplete();
    }

    /**
     * This method tests that when the cursor value for forward pagination request is "null", then the cursor key
     * should be skipped from being added to the query variables. This would effectively turn the pagination query
     * into a simple non paginated query.
     */
    @Test
    public void testNextCursorKeyIsSkippedWhenCursorValueIsNull() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setPaginationType(PaginationType.CURSOR);
        actionConfig.getPluginSpecifiedTemplates().get(QUERY_VARIABLES_INDEX).setValue("{}");

        Map<String, Object> paginationDataMap = new HashMap<>();
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.limit.name", "first");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.limit.value", "3");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.cursor.name", "endCursor");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.cursor.value", "null");
        Property property = new Property();
        property.setKey("paginationData");
        property.setValue(paginationDataMap);
        actionConfig.getPluginSpecifiedTemplates().add(property);

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        executeActionDTO.setPaginationField(PaginationField.NEXT);

        updateVariablesWithPaginationValues(actionConfig, executeActionDTO);
        String expectedVariableString = "{\"first\":3}";
        assertEquals(
                expectedVariableString,
                actionConfig
                        .getPluginSpecifiedTemplates()
                        .get(QUERY_VARIABLES_INDEX)
                        .getValue());
    }

    /**
     * This method tests that when the cursor value for backward pagination request is "null", then the cursor key
     * should be skipped from being added to the query variables. This would effectively turn the pagination query
     * into a simple non paginated query.
     */
    @Test
    public void testPrevCursorKeyIsSkippedWhenCursorValueIsNull() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setPaginationType(PaginationType.CURSOR);
        actionConfig.getPluginSpecifiedTemplates().get(QUERY_VARIABLES_INDEX).setValue("{}");

        Map<String, Object> paginationDataMap = new HashMap<>();
        setValueSafelyInFormData(paginationDataMap, "cursorBased.previous.limit.name", "last");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.previous.limit.value", "3");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.previous.cursor.name", "startCursor");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.previous.cursor.value", "null");
        Property property = new Property();
        property.setKey("paginationData");
        property.setValue(paginationDataMap);
        actionConfig.getPluginSpecifiedTemplates().add(property);

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        executeActionDTO.setPaginationField(PaginationField.PREV);

        updateVariablesWithPaginationValues(actionConfig, executeActionDTO);
        String expectedVariableString = "{\"last\":3}";
        assertEquals(
                expectedVariableString,
                actionConfig
                        .getPluginSpecifiedTemplates()
                        .get(QUERY_VARIABLES_INDEX)
                        .getValue());
    }

    /**
     * This method tests that when the value for pagination type is "null", then the cursor key should be skipped from
     * being added to the query variables. This would ensure that when a user clicks on the `run` button after
     * configuring the pagination settings then it runs without taking the pagination value otherwise every
     * subsequent click would fetch the next `n` values instead of getting the same values. It should consider the
     * cursor value only when the query is triggered via a paginated widget.
     */
    @Test
    public void testNextCursorKeyIsSkippedWhenPaginationValueIsNull() {
        ActionConfiguration actionConfig = getDefaultActionConfiguration();
        actionConfig.setPaginationType(PaginationType.CURSOR);
        actionConfig.getPluginSpecifiedTemplates().get(QUERY_VARIABLES_INDEX).setValue("{}");

        Map<String, Object> paginationDataMap = new HashMap<>();
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.limit.name", "first");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.limit.value", "3");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.cursor.name", "endCursor");
        setValueSafelyInFormData(paginationDataMap, "cursorBased.next.cursor.value", "null");
        Property property = new Property();
        property.setKey("paginationData");
        property.setValue(paginationDataMap);
        actionConfig.getPluginSpecifiedTemplates().add(property);

        ExecuteActionDTO executeActionDTO = new ExecuteActionDTO();
        executeActionDTO.setPaginationField(PaginationField.NEXT);

        updateVariablesWithPaginationValues(actionConfig, executeActionDTO);
        String expectedVariableString = "{\"first\":3}";
        assertEquals(
                expectedVariableString,
                actionConfig
                        .getPluginSpecifiedTemplates()
                        .get(QUERY_VARIABLES_INDEX)
                        .getValue());
    }

    @Test
    public void verifyUniquenessOfGraphQLPluginErrorCode() {
        assertEquals(
                GraphQLPluginError.values().length,
                Arrays.stream(GraphQLPluginError.values())
                        .map(GraphQLPluginError::getAppErrorCode)
                        .distinct()
                        .count());

        assertEquals(
                0,
                Arrays.stream(GraphQLPluginError.values())
                        .map(GraphQLPluginError::getAppErrorCode)
                        .filter(appErrorCode -> appErrorCode.length() != 11 || !appErrorCode.startsWith("PE-GQL"))
                        .toList()
                        .size());
    }
}
