/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seata.namingserver.config;

import jakarta.servlet.Filter;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.RequestContent;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.seata.namingserver.filter.ConsoleRemotingFilter;
import org.apache.seata.namingserver.manager.NamingManager;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static org.apache.seata.namingserver.contants.NamingConstant.DEFAULT_CONNECTION_MAX_PER_ROUTE;
import static org.apache.seata.namingserver.contants.NamingConstant.DEFAULT_CONNECTION_MAX_TOTAL;
import static org.apache.seata.namingserver.contants.NamingConstant.DEFAULT_REQUEST_TIMEOUT;

@Configuration
public class WebConfig {

    @Bean
    public RestTemplate restTemplate() {
        // Create a connection manager with custom settings
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(DEFAULT_CONNECTION_MAX_TOTAL); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(DEFAULT_CONNECTION_MAX_PER_ROUTE); // Maximum connections per route
        // Create an HttpClient with the connection manager
        CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(connectionManager).build();
        // Create a request factory with the HttpClient
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(DEFAULT_REQUEST_TIMEOUT); // Connection timeout in milliseconds
        requestFactory.setConnectionRequestTimeout(DEFAULT_REQUEST_TIMEOUT); // Read timeout in milliseconds
        // Create and return a RestTemplate with the custom request factory
        return new RestTemplate(requestFactory);
    }

    @Bean
    public CloseableHttpAsyncClient asyncRestTemplate() {
        final AsyncClientConnectionManager connectionManager = getConnectionManager();
        CloseableHttpAsyncClient client =  HttpAsyncClients.custom()
                .addRequestInterceptorLast(new RequestContent(true))

                .setIOReactorConfig(IOReactorConfig.DEFAULT)
                // catch all exceptions here instead of in DefaultConnectingIOReactor
                .setIoReactorExceptionCallback((ex) -> {

                })
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(DEFAULT_REQUEST_TIMEOUT,
                                TimeUnit.SECONDS).build())
                .setConnectionManager(connectionManager)
                .build();
        client.start();
        return client;
    }

    @Bean
    public FilterRegistrationBean<Filter> consoleRemotingFilter(NamingManager namingManager,
            CloseableHttpAsyncClient asyncRestTemplate) {
        ConsoleRemotingFilter consoleRemotingFilter = new ConsoleRemotingFilter(namingManager, asyncRestTemplate);
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(consoleRemotingFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
    private AsyncClientConnectionManager getConnectionManager() {
        try {
            SSLContext sslcontext = SSLContext.getDefault();
            HostnameVerifier hostnameVerifier = new DefaultHostnameVerifier();
            TlsStrategy sslStrategy = new DefaultClientTlsStrategy(sslcontext, hostnameVerifier);
            // manager no more needs IOReactor
            return PoolingAsyncClientConnectionManagerBuilder
                    // old method Registry::register("http", NoopIOSessionStrategy.INSTANCE) has been a default strategy
                    .create()
                    // refers to old Registry::register("https", sslStrategy)
                    .setTlsStrategy(sslStrategy)
                    // setMaxTotal now can be used in builder
                    .setMaxConnTotal(DEFAULT_CONNECTION_MAX_TOTAL)
                    // setDefaultMaxPerRoute now can be used in builder
                    .setMaxConnPerRoute(DEFAULT_CONNECTION_MAX_PER_ROUTE)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(DEFAULT_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS)
                            .build())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
