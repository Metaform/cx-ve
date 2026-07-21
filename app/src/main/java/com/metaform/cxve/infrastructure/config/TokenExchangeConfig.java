/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package com.metaform.cxve.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TokenExchangeConfig {
    @Value("${token.exchange.url:http://jad.localhost/api/auth}")
    private String tokenExchangeUrl;

    @Bean
    public RestClient tokenExchangeClient() {
        return RestClient.builder()
                .baseUrl(tokenExchangeUrl)
                .build();
    }
}
