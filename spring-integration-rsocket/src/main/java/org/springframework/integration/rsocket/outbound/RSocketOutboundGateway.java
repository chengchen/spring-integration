/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.rsocket.outbound;

import java.util.function.Consumer;

import org.reactivestreams.Publisher;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.handler.AbstractReplyProducingMessageHandler;
import org.springframework.messaging.Message;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import io.rsocket.RSocketFactory;
import io.rsocket.transport.ClientTransport;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * An Outbound Messaging Gateway for RSocket client requests.
 *
 * @author Artem Bilan
 *
 * @since 5.2
 *
 * @see RSocketRequester
 */
public class RSocketOutboundGateway extends AbstractReplyProducingMessageHandler {

	private final ClientTransport clientTransport;

	private final Expression routeExpression;

	private MimeType dataMimeType = MimeTypeUtils.TEXT_PLAIN;

	private Consumer<RSocketFactory.ClientRSocketFactory> factoryConfigurer = (clientRSocketFactory) -> { };

	private Consumer<RSocketStrategies.Builder> strategiesConfigurer = (builder) -> { };

	private Expression commandExpression = new ValueExpression<>(Command.requestResponse);

	private Expression publisherElementTypeExpression = new ValueExpression<>(String.class);

	private Expression expectedResponseTypeExpression = new ValueExpression<>(String.class);

	private Mono<RSocketRequester> rSocketRequesterMono;

	private EvaluationContext evaluationContext;

	public RSocketOutboundGateway(ClientTransport clientTransport, String route) {
		this(clientTransport, new ValueExpression<>(route));
	}

	public RSocketOutboundGateway(ClientTransport clientTransport, Expression routeExpression) {
		Assert.notNull(clientTransport, "'clientTransport' must not be null");
		Assert.notNull(routeExpression, "'routeExpression' must not be null");
		this.clientTransport = clientTransport;
		this.routeExpression = routeExpression;
		setAsync(true);
		setPrimaryExpression(this.routeExpression);
	}

	public void setDataMimeType(MimeType dataMimeType) {
		Assert.notNull(dataMimeType, "'dataMimeType' must not be null");
		this.dataMimeType = dataMimeType;
	}

	public void setFactoryConfigurer(Consumer<RSocketFactory.ClientRSocketFactory> factoryConfigurer) {
		Assert.notNull(factoryConfigurer, "'factoryConfigurer' must not be null");
		this.factoryConfigurer = factoryConfigurer;
	}

	public void setStrategiesConfigurer(Consumer<RSocketStrategies.Builder> strategiesConfigurer) {
		Assert.notNull(strategiesConfigurer, "'strategiesConfigurer' must not be null");
		this.strategiesConfigurer = strategiesConfigurer;
	}

	public void setCommand(Command command) {
		setCommandExpression(new ValueExpression<>(command));
	}

	public void setCommandExpression(Expression commandExpression) {
		Assert.notNull(commandExpression, "'commandExpression' must not be null");
		this.commandExpression = commandExpression;
	}

	/**
	 * Configure a type for a request {@link Publisher} elements.
	 * @param publisherElementType the type of the request {@link Publisher} elements.
	 * @see RSocketRequester.RequestSpec#data(Publisher, Class)
	 */
	public void setPublisherElementType(Class<?> publisherElementType) {
		Assert.notNull(publisherElementType, "'publisherElementType' must not be null");
		setPublisherElementTypeExpression(new ValueExpression<>(publisherElementType));

	}

	/**
	 * Configure a SpEL expression to evaluate a request {@link Publisher} elements type at runtime against
	 * a request message.
	 * @param publisherElementTypeExpression the expression to evaluate a type for the request
	 * {@link Publisher} elements.
	 * @see RSocketRequester.RequestSpec#data
	 */
	public void setPublisherElementTypeExpression(Expression publisherElementTypeExpression) {
		this.publisherElementTypeExpression = publisherElementTypeExpression;
	}

	/**
	 * Specify the expected response type for the RSocket response.
	 * @param expectedResponseType The expected type.
	 * @see #setExpectedResponseTypeExpression(Expression)
	 * @see RSocketRequester.ResponseSpec#retrieveMono
	 * @see RSocketRequester.ResponseSpec#retrieveFlux
	 */
	public void setExpectedResponseType(Class<?> expectedResponseType) {
		Assert.notNull(expectedResponseType, "'expectedResponseType' must not be null");
		setExpectedResponseTypeExpression(new ValueExpression<>(expectedResponseType));
	}

	/**
	 * Specify the {@link Expression} to determine the type for the RSocket response.
	 * @param expectedResponseTypeExpression The expected response type expression.
	 * @see RSocketRequester.ResponseSpec#retrieveMono
	 * @see RSocketRequester.ResponseSpec#retrieveFlux
	 */
	public void setExpectedResponseTypeExpression(Expression expectedResponseTypeExpression) {
		this.expectedResponseTypeExpression = expectedResponseTypeExpression;
	}


