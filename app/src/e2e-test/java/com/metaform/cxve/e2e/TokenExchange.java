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

package com.metaform.cxve.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static io.restassured.RestAssured.given;

/**
 * The TokenExchange class provides functionality for obtaining tokens required for authentication
 * and authorization in a Kubernetes and service integration context. It includes methods for
 * retrieving a workload token from the Kubernetes environment and exchanging it for a participant token
 * from a specified token exchange service.
 */
public class TokenExchange {
    private final String tokenExchangeUrl;
    private final String kubernetesConfigFile;

    public TokenExchange(String tokenExchangeUrl, String kubernetesConfigFile) {
        this.tokenExchangeUrl = tokenExchangeUrl;
        this.kubernetesConfigFile = kubernetesConfigFile;
    }

    public TokenExchange(String tokenExchangeUrl) {
        this(tokenExchangeUrl, null);
    }


    /**
     * Retrieves a workload token from the Kubernetes environment for the specified service account.
     * For this, the {@code kubectl} command is used.
     *
     * @param serviceAccountName The name of the service account for which to retrieve the token.
     * @return The workload token as a string.
     */
    public String getWorkLoadToken(String serviceAccountName) {
        try {
            // mutable copy: Arrays.asList is fixed-size and would throw on add()
            var command = new ArrayList<>(Arrays.asList("kubectl", "create", "token", serviceAccountName, "-n", "edc-v",
                    "--audience=https://kubernetes.default.svc.cluster.local"));

            if (kubernetesConfigFile != null) {
                command.add("--kubeconfig=%s".formatted(kubernetesConfigFile));
            }

            var process = new ProcessBuilder(command).start();

            var exitCode = process.waitFor();
            if (exitCode != 0) {
                String stderr;
                try (var errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    stderr = errorReader.lines().reduce("", (a, b) -> a + "\n" + b);
                }
                throw new RuntimeException("kubectl process failed with exit code: " + exitCode + ". Error: " + stderr);
            }

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.lines().findFirst().orElseThrow(() -> new RuntimeException("No token returned"));
            }
        } catch (IOException |
                 InterruptedException e) {
            throw new RuntimeException("Failed to execute kubectl command", e);
        }
    }

    /**
     * Retrieves a participant token for a specified resource and scopes using a default service account ("redline").
     * This token can be used for accessing services that require authentication within the specified resource
     * and scopes settings.
     *
     * @param resource The resource for which the token is requested.
     * @param scopes   The scopes that define the access permissions for the token.
     * @return A string representing the participant token for the specified resource and scopes.
     */
    public String getParticipantToken(String resource, String scopes) {
        return getParticipantToken("certo", resource, scopes);
    }

    /**
     * Retrieves a participant token for a specified service account, resource, and scopes.
     * The token is used for authenticating requests to services that require access permissions
     * within the specified resource and scopes.
     *
     * @param serviceAccount The name of the service account for which the participant token is requested.
     * @param resource       The resource for which access is required.
     * @param scopes         The scopes that define the permissions for the token.
     * @return A string representing the participant access token.
     */
    public String getParticipantToken(String serviceAccount, String resource, String scopes) {
        var workloadToken = getWorkLoadToken(serviceAccount);

        return given()
                .baseUri(tokenExchangeUrl)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .formParam("subject_token", workloadToken)
                .formParam("subject_token_type", "urn:ietf:params:oauth:token-type:jwt")
                .formParam("resource", resource)
                .formParam("scope", scopes)
                .post()
                .then()
                .statusCode(200)
                .extract()
                .path("access_token");
    }

}
