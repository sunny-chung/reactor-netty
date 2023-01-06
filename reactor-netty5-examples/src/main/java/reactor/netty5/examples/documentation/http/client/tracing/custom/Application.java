/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.examples.documentation.http.client.tracing.custom;

import brave.Tracing;
import brave.handler.SpanHandler;
import brave.propagation.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.util.concurrent.Future;
import reactor.netty5.NettyPipeline;
import reactor.netty5.http.client.HttpClient;
import reactor.netty5.http.observability.ReactorNettyPropagatingSenderTracingObservationHandler;
import reactor.netty5.observability.ReactorNettyTracingObservationHandler;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.brave.ZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

import static reactor.netty5.Metrics.OBSERVATION_REGISTRY;

public class Application {

	public static void main(String[] args) {
		init(); //<1>

		HttpClient client =
				HttpClient.create()
				          .metrics(true, s -> {
				              if (s.startsWith("/stream/")) { //<2>
				                  return "/stream/{n}";
				              }
				              return s;
				          }) //<3>
				          .doOnConnected(conn -> conn.channel().pipeline().addAfter(NettyPipeline.HttpCodec,
				                  "custom-channel-handler", CustomChannelOutboundHandler.INSTANCE)); //<4>

		client.get()
		      .uri("https://httpbin.org/stream/3")
		      .responseContent()
		      .blockLast();
	}

	static final class CustomChannelOutboundHandler extends ChannelHandlerAdapter {

		static final ChannelHandler INSTANCE = new CustomChannelOutboundHandler();

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		@SuppressWarnings("try")
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			try (ContextSnapshot.Scope scope = ContextSnapshot.captureFrom(ctx.channel()).setThreadLocals()) {
				System.out.println("Current Observation in Scope: " + OBSERVATION_REGISTRY.getCurrentObservation());
			}
			System.out.println("Current Observation: " + OBSERVATION_REGISTRY.getCurrentObservation());
			return ctx.write(msg);
		}
	}

	/**
	 * This setup is based on
	 * <a href="https://micrometer.io/docs/tracing#_micrometer_tracing_brave_setup">Micrometer Tracing Brave Setup</a>
	 */
	static void init() {
		SpanHandler spanHandler = ZipkinSpanHandler
				.create(AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans")));

		StrictCurrentTraceContext braveCurrentTraceContext = StrictCurrentTraceContext.create();

		CurrentTraceContext bridgeContext = new BraveCurrentTraceContext(braveCurrentTraceContext);

		Tracing tracing =
				Tracing.newBuilder()
				       .currentTraceContext(braveCurrentTraceContext)
				       .supportsJoin(false)
				       .traceId128Bit(true)
				       .sampler(Sampler.ALWAYS_SAMPLE)
				       .addSpanHandler(spanHandler)
				       .localServiceName("reactor-netty-examples")
				       .build();

		brave.Tracer braveTracer = tracing.tracer();

		Tracer tracer = new BraveTracer(braveTracer, bridgeContext, new BraveBaggageManager());

		Propagator propagator = new BravePropagator(tracing);

		OBSERVATION_REGISTRY.observationConfig()
		                    .observationHandler(new ReactorNettyPropagatingSenderTracingObservationHandler(tracer, propagator))
		                    .observationHandler(new ReactorNettyTracingObservationHandler(tracer));
	}
}