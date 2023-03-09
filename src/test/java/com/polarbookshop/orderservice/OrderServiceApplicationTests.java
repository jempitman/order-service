package com.polarbookshop.orderservice;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polarbookshop.orderservice.book.Book;
import com.polarbookshop.orderservice.book.BookClient;
import com.polarbookshop.orderservice.order.domain.Order;
import com.polarbookshop.orderservice.order.domain.OrderStatus;
import com.polarbookshop.orderservice.order.event.OrderAcceptedMessage;
import com.polarbookshop.orderservice.order.web.OrderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import dasniko.testcontainers.keycloak.KeycloakContainer;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestChannelBinderConfiguration.class)
@Testcontainers
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
class OrderServiceApplicationTests {

	public static KeycloakToken bjornTokens;
	public static KeycloakToken isabelleTokens;

	@Container
	private static final KeycloakContainer keycloakContainer =
			new KeycloakContainer("quay.io/keycloak/keycloak:19.0")
					.withRealmImportFile("test-realm-config.json");


	@BeforeAll
	static void generateAccessTokens(){
		WebClient webClient = WebClient.builder()
				.baseUrl(keycloakContainer.getAuthServerUrl()
				+ "realms/PolarBookshop/protocol/openid-connect/token")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
				.build();
		isabelleTokens = authenticateWith(
				"isabelle", "password", webClient);
		bjornTokens = authenticateWith(
				"bjorn", "password", webClient);

	}

	private static KeycloakToken authenticateWith(
			String username, String password, WebClient webClient
	){
		return webClient
				.post()
				.body(
						BodyInserters.fromFormData("grant_type", "password")
								.with("client_id", "polar-test")
								.with("username", username)
								.with("password", password)
				)
				.retrieve()
				.bodyToMono(KeycloakToken.class)
				.block();
	}

	private record KeycloakToken(String accessToken){
		@JsonCreator
		private KeycloakToken(
				@JsonProperty("access_token") final String accessToken
		){
			this.accessToken = accessToken;
		}
	}

	@Container
	static PostgreSQLContainer<?> postgresql =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.4"));

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private OutputDestination output;

	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BookClient bookClient;

	@DynamicPropertySource
	static void setPostgresqlProperties(DynamicPropertyRegistry registry){
		registry.add("spring.r2dbc.url", OrderServiceApplicationTests::r2dbcUrl);
		registry.add("spring.r2dbc.username", postgresql::getUsername);
		registry.add("spring.r2dbc.password", postgresql::getPassword);
		registry.add("spring.flyway.url", postgresql::getJdbcUrl);
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
				() -> keycloakContainer.getAuthServerUrl() + "realms/PolarBookshop");
	}

	private static String r2dbcUrl(){
		return String.format("r2dbc:postgresql://%s:%s/%s", postgresql.getHost(),
				postgresql.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), postgresql.getDatabaseName());
	}

	@Test
	void whenGetOwnOrdersThenReturn() throws IOException {
		String bookIsbn = "1234567893";
		Book book = new Book(bookIsbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(bookIsbn)).willReturn(Mono.just(book));
		OrderRequest orderRequest = new OrderRequest(bookIsbn, 1);
		Order expectedOrder = webTestClient.post().uri("/orders")
				.headers(httpHeaders ->
						httpHeaders.setBearerAuth(bjornTokens.accessToken()))
				.bodyValue(orderRequest)
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectBody(Order.class).returnResult().getResponseBody();
		assertThat(expectedOrder).isNotNull();
		assertThat(objectMapper.readValue(output.receive().getPayload(), OrderAcceptedMessage.class))
				.isEqualTo(new OrderAcceptedMessage(expectedOrder.id()));

		webTestClient.get().uri("/orders")
				.headers(httpHeaders -> httpHeaders.setBearerAuth(bjornTokens.accessToken()))
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectBodyList(Order.class).value(orders -> {
					assertThat(orders.stream().filter(order -> order.bookIsbn().equals(bookIsbn))
							.findAny()).isNotEmpty();
				});
	}

	@Test
	void whenAuthenticatedPostRequestAndBookExistsThenOrderAccepted () throws IOException{
		String bookIsbn = "1234567899";
		Book book = new Book(bookIsbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(bookIsbn)).willReturn(Mono.just(book));
		OrderRequest orderRequest = new OrderRequest(bookIsbn, 3);

		Order createdOrder = webTestClient.post().uri("/orders")
				.headers(headers -> headers.setBearerAuth(bjornTokens.accessToken()))
				.bodyValue(orderRequest)
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectBody(Order.class).returnResult().getResponseBody();

		assertThat(createdOrder).isNotNull();
		assertThat(createdOrder.bookIsbn()).isEqualTo(orderRequest.isbn());
		assertThat(createdOrder.quantity()).isEqualTo(orderRequest.quantity());
		assertThat(createdOrder.bookName()).isEqualTo(book.title() + " - " + book.author());
		assertThat(createdOrder.bookPrice()).isEqualTo(book.price());
		assertThat(createdOrder.status()).isEqualTo(OrderStatus.ACCEPTED);

		assertThat(objectMapper.readValue(output.receive().getPayload(), OrderAcceptedMessage.class))
				.isEqualTo(new OrderAcceptedMessage(createdOrder.id()));
	}

	@Test
	void whenAuthenticatedPostRequestAndBookNotExistsThenOrderRejected(){
		String bookIsbn = "1234567894";
		given(bookClient.getBookByIsbn(bookIsbn)).willReturn(Mono.empty());
		OrderRequest orderRequest = new OrderRequest(bookIsbn, 3);

		Order createdOrder = webTestClient.post().uri("/orders")
				.headers(headers -> headers.setBearerAuth(bjornTokens.accessToken())).bodyValue(orderRequest)
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectBody(Order.class).returnResult().getResponseBody();

		assertThat(createdOrder).isNotNull();
		assertThat(createdOrder.bookIsbn()).isEqualTo(orderRequest.isbn());
		assertThat(createdOrder.quantity()).isEqualTo(orderRequest.quantity());
		assertThat(createdOrder.status()).isEqualTo(OrderStatus.REJECTED);

	}
}
