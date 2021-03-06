/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import static io.opentelemetry.trace.Span.Kind.CLIENT

import groovy.json.JsonSlurper
import io.opentelemetry.instrumentation.test.AgentTestRunner
import io.opentelemetry.instrumentation.api.tracer.HttpClientTracer
import io.opentelemetry.trace.attributes.SemanticAttributes
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.util.EntityUtils
import org.elasticsearch.client.Request
import org.elasticsearch.client.Response
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.TransportAddress
import org.elasticsearch.env.Environment
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.node.InternalSettingsPreparer
import org.elasticsearch.node.Node
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.transport.Netty4Plugin
import spock.lang.Shared

class Elasticsearch6RestClientTest extends AgentTestRunner {

  @Shared
  TransportAddress httpTransportAddress
  @Shared
  Node testNode
  @Shared
  File esWorkingDir

  @Shared
  RestClient client
  @Shared
  String clusterName = UUID.randomUUID().toString()

  def setupSpec() {

    esWorkingDir = File.createTempDir("test-es-working-dir-", "")
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
      .put("cluster.name", clusterName)
      .build()
    testNode = new TestNode(InternalSettingsPreparer.prepareEnvironment(settings, null), [Netty4Plugin])
    testNode.start()
    httpTransportAddress = testNode.injector().getInstance(HttpServerTransport).boundAddress().publishAddress()

    client = RestClient.builder(new HttpHost(httpTransportAddress.address, httpTransportAddress.port))
      .setMaxRetryTimeoutMillis(Integer.MAX_VALUE)
      .setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
        @Override
        RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder builder) {
          return builder.setConnectTimeout(Integer.MAX_VALUE).setSocketTimeout(Integer.MAX_VALUE)
        }
      })
      .build()

  }

  def cleanupSpec() {
    testNode?.close()
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch status"() {
    setup:
    Request request = new Request("GET", "_cluster/health")
    Response response = client.performRequest(request)

    Map result = new JsonSlurper().parseText(EntityUtils.toString(response.entity))

    expect:
    result.status == "green"

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "GET _cluster/health"
          kind CLIENT
          hasNoParent()
          attributes {
            "${SemanticAttributes.NET_PEER_NAME.key()}" httpTransportAddress.address
            "${SemanticAttributes.NET_PEER_PORT.key()}" httpTransportAddress.port
            "${SemanticAttributes.HTTP_URL.key()}" "_cluster/health"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "GET _cluster/health"
          }
        }
        span(1) {
          name expectedOperationName("GET")
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key()}" "IP.TCP"
            "${SemanticAttributes.HTTP_URL.key()}" "_cluster/health"
            "${SemanticAttributes.HTTP_METHOD.key()}" "GET"
            "${SemanticAttributes.HTTP_STATUS_CODE.key()}" 200
            "${SemanticAttributes.HTTP_FLAVOR.key()}" "1.1"
          }
        }
      }
    }
  }

  static class TestNode extends Node {
    TestNode(Environment environment, Collection<Class<? extends Plugin>> classpathPlugins) {
      super(environment, classpathPlugins, false)
    }

    @Override
    protected void registerDerivedNodeNameWithLogger(String nodeName) {}
  }

  String expectedOperationName(String method) {
    return method != null ? "HTTP $method" : HttpClientTracer.DEFAULT_SPAN_NAME
  }
}