	@Override
	protected void doInit() {
		super.doInit();
		this.rSocketRequesterMono =
				RSocketRequester.builder()
						.rsocketFactory(this.factoryConfigurer)
						.rsocketStrategies(this.strategiesConfigurer)
						.connect(this.clientTransport, this.dataMimeType);

		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(getBeanFactory());
	}

	@Override
	public void destroy() {
		super.destroy();
		this.rSocketRequesterMono.map(RSocketRequester::rsocket)
				.doOnNext(Disposable::dispose)
				.subscribe();
	}

	@Override
	protected Object handleRequestMessage(Message<?> requestMessage) {
		return this.rSocketRequesterMono.cache()
				.map((rSocketRequester) -> createRequestSpec(rSocketRequester, requestMessage))
				.map((requestSpec) -> createResponseSpec(requestSpec, requestMessage))
				.flatMap((responseSpec) -> performRequest(responseSpec, requestMessage));
	}

	private RSocketRequester.RequestSpec createRequestSpec(RSocketRequester rSocketRequester,
			Message<?> requestMessage) {

		String route = this.routeExpression.getValue(this.evaluationContext, requestMessage, String.class);
		Assert.notNull(route, () -> "The 'routeExpression' [" + this.routeExpression + "] must not evaluate to null");

		return rSocketRequester.route(route);
	}

	private RSocketRequester.ResponseSpec createResponseSpec(RSocketRequester.RequestSpec requestSpec,
			Message<?> requestMessage) {

		Object payload = requestMessage.getPayload();

		if (payload instanceof Publisher<?>) {
			Object publisherElementType = evaluateExpressionForType(requestMessage, this.publisherElementTypeExpression,
					"publisherElementTypeExpression");
			return responseSpecForPublisher(requestSpec, (Publisher<?>) payload, publisherElementType);
		}
		else {
			return requestSpec.data(payload);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private RSocketRequester.ResponseSpec responseSpecForPublisher(RSocketRequester.RequestSpec requestSpec,
			Publisher<?> payload, Object publisherElementType) {

		if (publisherElementType instanceof Class<?>) {
			return requestSpec.data(payload, (Class) publisherElementType);
		}
		else {
			return requestSpec.data(payload, (ParameterizedTypeReference) publisherElementType);
		}
	}

	private Mono<?> performRequest(RSocketRequester.ResponseSpec responseSpec, Message<?> requestMessage) {
		Command command = this.commandExpression.getValue(this.evaluationContext, requestMessage, Command.class);
		Assert.notNull(command, () -> "The 'commandExpression' [" + this.commandExpression +
				"] must not evaluate to null");

		Object expectedResponseType = null;
		if (!Command.fireAndForget.equals(command)) {
			expectedResponseType = evaluateExpressionForType(requestMessage, this.expectedResponseTypeExpression,
					"expectedResponseTypeExpression");
		}

		switch (command) {
			case fireAndForget:
				return responseSpec.send();
			case requestResponse:
				if (expectedResponseType instanceof Class<?>) {
					return responseSpec.retrieveMono((Class<?>) expectedResponseType);
				}
				else {
					return responseSpec.retrieveMono((ParameterizedTypeReference<?>) expectedResponseType);
				}
			case requestStreamOrChannel:
				if (expectedResponseType instanceof Class<?>) {
					return Mono.just(responseSpec.retrieveFlux((Class<?>) expectedResponseType));
				}
				else {
					return Mono.just(responseSpec.retrieveFlux((ParameterizedTypeReference<?>) expectedResponseType));
				}
			default:
				throw new UnsupportedOperationException("Unsupported command: " + command);
		}
	}

	private Object evaluateExpressionForType(Message<?> requestMessage, Expression expression, String propertyName) {
		Object type = expression.getValue(this.evaluationContext, requestMessage);
		Assert.state(type instanceof Class<?>
						|| type instanceof String
						|| type instanceof ParameterizedTypeReference<?>,
				() -> "The '" + propertyName + "' [" + expression +
						"] must evaluate to 'String' (class FQN), 'Class<?>' " +
						"or 'ParameterizedTypeReference<?>', not to: " + type);

		if (type instanceof String) {
			try {
				return ClassUtils.forName((String) type, getBeanClassLoader());
			}
			catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		else {
			return type;
		}
	}

	/**
	 * Enumeration of commands supported by the gateways.
	 */
	public enum Command {

		/**
		 * Perform {@link io.rsocket.RSocket#fireAndForget fireAndForget}.
		 * @see RSocketRequester.ResponseSpec#send()
		 */
		fireAndForget,

		/**
		 * Perform {@link io.rsocket.RSocket#requestResponse requestResponse}.
		 * @see RSocketRequester.ResponseSpec#retrieveMono
		 */
		requestResponse,

		/**
		 * Perform {@link io.rsocket.RSocket#requestStream requestStream} or
		 * {@link io.rsocket.RSocket#requestChannel requestChannel} depending on whether
		 * the request input consists of a single or multiple payloads.
		 * @see RSocketRequester.ResponseSpec#retrieveFlux
		 */
		requestStreamOrChannel

	}

}
